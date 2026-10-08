package org.sunbird.job.karmapoints.v2.handlers

import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.karmapoints.v2.config.KarmaPointsV2Config
import org.sunbird.job.karmapoints.v2.domain.UnifiedEvent
import org.sunbird.job.karmapoints.v2.exceptions.{InvalidUserIdException, UnknownEventTypeException}
import org.sunbird.job.karmapoints.v2.storage.{CassandraUtil, RedisUtil}

/**
 * Handles all three registration event types - SELF_REGISTRATION, CUSTOM_REGISTRATION,
 * BULK_REGISTRATION:
 * {
 * "eventType": "SELF_REGISTRATION" | "CUSTOM_REGISTRATION" | "BULK_REGISTRATION",
 * "data": { "edata": { "userId": "user123" } },
 * "version": 1
 * }
 * Kafka key: userId
 *
 * One-time-per-user karma-points award (config.selfRegistrationQuotaKarmaPoints, shared by all
 * three - no separate quota per type). Each type's operation_type equals its own eventType
 * literal, so the three are independently dedup'd: dedup identity is the plain-key/operation_type
 * pair `(userId, operationType)` in `user_karma_points_credit_lookup`, read via
 * `CassandraUtil.doesEntryExistByKey` and written via `CassandraUtil.insertKarmaPointsWithLookupKey`
 * (lookupKey=userId) - both already-generic methods, reused unmodified.
 */
class RegistrationHandler(config: KarmaPointsV2Config, cassandraUtil: CassandraUtil, redisUtil: RedisUtil) extends EventHandler {
  private[this] val logger = LoggerFactory.getLogger(classOf[RegistrationHandler])

  override protected def doHandle(event: UnifiedEvent)(implicit metrics: Metrics): Unit = {
    val operationType = event.eventType match {
      case config.EVENT_TYPE_SELF_REGISTRATION => config.OPERATION_TYPE_SELF_REGISTRATION
      case config.EVENT_TYPE_CUSTOM_REGISTRATION => config.OPERATION_TYPE_CUSTOM_REGISTRATION
      case config.EVENT_TYPE_BULK_REGISTRATION => config.OPERATION_TYPE_BULK_REGISTRATION
      case other => throw UnknownEventTypeException(s"Unknown eventType: '$other' for RegistrationHandler")
    }

    val userId = event.dataEdataString("userId")
    // TODO: Remove temporary INFO log after testing.
    logger.info(s"Processing ${event.eventType} event: userId=$userId")

    if (StringUtils.isBlank(userId)) {
      throw InvalidUserIdException(s"${event.eventType} event is missing userId (data.edata.userId)")
    }

    if (cassandraUtil.doesEntryExistByKey(userId, operationType)) {
      logger.info(s"${event.eventType} karma points already awarded for userId=$userId - skipping duplicate")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val points = config.selfRegistrationQuotaKarmaPoints
    val addInfo = cassandraUtil.buildAddInfo(null, config.ADDINFO_REGISTRATION_TYPE -> event.eventType)
    cassandraUtil.insertKarmaPointsWithLookupKey(userId, operationType, operationType, userId, points, addInfo, userId)
    // TODO: Remove temporary INFO log after testing.
    logger.info(s"${event.eventType} points awarded: userId=$userId, points=$points")
    val newTotal = cassandraUtil.addToKarmaSummary(userId, points)
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }
}
