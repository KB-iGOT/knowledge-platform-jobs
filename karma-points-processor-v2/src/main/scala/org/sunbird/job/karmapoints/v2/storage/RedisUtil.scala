package org.sunbird.job.karmapoints.v2.storage

import org.slf4j.LoggerFactory
import org.sunbird.job.cache.DataCache
import redis.clients.jedis.exceptions.{JedisConnectionException, JedisException}

/**
 * Thin wrapper over jobs-core's [[DataCache]] scoped to the one key karma-points cares about:
 * `user:karmaPoints:<userId>`, a write-through mirror of the Cassandra summary total (same as V1).
 * Redis is never read for business decisions here (V1 never did either), so failures on either
 * side are best-effort: logged and swallowed, never escalated to a job restart.
 */
class RedisUtil(dataCache: DataCache) {

  private[this] val logger = LoggerFactory.getLogger(classOf[RedisUtil])

  private def keyFor(userId: String): String = s"user:karmaPoints:$userId"

  /** Mirrors the user's new total to Redis. Best-effort - a Redis outage must not fail the event. */
  def setUserKarmaPoints(userId: String, totalPoints: Int): Unit = {
    try {
      dataCache.setWithRetry(keyFor(userId), totalPoints.toString)
    } catch {
      case ex@(_: JedisConnectionException | _: JedisException) =>
        logger.error(s"Failed to mirror karma points to Redis for userId=$userId (best-effort, not fatal)", ex)
      case ex: Exception =>
        logger.error(s"Unexpected error mirroring karma points to Redis for userId=$userId (best-effort, not fatal)", ex)
    }
  }

  /** Reads the cached total. Returns 0 on a cache miss or on any Redis failure (best-effort). */
  def getUserKarmaPoints(userId: String): Int = {
    try {
      val value = dataCache.getStringValue(keyFor(userId))
      if (value != null && value.nonEmpty) value.toInt else 0
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to read karma points from Redis for userId=$userId, defaulting to 0", ex)
        0
    }
  }

  def close(): Unit = dataCache.close()
}
