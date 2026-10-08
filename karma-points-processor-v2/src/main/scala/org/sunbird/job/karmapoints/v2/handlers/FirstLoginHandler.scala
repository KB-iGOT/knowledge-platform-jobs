package org.sunbird.job.karmapoints.v2.handlers

import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.karmapoints.v2.config.KarmaPointsV2Config
import org.sunbird.job.karmapoints.v2.domain.UnifiedEvent
import org.sunbird.job.karmapoints.v2.storage.{CassandraUtil, RedisUtil}

/**
 * Ports V1 `FirstLoginProcessorFn`: awards `firstLoginQuotaKarmaPoints` (5) once ever, only when
 * `edata.selfRegistration=true` and no prior FIRST_LOGIN credit-lookup entry exists for the user
 * (identical dedup key as V1: contextType=contextId=operationType=FIRST_LOGIN=userId row).
 *
 * Also handles FIRST_LOGIN_MOBILE (same handler, independently dedup'd - see [[handleFirstLoginMobile]]):
 * a separate once-ever-per-user award, keyed by `userId|FIRST_LOGIN_MOBILE|userId`, so a user's
 * FIRST_LOGIN credit never blocks FIRST_LOGIN_MOBILE and vice versa.
 */
class FirstLoginHandler(config: KarmaPointsV2Config, cassandraUtil: CassandraUtil, redisUtil: RedisUtil) extends EventHandler {
  private[this] val logger = LoggerFactory.getLogger(classOf[FirstLoginHandler])

  override protected def doHandle(event: UnifiedEvent)(implicit metrics: Metrics): Unit = event.eventType match {
    case config.EVENT_TYPE_FIRST_LOGIN => handleFirstLogin(event)
    case config.EVENT_TYPE_FIRST_LOGIN_MOBILE => handleFirstLoginMobile(event)
  }

  private def handleFirstLogin(event: UnifiedEvent)(implicit metrics: Metrics): Unit = {
    val userId = event.dataEdataString("id")
   /* val selfRegistration = event.dataEdataBoolean("self_registration")
    logger.info(
      s"Processing FIRST_LOGIN event: userId=$userId, selfRegistration=$selfRegistration"
    )
    if (!selfRegistration) {
      metrics.incCounter(config.skippedEventCount)
      return
    }*/
    if (cassandraUtil.doesEntryExist(userId, config.OPERATION_TYPE_FIRST_LOGIN, config.OPERATION_TYPE_FIRST_LOGIN, userId)) {
      logger.info(s"FIRST_LOGIN karma points already awarded for userId=$userId - skipping duplicate")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val points = config.firstLoginQuotaKarmaPoints
    cassandraUtil.insertKarmaPoints(userId, config.OPERATION_TYPE_FIRST_LOGIN, config.OPERATION_TYPE_FIRST_LOGIN, userId, points, config.EMPTY)
    // TODO: Remove temporary INFO log after testing.
    logger.info(
      s"FIRST_LOGIN points awarded: userId=$userId, points=$points"
    )
    val newTotal = cassandraUtil.addToKarmaSummary(userId, points)
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }

  private def handleFirstLoginMobile(event: UnifiedEvent)(implicit metrics: Metrics): Unit = {
    val userId = event.dataEdataString("id")
    // TODO: Remove temporary INFO log after testing.
    logger.info(s"Processing FIRST_LOGIN_MOBILE event: userId=$userId")

    if (cassandraUtil.doesEntryExist(userId, config.OPERATION_TYPE_FIRST_LOGIN_MOBILE, config.OPERATION_TYPE_FIRST_LOGIN_MOBILE, userId)) {
      logger.info(s"FIRST_LOGIN_MOBILE karma points already awarded for userId=$userId - skipping duplicate")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    // deviceType/first_login are optional - only included in addinfo when present in the event.
    val deviceType = event.dataEdataString(config.ADDINFO_DEVICE_TYPE)
    val firstLoginStr = event.dataEdataString(config.ADDINFO_FIRST_LOGIN)
    var addInfoUpdates = Seq.empty[(String, Any)]
    if (StringUtils.isNotBlank(deviceType)) addInfoUpdates :+= config.ADDINFO_DEVICE_TYPE -> deviceType
    if (StringUtils.isNotBlank(firstLoginStr)) addInfoUpdates :+= config.ADDINFO_FIRST_LOGIN -> firstLoginStr.toLong
    val addInfo = cassandraUtil.buildAddInfo(null, addInfoUpdates: _*)

    val points = config.firstLoginMobileQuotaKarmaPoints
    cassandraUtil.insertKarmaPoints(userId, config.OPERATION_TYPE_FIRST_LOGIN_MOBILE, config.OPERATION_TYPE_FIRST_LOGIN_MOBILE, userId, points, addInfo)
    // TODO: Remove temporary INFO log after testing.
    logger.info(s"FIRST_LOGIN_MOBILE points awarded: userId=$userId, points=$points")
    val newTotal = cassandraUtil.addToKarmaSummary(userId, points)
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }
}
