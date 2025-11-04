package org.sunbird.job.dedup

import org.slf4j.LoggerFactory
import org.sunbird.job.BaseJobConfig
import org.sunbird.job.cache.{DataCache, RedisConnect}
import redis.clients.jedis.Jedis

class DeDupEngine(val config: BaseJobConfig, val redisConnect: RedisConnect, val store: Int, val expirySeconds: Int, val dedupHost: String = null, val dedupPort: Int = -1) {

  private[this] val logger = LoggerFactory.getLogger(classOf[DataCache])
  private var redisConnection: Jedis = _

  def init() {
    this.redisConnection = redisConnect.getConnection(store)
  }

  def close() {
    this.redisConnection.close()
  }

  import redis.clients.jedis.exceptions.JedisException

  def isUniqueEvent(checksum: String): Boolean = {
    try !redisConnection.exists(checksum)
    catch {
      case ex: JedisException =>
        ex.printStackTrace()
        this.redisConnection.close
        this.redisConnection = redisConnect.getConnection(store, 10000)
        !redisConnection.exists(checksum)
    }
  }

  def storeChecksum(checksum: String): Unit = {
    try {
      logger.info(s"Inside Dedup StoreCheckSum : $checksum")
      redisConnection.setex(checksum, expirySeconds, "")
    } catch {
      case ex: JedisException =>
        logger.error("Redis error in storeChecksum; attempting fallback", ex)
        // close primary if open
        if (this.redisConnection != null) this.redisConnection.close()

        // If a dedicated dedup host/port is provided, use a temporary Jedis to that host
        if (dedupHost != null && dedupPort > 0) {
          var tmp: Jedis = null
          try {
            tmp = new Jedis(dedupHost, dedupPort)
            tmp.select(store)
            tmp.setex(checksum, expirySeconds, "")
          } catch {
            case inner: Exception =>
              logger.error("Failed to write checksum to dedup host/port", inner)
              // fallback to reconnecting via RedisConnect
              this.redisConnection = redisConnect.getConnection(store, 10000)
              this.redisConnection.select(store)
              this.redisConnection.setex(checksum, expirySeconds, "")
          } finally {
            if (tmp != null) tmp.close()
          }
        } else {
          // No dedup host configured — reconnect using RedisConnect and retry
          this.redisConnection = redisConnect.getConnection(store, 10000)
          this.redisConnection.select(store)
          redisConnection.setex(checksum, expirySeconds, "")
        }
    }
  }

}
