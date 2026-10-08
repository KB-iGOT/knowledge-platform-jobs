package org.sunbird.job.karmapoints.v2.handlers

import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.karmapoints.v2.config.KarmaPointsV2Config
import org.sunbird.job.karmapoints.v2.domain.UnifiedEvent
import org.sunbird.job.karmapoints.v2.exceptions.MissingPayloadException
import org.sunbird.job.karmapoints.v2.storage.{CassandraUtil, RedisUtil}

/**
 * Manual reconciliation event: adjusts ONLY user_karma_points_summary.total_points by `data.points`
 * (positive or negative) for `data.user_id` - no user_karma_points row or credit_lookup row is
 * written or changed, and no idempotency check is performed. Used to fix up a user's running total
 * after an out-of-band correction to an individual user_karma_points row (e.g. the EVENT_ATTENDED
 * karma-rate reconciliation script), without replaying/duplicating that row's own credit.
 */
class KarmaPointsAdjustmentHandler(config: KarmaPointsV2Config, cassandraUtil: CassandraUtil, redisUtil: RedisUtil) extends EventHandler {
  private[this] val logger = LoggerFactory.getLogger(classOf[KarmaPointsAdjustmentHandler])

  override protected def doHandle(event: UnifiedEvent)(implicit metrics: Metrics): Unit = {
    val userId = event.dataString("user_id")
    val points = event.dataLong("points").toInt
    if (points == 0) {
      throw MissingPayloadException(s"data.points is required (non-zero) for KARMA_POINTS_ADJUSTMENT event, userId=$userId")
    }
    logger.info(s"Processing KARMA_POINTS_ADJUSTMENT event: userId=$userId, points=$points")

    val newTotal = cassandraUtil.addToKarmaSummary(userId, points)
    logger.info(s"KARMA_POINTS_ADJUSTMENT applied: userId=$userId, points=$points, newTotal=$newTotal")
    redisUtil.setUserKarmaPoints(userId, newTotal)
  }
}
