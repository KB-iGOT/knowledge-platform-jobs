package org.sunbird.job.karmapoints.v2.handlers

import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.karmapoints.v2.config.KarmaPointsV2Config
import org.sunbird.job.karmapoints.v2.domain.UnifiedEvent
import org.sunbird.job.karmapoints.v2.exceptions.{InvalidPayloadException, MissingPayloadException}
import org.sunbird.job.karmapoints.v2.storage.{CassandraUtil, RedisUtil}

/**
 * Handles both ASSESSMENT_PASSED and ASSESSMENT_HIGH_SCORE events:
 * {
 *   "eventType": "ASSESSMENT_PASSED" | "ASSESSMENT_HIGH_SCORE",
 *   "data": { "edata": { "userId": "...", "courseId": "...", "batchId": "...",
 *                        "assessmentId": "...", "score": 68.5 } },
 *   "version": 1
 * }
 * Kafka key: userId
 *
 * A single assessment can independently earn both awards (once for passing, once again for a high
 * score), so each is its own dedup identity in `user_karma_points_credit_lookup`:
 * `userId|ASSESSMENT_PASSED|assessmentId|courseId` and `userId|ASSESSMENT_HIGH_SCORE|assessmentId|courseId`
 * - distinct enough that neither award blocks the other, same generic
 * `CassandraUtil.doesEntryExistByKey`/`insertKarmaPointsWithLookupKey` every other composite-lookup
 * handler (e.g. SurveySubmissionHandler) already uses. Ledger `context_type`/`context_id` are
 * `Course`/`courseId` for both - `assessmentId`/`batchId`/`score` are carried only in `addinfo`,
 * never as `context_id`. `addToKarmaSummary`/`RedisUtil.setUserKarmaPoints` are reused unchanged.
 */
class AssessmentHandler(config: KarmaPointsV2Config, cassandraUtil: CassandraUtil, redisUtil: RedisUtil) extends EventHandler {
  private[this] val logger = LoggerFactory.getLogger(classOf[AssessmentHandler])

  override protected def doHandle(event: UnifiedEvent)(implicit metrics: Metrics): Unit = {
    val userId = event.dataEdataString("userId")
    val courseId = event.dataEdataString("courseId")
    val batchId = event.dataEdataString("batchId")
    val assessmentId = event.dataEdataString("assessmentId")
    val scoreStr = event.dataEdataString("score")

    if (StringUtils.isBlank(userId)) {
      throw MissingPayloadException(s"${event.eventType} event is missing userId (data.edata.userId)")
    }
    if (StringUtils.isBlank(courseId)) {
      throw MissingPayloadException(s"${event.eventType} event is missing courseId (data.edata.courseId) for userId=$userId")
    }
    if (StringUtils.isBlank(batchId)) {
      throw MissingPayloadException(s"${event.eventType} event is missing batchId (data.edata.batchId) for userId=$userId")
    }
    if (StringUtils.isBlank(assessmentId)) {
      throw MissingPayloadException(s"${event.eventType} event is missing assessmentId (data.edata.assessmentId) for userId=$userId")
    }
    if (StringUtils.isBlank(scoreStr)) {
      throw MissingPayloadException(s"${event.eventType} event is missing score (data.edata.score) for userId=$userId")
    }
    // "score" arrives as either an integer-valued or decimal JSON number (e.g. 75 or 75.0) -
    // toDouble normalizes both the same way, so no separate handling is needed for either shape.
    val score = try scoreStr.toDouble catch {
      case _: NumberFormatException =>
        throw InvalidPayloadException(s"${event.eventType} event has a non-numeric score='$scoreStr' for userId=$userId")
    }

    event.eventType match {
      case config.EVENT_TYPE_ASSESSMENT_PASSED => handlePassed(userId, courseId, batchId, assessmentId, score)
      case config.EVENT_TYPE_ASSESSMENT_HIGH_SCORE => handleHighScore(userId, courseId, batchId, assessmentId, score)
    }
  }

  private def handlePassed(userId: String, courseId: String, batchId: String, assessmentId: String, score: Double)
                           (implicit metrics: Metrics): Unit = {
    val lookupKey = s"$userId${config.PIPE}${config.OPERATION_TYPE_ASSESSMENT_PASSED}${config.PIPE}$assessmentId${config.PIPE}$courseId"
    if (cassandraUtil.doesEntryExistByKey(lookupKey, config.OPERATION_TYPE_ASSESSMENT_PASSED)) {
      logger.info(s"ASSESSMENT_PASSED karma points already awarded: userId=$userId, courseId=$courseId, assessmentId=$assessmentId - skipping duplicate")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val points = config.assessmentPassedQuotaKarmaPoints
    val addInfo = cassandraUtil.buildAddInfo(null, config.ADDINFO_ASSESSMENT_STATUS -> config.ASSESSMENT_STATUS_PASS,
      config.ADDINFO_SCORE -> score, config.ADDINFO_ASSESSMENT_ID -> assessmentId, config.ADDINFO_BATCH_ID -> batchId)
    cassandraUtil.insertKarmaPointsWithLookupKey(userId, config.COURSE, config.OPERATION_TYPE_ASSESSMENT_PASSED,
      courseId, points, addInfo, lookupKey)
    logger.info(s"ASSESSMENT_PASSED Karma Points awarded: userId=$userId, courseId=$courseId, assessmentId=$assessmentId, points=$points, score=$score")
    val newTotal = cassandraUtil.addToKarmaSummary(userId, points)
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }

  private def handleHighScore(userId: String, courseId: String, batchId: String, assessmentId: String, score: Double)
                              (implicit metrics: Metrics): Unit = {
    if (score <= config.assessmentHighScoreThreshold) {
      logger.info(s"ASSESSMENT_HIGH_SCORE score=$score does not exceed threshold=${config.assessmentHighScoreThreshold}: userId=$userId, courseId=$courseId, assessmentId=$assessmentId - not awarding")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val lookupKey = s"$userId${config.PIPE}${config.OPERATION_TYPE_ASSESSMENT_HIGH_SCORE}${config.PIPE}$assessmentId${config.PIPE}$courseId"
    if (cassandraUtil.doesEntryExistByKey(lookupKey, config.OPERATION_TYPE_ASSESSMENT_HIGH_SCORE)) {
      logger.info(s"ASSESSMENT_HIGH_SCORE karma points already awarded: userId=$userId, courseId=$courseId, assessmentId=$assessmentId - skipping duplicate")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val points = config.assessmentHighScoreQuotaKarmaPoints
    val addInfo = cassandraUtil.buildAddInfo(null, config.ADDINFO_ASSESSMENT_STATUS -> config.ASSESSMENT_STATUS_HIGH_SCORE,
      config.ADDINFO_SCORE -> score, config.ADDINFO_ASSESSMENT_ID -> assessmentId, config.ADDINFO_BATCH_ID -> batchId)
    cassandraUtil.insertKarmaPointsWithLookupKey(userId, config.COURSE, config.OPERATION_TYPE_ASSESSMENT_HIGH_SCORE,
      courseId, points, addInfo, lookupKey)
    logger.info(s"ASSESSMENT_HIGH_SCORE Karma Points awarded: userId=$userId, courseId=$courseId, assessmentId=$assessmentId, points=$points, score=$score")
    val newTotal = cassandraUtil.addToKarmaSummary(userId, points)
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }
}
