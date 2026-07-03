package org.sunbird.job.karmapoints.functions

import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.karmapoints.domain.Event
import org.sunbird.job.karmapoints.task.KarmaPointsProcessorConfig
import org.sunbird.job.karmapoints.util.Utility._
import org.sunbird.job.util.{CassandraUtil, HttpUtil}
import org.sunbird.job.{BaseProcessFunction, Metrics}
import org.sunbird.job.cache.{DataCache, RedisConnect}

class UnenrolmentProcessorFn(config: KarmaPointsProcessorConfig, httpUtil: HttpUtil)
                            (implicit val stringTypeInfo: TypeInformation[String],
                             @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessFunction[Event, String](config) {

  private val logger = LoggerFactory.getLogger("org.sunbird.job.karmapoints.functions.UnenrolmentProcessorFn")
  private var dataCache: DataCache = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
    val redisConnect = new RedisConnect(config, Option(config.metaRedisHost), Option(config.metaRedisPort))
    dataCache = new DataCache(config, redisConnect, config.cacheDbId, List())
    dataCache.init()
  }

  override def close(): Unit = {
    cassandraUtil.close()
    dataCache.close()
    super.close()
  }

  override def metricsList(): List[String] = {
    List(config.totalEventsCount, config.dbReadCount, config.dbUpdateCount, config.failedEventCount, config.skippedEventCount, config.successEventCount,
      config.cacheHitCount, config.karmaPointsIssueEventsCount, config.cacheMissCount)
  }

  override def processElement(event: Event,
                              context: ProcessFunction[Event, String]#Context,
                              metrics: Metrics): Unit = {
    val userId: String = event.readOrDefault[String](config.EVENT_USERID, config.EMPTY)
    val courseId: String = event.readOrDefault[String](config.EVENT_COURSEID, config.EMPTY)
    if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(courseId))
      return

    val hierarchy: java.util.Map[String, AnyRef] = fetchContentHierarchy(courseId)(metrics, config, cassandraUtil)
    if (hierarchy == null || hierarchy.size() < 1)
      return
    val contextType = hierarchy.get(config.PRIMARY_CATEGORY).asInstanceOf[String]

    logger.info(s"Reverting first-enrolment karma points on unenrolment for userId: $userId, courseId: $courseId")
    revertKarmaPoints(userId, contextType, config.OPERATION_TYPE_ENROLMENT, courseId, config.ADDINFO_UNENROLMENT)(metrics, config, cassandraUtil, dataCache)
  }
}
