package org.sunbird.job.karmapoints.v2.handlers

import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.karmapoints.v2.config.KarmaPointsV2Config
import org.sunbird.job.karmapoints.v2.domain.UnifiedEvent
import org.sunbird.job.karmapoints.v2.exceptions.{InvalidPayloadException, MissingPayloadException}
import org.sunbird.job.karmapoints.v2.storage.{CassandraUtil, RedisUtil}
import org.sunbird.job.karmapoints.v2.utils.ExternalServiceClient

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
class AssessmentHandler(config: KarmaPointsV2Config, cassandraUtil: CassandraUtil, redisUtil: RedisUtil,
                        externalServiceClient: ExternalServiceClient) extends EventHandler {
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

    logger.info(s"Processing ${event.eventType} event: userId=$userId, courseId=$courseId, batchId=$batchId, assessmentId=$assessmentId, score=$score")

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

    val hierarchy = courseHierarchy(config.EVENT_TYPE_ASSESSMENT_PASSED, userId, courseId)
    if (hierarchy.isEmpty) return

    val isAcbp = isAcbpCourse(userId, courseId)
    logger.info(s"ASSESSMENT_PASSED ACBP check: userId=$userId, courseId=$courseId, assessmentId=$assessmentId, isAcbp=$isAcbp")
    if (!isAcbp && cassandraUtil.hasReachedMonthlyCountCutOff(userId, config.ASSESSMENT_PASSED_MONTHLY_COUNT,
      config.ASSESSMENT_PASSED_FORMATTED_MONTH, config.assessmentMonthlyQuota)) {
      logger.info(s"ASSESSMENT_PASSED monthly limit reached for non-ACBP course: userId=$userId, courseId=$courseId, assessmentId=$assessmentId - not awarding")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val points = config.assessmentPassedQuotaKarmaPoints
    val addInfo = cassandraUtil.buildAddInfo(null, config.ADDINFO_ASSESSMENT_STATUS -> config.ASSESSMENT_STATUS_PASS,
      config.ADDINFO_SCORE -> score, config.ADDINFO_ASSESSMENT_ID -> assessmentId, config.ADDINFO_BATCH_ID -> batchId,
      config.ADDINFO_IS_ACBP -> isAcbp,
      config.ADDINFO_COURSENAME -> hierarchy.get.get(config.name))
    cassandraUtil.insertKarmaPointsWithLookupKey(userId, config.COURSE, config.OPERATION_TYPE_ASSESSMENT_PASSED,
      courseId, points, addInfo, lookupKey)
    logger.info(s"ASSESSMENT_PASSED Karma Points awarded: userId=$userId, courseId=$courseId, courseName=${hierarchy.get.get(config.name)}, assessmentId=$assessmentId, points=$points, score=$score, isAcbp=$isAcbp")
    val newTotal = if (isAcbp) cassandraUtil.addToKarmaSummary(userId, points)
    else cassandraUtil.applyMonthlyCountSummaryUpdate(userId, points, config.ASSESSMENT_PASSED_MONTHLY_COUNT,
      config.ASSESSMENT_PASSED_FORMATTED_MONTH)
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }

  private def handleHighScore(userId: String, courseId: String, batchId: String, assessmentId: String, score: Double)
                              (implicit metrics: Metrics): Unit = {
    if (score < config.assessmentHighScoreThreshold) {
      logger.info(s"ASSESSMENT_HIGH_SCORE score=$score is below threshold=${config.assessmentHighScoreThreshold}: userId=$userId, courseId=$courseId, assessmentId=$assessmentId - not awarding")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val lookupKey = s"$userId${config.PIPE}${config.OPERATION_TYPE_ASSESSMENT_HIGH_SCORE}${config.PIPE}$assessmentId${config.PIPE}$courseId"
    if (cassandraUtil.doesEntryExistByKey(lookupKey, config.OPERATION_TYPE_ASSESSMENT_HIGH_SCORE)) {
      logger.info(s"ASSESSMENT_HIGH_SCORE karma points already awarded: userId=$userId, courseId=$courseId, assessmentId=$assessmentId - skipping duplicate")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val hierarchy = courseHierarchy(config.EVENT_TYPE_ASSESSMENT_HIGH_SCORE, userId, courseId)
    if (hierarchy.isEmpty) return

    val isAcbp = isAcbpCourse(userId, courseId)
    logger.info(s"ASSESSMENT_HIGH_SCORE ACBP check: userId=$userId, courseId=$courseId, assessmentId=$assessmentId, isAcbp=$isAcbp")
    if (!isAcbp && cassandraUtil.hasReachedMonthlyCountCutOff(userId, config.ASSESSMENT_HIGH_SCORE_MONTHLY_COUNT,
      config.ASSESSMENT_HIGH_SCORE_FORMATTED_MONTH, config.assessmentMonthlyQuota)) {
      logger.info(s"ASSESSMENT_HIGH_SCORE monthly limit reached for non-ACBP course: userId=$userId, courseId=$courseId, assessmentId=$assessmentId - not awarding")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val points = config.assessmentHighScoreQuotaKarmaPoints
    val addInfo = cassandraUtil.buildAddInfo(null, config.ADDINFO_ASSESSMENT_STATUS -> config.ASSESSMENT_STATUS_HIGH_SCORE,
      config.ADDINFO_SCORE -> score, config.ADDINFO_ASSESSMENT_ID -> assessmentId, config.ADDINFO_BATCH_ID -> batchId,
      config.ADDINFO_IS_ACBP -> isAcbp,
      config.ADDINFO_COURSENAME -> hierarchy.get.get(config.name))
    cassandraUtil.insertKarmaPointsWithLookupKey(userId, config.COURSE, config.OPERATION_TYPE_ASSESSMENT_HIGH_SCORE,
      courseId, points, addInfo, lookupKey)
    logger.info(s"ASSESSMENT_HIGH_SCORE Karma Points awarded: userId=$userId, courseId=$courseId, courseName=${hierarchy.get.get(config.name)}, assessmentId=$assessmentId, points=$points, score=$score, isAcbp=$isAcbp")
    val newTotal = if (isAcbp) cassandraUtil.addToKarmaSummary(userId, points)
    else cassandraUtil.applyMonthlyCountSummaryUpdate(userId, points, config.ASSESSMENT_HIGH_SCORE_MONTHLY_COUNT,
      config.ASSESSMENT_HIGH_SCORE_FORMATTED_MONTH)
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }

  private def isAcbpCourse(userId: String, courseId: String)(implicit metrics: Metrics): Boolean = {
    val headers = Map(
      config.HEADER_CONTENT_TYPE_KEY -> config.HEADER_CONTENT_TYPE_JSON,
      config.X_AUTHENTICATED_USER_ORGID -> cassandraUtil.fetchUserRootOrgId(userId),
      config.X_AUTHENTICATED_USER_ID -> userId
    )
    externalServiceClient.isCourseOnACBPPlan(courseId, headers)
  }

  private def courseHierarchy(eventType: String, userId: String, courseId: String)
                             (implicit metrics: Metrics): Option[java.util.HashMap[String, AnyRef]] = {
    val hierarchy = cassandraUtil.fetchContentHierarchy(courseId)
    if (hierarchy == null || hierarchy.isEmpty) {
      logger.info(s"No content hierarchy for courseId=$courseId - skipping $eventType, userId=$userId")
      metrics.incCounter(config.skippedEventCount)
      None
    } else Some(hierarchy)
  }
}
