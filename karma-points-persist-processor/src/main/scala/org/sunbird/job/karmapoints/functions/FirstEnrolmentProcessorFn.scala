package org.sunbird.job.karmapoints.functions
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

import java.util.Date

class FirstEnrolmentProcessorFn(config: KarmaPointsProcessorConfig, httpUtil: HttpUtil)
                               (implicit val stringTypeInfo: TypeInformation[String],
                                @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessFunction[Event, String](config)   {
  private val logger = LoggerFactory.getLogger("org.sunbird.job.karmapoints.functions.FirstEnrolmentProcessorFn")
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
    val eData = event.getMap().get(config.EDATA).asInstanceOf[scala.collection.immutable.Map[String, Any]]
    val usrId: String = eData.get(config.USER_ID_CAMEL) match {
      case Some(value) => value.asInstanceOf[String]
      case None => config.EMPTY
    }
    val contextId: String = eData.get(config.COURSE_ID) match {
      case Some(value) => value.asInstanceOf[String]
      case None => config.EMPTY
    }
    val hierarchy: java.util.Map[String, AnyRef] = fetchContentHierarchy(contextId)(metrics, config, cassandraUtil)
    if(null == hierarchy || hierarchy.size() < 1)
      return
    val contextType = hierarchy.get(config.PRIMARY_CATEGORY).asInstanceOf[String]
    logger.info(String.format("Enrolment check - User ID:+"+ usrId+",Context Type:"+contextType+", Context ID:" +contextId))
    if (!config.COURSE.equalsIgnoreCase(contextType))
      return
    kpOnFirstEnrollment(usrId, contextType, config.OPERATION_TYPE_ENROLMENT, contextId, hierarchy, cassandraUtil)(metrics)
  }

  private def kpOnFirstEnrollment(userId: String, contextType: String,
                                  operationType: String, contextId: String, hierarchy: java.util.Map[String, AnyRef],
                                  cassandraUtil: CassandraUtil)(implicit metrics: Metrics): Unit = {
    val points: Int = config.firstEnrolmentQuotaKarmaPoints
    val lookup = fetchUserKarmaPointsCreditLookup(userId, contextType, operationType, contextId)(config, cassandraUtil)
    if (lookup == null || lookup.isEmpty) {
      // No entry for this course. Only a user who has never actively earned first-enrolment
      // points (i.e. no course with points > 0) is eligible - this covers both a genuinely
      // new user, and a user whose only prior first-enrolment entry was reverted to 0 on unenrolment.
      if (hasEarnedFirstEnrolmentPoints(userId)(config, cassandraUtil))
        return
      val addInfo = buildAddInfo(null, config.ADDINFO_COURSENAME -> hierarchy.get(config.name))
      insertKarmaPoints(userId, contextType, operationType, contextId, points, addInfo)(metrics, config, cassandraUtil)
    } else {
      val creditDate = lookup.get(0).getObject(config.DB_COLUMN_CREDIT_DATE).asInstanceOf[Date]
      val entry = fetchUserKarmaPoints(creditDate, userId, contextType, operationType, contextId)(config, cassandraUtil)
      // Points > 0 means this exact course is already actively credited (duplicate/redelivered event).
      // Points == 0 means the course was previously unenrolled - re-enrolling re-awards on the same
      // row (same credit_date), tagged as a re-enrolment - but only if the user doesn't already hold
      // an active first-enrolment credit from another course.
      if (entry == null || entry.isEmpty || entry.get(0).getInt(config.POINTS) > 0)
        return
      if (hasEarnedFirstEnrolmentPoints(userId)(config, cassandraUtil))
        return
      val addInfo = buildAddInfo(entry.get(0).getString(config.ADD_INFO),
        config.ADDINFO_COURSENAME -> hierarchy.get(config.name), config.ADDINFO_REENROLMENT -> java.lang.Boolean.TRUE)
      updatePoints(userId, contextType, operationType, contextId, points, addInfo, creditDate.getTime)(config, cassandraUtil)
    }
    updateKarmaSummary(userId, points)( config, cassandraUtil, dataCache)
  }
}