package org.sunbird.job.karmapoints.v2.handlers

import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.karmapoints.v2.config.KarmaPointsV2Config
import org.sunbird.job.karmapoints.v2.domain.UnifiedEvent
import org.sunbird.job.karmapoints.v2.exceptions.MissingPayloadException
import org.sunbird.job.karmapoints.v2.storage.{CassandraUtil, RedisUtil}

/**
 * Handles SURVEY_SUBMISSION events:
 * {
 * "eventType": "SURVEY_SUBMISSION",
 * "data": { "edata": { "userId": "user123", "courseId": "course123", "surveyId": "form123" } },
 * "version": 1
 * }
 * Kafka key: userId
 *
 * Once-per-user-per-course-per-survey karma-points award (config.surveySubmissionQuotaKarmaPoints).
 * Dedup identity is `userId|courseId|surveyId` / `operation_type=SURVEY_SUBMISSION` in
 * `user_karma_points_credit_lookup` - an independent lookup key, NOT the `userId|contextType|contextId`
 * composite `CassandraUtil.insertKarmaPoints` builds - so this reuses
 * `CassandraUtil.doesEntryExistByKey(lookupKey, operationType)` and
 * `CassandraUtil.insertKarmaPointsWithLookupKey(userId, contextType, operationType, contextId,
 * points, addInfo, lookupKey)` instead, with ledger `context_type=config.COURSE`/`context_id=courseId`
 * and `addinfo={"surveyId":...}` via the existing `buildAddInfo`.
 *
 * A user can receive at most `config.surveySubmissionMonthlyQuota` survey awards per calendar month,
 * tracked via `CassandraUtil.hasReachedMonthlyCountCutOff` / `applyMonthlyCountSummaryUpdate`. Duplicates
 * and over-limit submissions do not consume a slot.
 */
class SurveySubmissionHandler(config: KarmaPointsV2Config, cassandraUtil: CassandraUtil, redisUtil: RedisUtil) extends EventHandler {
  private[this] val logger = LoggerFactory.getLogger(classOf[SurveySubmissionHandler])

  override protected def doHandle(event: UnifiedEvent)(implicit metrics: Metrics): Unit = {
    val userId = event.dataEdataString("userId")
    val courseId = event.dataEdataString("courseId")
    val surveyId = event.dataEdataString("surveyId")
    // TODO: Remove temporary INFO log after testing.
    logger.info(s"Processing SURVEY_SUBMISSION event: userId=$userId, courseId=$courseId, surveyId=$surveyId")

    if (StringUtils.isBlank(userId)) {
      throw MissingPayloadException("SURVEY_SUBMISSION event is missing userId (data.edata.userId)")
    }
    if (StringUtils.isBlank(courseId)) {
      throw MissingPayloadException(s"SURVEY_SUBMISSION event is missing courseId (data.edata.courseId) for userId=$userId")
    }
    if (StringUtils.isBlank(surveyId)) {
      throw MissingPayloadException(s"SURVEY_SUBMISSION event is missing surveyId (data.edata.surveyId) for userId=$userId")
    }

    val lookupKey = s"$userId${config.PIPE}$courseId${config.PIPE}$surveyId"
    if (cassandraUtil.doesEntryExistByKey(lookupKey, config.OPERATION_TYPE_SURVEY_SUBMISSION)) {
      logger.info(s"SURVEY_SUBMISSION karma points already awarded for userId=$userId, courseId=$courseId, surveyId=$surveyId - skipping duplicate")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val hierarchy = cassandraUtil.fetchContentHierarchy(courseId)
    if (hierarchy == null || hierarchy.isEmpty) {
      logger.info(s"No content hierarchy for courseId=$courseId - skipping SURVEY_SUBMISSION, userId=$userId")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    if (cassandraUtil.hasReachedMonthlyCountCutOff(userId, config.SURVEY_SUBMISSION_MONTHLY_COUNT,
      config.SURVEY_SUBMISSION_FORMATTED_MONTH, config.surveySubmissionMonthlyQuota)) {
      logger.info(s"SURVEY_SUBMISSION monthly limit reached: userId=$userId, courseId=$courseId, surveyId=$surveyId - not awarding")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val points = config.surveySubmissionQuotaKarmaPoints
    val addInfo = cassandraUtil.buildAddInfo(null, config.ADDINFO_SURVEY_ID -> surveyId,
      config.ADDINFO_COURSENAME -> hierarchy.get(config.name))
    cassandraUtil.insertKarmaPointsWithLookupKey(userId, config.COURSE, config.OPERATION_TYPE_SURVEY_SUBMISSION,
      courseId, points, addInfo, lookupKey)
    // TODO: Remove temporary INFO log after testing.
    logger.info(s"SURVEY_SUBMISSION points awarded: userId=$userId, courseId=$courseId, courseName=${hierarchy.get(config.name)}, surveyId=$surveyId, points=$points")
    val newTotal = cassandraUtil.applyMonthlyCountSummaryUpdate(userId, points, config.SURVEY_SUBMISSION_MONTHLY_COUNT,
      config.SURVEY_SUBMISSION_FORMATTED_MONTH)
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }
}
