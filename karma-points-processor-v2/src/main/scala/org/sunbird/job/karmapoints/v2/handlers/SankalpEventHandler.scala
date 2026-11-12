package org.sunbird.job.karmapoints.v2.handlers

import java.time.format.{DateTimeFormatter, DateTimeParseException, ResolverStyle}
import java.time.{DateTimeException, Instant, LocalDate, LocalTime, ZoneId, ZonedDateTime}
import java.util.Locale

import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.karmapoints.v2.config.KarmaPointsV2Config
import org.sunbird.job.karmapoints.v2.domain.UnifiedEvent
import org.sunbird.job.karmapoints.v2.exceptions.MissingPayloadException
import org.sunbird.job.karmapoints.v2.storage.{CassandraUtil, RedisUtil}

import scala.util.Try

/**
 * Handles SANKALP_EVENT events (Kafka contract unchanged):
 * {
 * "eventType": "SANKALP_EVENT",
 * "data": { "edata": { "userId": "user123", "courseId": "course123", "surveyId": "form123", "date_time": 1787647796434,
 * "eventName": "Karmayogi Sankalpa" } },
 * "version": 1
 * }
 * Kafka key: userId
 *
 * Campaign-specific values come from configuration: sankalpEventKarmaPoints, sankalpEventOperationType,
 * sankalpEventContextType and the Karma Points window (sankalpKarmaPointsProcessStartDate/Time, sankalpKarmaPointsProcessStopDate/Time,
 * sankalpEventTimezone). The event name is read from data.edata.eventName.
 *
 * Awards once per user/course/survey, only when the payload's `date_time` (epoch millis) lies within the
 * Karma Points window (both boundaries inclusive). No monthly quota applies.
 * Dedup identity is `userId|<operationType>|courseId|surveyId` / `operation_type=<operationType>`.
 */
class SankalpEventHandler(config: KarmaPointsV2Config, cassandraUtil: CassandraUtil, redisUtil: RedisUtil) extends EventHandler {
  private[this] val logger = LoggerFactory.getLogger(classOf[SankalpEventHandler])
  private[this] val minEpochMillis = 100000000000L
  private[this] val operationType = config.sankalpEventOperationType
  private[this] val contextType = config.sankalpEventContextType

  private[this] val timezone: ZoneId = try ZoneId.of(config.sankalpEventTimezone)
  catch {
    case e: DateTimeException => throw new IllegalArgumentException(s"Invalid karmapoints.sankalpEventTimezone: ${config.sankalpEventTimezone}", e)
  }
  private[this] val campaignStart: ZonedDateTime = parseBoundary("karmapoints.sankalpKarmaPointsProcessStartDate", config.sankalpKarmaPointsProcessStartDate,
    "karmapoints.sankalpKarmaPointsProcessStartTime", config.sankalpKarmaPointsProcessStartTime, endOfMinute = false)
  private[this] val campaignStop: ZonedDateTime = parseBoundary("karmapoints.sankalpKarmaPointsProcessStopDate", config.sankalpKarmaPointsProcessStopDate,
    "karmapoints.sankalpKarmaPointsProcessStopTime", config.sankalpKarmaPointsProcessStopTime, endOfMinute = true)
  require(StringUtils.isNotBlank(operationType), "karmapoints.sankalpEventOperationType must not be blank")
  require(StringUtils.isNotBlank(contextType), "karmapoints.sankalpEventContextType must not be blank")
  require(config.sankalpEventKarmaPoints > 0, s"karmapoints.sankalpEventKarmaPoints must be positive, got ${config.sankalpEventKarmaPoints}")
  require(campaignStop.toInstant.isAfter(campaignStart.toInstant),
    s"campaign stop ($campaignStop) must be later than campaign start ($campaignStart)")

  override protected def doHandle(event: UnifiedEvent)(implicit metrics: Metrics): Unit = {
    val userId = event.dataEdataString("userId")
    val courseId = event.dataEdataString("courseId")
    val surveyId = event.dataEdataString("surveyId")
    val eventName = event.dataEdataString("eventName")

    if (StringUtils.isBlank(userId)) {
      throw MissingPayloadException(s"Event validation failed: userId is missing (data.edata.userId), ${context(event, userId, courseId, surveyId, eventName)}")
    }
    if (StringUtils.isBlank(courseId)) {
      throw MissingPayloadException(s"Event validation failed: courseId is missing (data.edata.courseId), ${context(event, userId, courseId, surveyId, eventName)}")
    }
    if (StringUtils.isBlank(surveyId)) {
      throw MissingPayloadException(s"Event validation failed: surveyId is missing (data.edata.surveyId), ${context(event, userId, courseId, surveyId, eventName)}")
    }
    if (StringUtils.isBlank(eventName)) {
      throw MissingPayloadException(s"Event validation failed: eventName is missing (data.edata.eventName), ${context(event, userId, courseId, surveyId, eventName)}")
    }

    val dateTimeMs = parseDateTime(event) match {
      case Some(ms) => ms
      case None =>
        logger.warn(s"Event skipped because date_time is missing or invalid: ${context(event, userId, courseId, surveyId, eventName)}, date_time=${event.dataEdata.get("date_time")}")
        metrics.incCounter(config.skippedEventCount)
        return
    }

    val receivedAt = Try(Instant.ofEpochMilli(dateTimeMs).atZone(timezone)).toOption.orNull
    if (receivedAt == null) {
      logger.warn(s"Event skipped because date_time is out of the supported range: ${context(event, userId, courseId, surveyId, eventName)}, date_time=$dateTimeMs")
      metrics.incCounter(config.skippedEventCount)
      return
    }
    val outsideReason = campaignSkipReason(receivedAt)
    if (outsideReason.isDefined) {
      logger.info(s"Event skipped because it is outside the configured campaign window (${outsideReason.get}): ${context(event, userId, courseId, surveyId, eventName)}, " +
        s"receivedTimestamp=$dateTimeMs, convertedDateTime=$receivedAt, campaignStart=$campaignStart, campaignStop=$campaignStop, timezone=$timezone")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val lookupKey = s"$userId${config.PIPE}$operationType${config.PIPE}$courseId${config.PIPE}$surveyId"
    if (cassandraUtil.doesEntryExistByKey(lookupKey, operationType)) {
      logger.info(s"Duplicate event detected, skipping: ${context(event, userId, courseId, surveyId, eventName)}, operationType=$operationType")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val points = config.sankalpEventKarmaPoints
    val addInfo = cassandraUtil.buildAddInfo(null, config.ADDINFO_EVENT_NAME -> eventName, config.ADDINFO_SURVEY_ID -> surveyId,
      config.ADDINFO_EVENTNAME -> eventName, config.ADDINFO_DATE_TIME -> dateTimeMs)
    cassandraUtil.insertKarmaPointsWithLookupKey(userId, contextType, operationType, courseId, points, addInfo, lookupKey)
    val newTotal = cassandraUtil.addToKarmaSummary(userId, points)
    logger.info(s"Event processing completed, ledger record inserted and karma summary updated: ${context(event, userId, courseId, surveyId, eventName)}, " +
      s"operationType=$operationType, contextType=$contextType, points=$points, newTotal=$newTotal, date_time=$dateTimeMs")
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }

  private def parseDateTime(event: UnifiedEvent): Option[Long] = {
    val parsed: Option[Long] = event.dataEdata.get(config.ADDINFO_DATE_TIME) match {
      case Some(n: Long) => Some(n)
      case Some(n: Int) => Some(n.toLong)
      case Some(d: Double) if !d.isNaN && !d.isInfinite && d == Math.rint(d) && Math.abs(d) < 9.0e18 => Some(d.toLong)
      case Some(s: String) if s.nonEmpty && s.forall(c => c >= '0' && c <= '9') => Try(s.toLong).toOption
      case _ => None
    }
    parsed.filter(_ >= minEpochMillis)
  }

  private def campaignSkipReason(received: ZonedDateTime): Option[String] =
    if (received.toInstant.isBefore(campaignStart.toInstant)) Some("before campaign start")
    else if (received.toInstant.isAfter(campaignStop.toInstant)) Some("after campaign stop")
    else None

  private def parseBoundary(dateKey: String, dateValue: String, timeKey: String, timeValue: String, endOfMinute: Boolean): ZonedDateTime = {
    val dateFormatter = DateTimeFormatter.ofPattern("dd-MM-uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    val timeFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    val date = try LocalDate.parse(dateValue, dateFormatter)
    catch {
      case e: DateTimeParseException => throw new IllegalArgumentException(s"Invalid $dateKey: '$dateValue', expected format dd-MM-yyyy", e)
    }
    val time = try LocalTime.parse(timeValue, timeFormatter)
    catch {
      case e: DateTimeParseException => throw new IllegalArgumentException(s"Invalid $timeKey: '$timeValue', expected format hh:mm a (for example 06:30 PM)", e)
    }
    val dateTime = date.atTime(time)
    (if (endOfMinute) dateTime.plusMinutes(1).minusNanos(1000000L) else dateTime).atZone(timezone)
  }

  private def context(event: UnifiedEvent, userId: String, courseId: String, surveyId: String, eventName: String): String =
    s"eventType=${event.eventType}, eventName=$eventName, userId=$userId, courseId=$courseId, surveyId=$surveyId"
}
