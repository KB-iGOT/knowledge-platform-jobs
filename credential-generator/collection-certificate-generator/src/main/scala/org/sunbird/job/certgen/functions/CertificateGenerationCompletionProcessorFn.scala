package org.sunbird.job.certgen.functions

import com.datastax.driver.core.querybuilder.QueryBuilder
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.certgen.domain.Event
import org.sunbird.job.certgen.task.CertificateGeneratorConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil}
import org.sunbird.job.{BaseProcessFunction, Metrics}
import scala.collection.JavaConverters._

class CertificateGenerationCompletionProcessorFn(config: CertificateGeneratorConfig, httpUtil: HttpUtil, @transient var cassandraUtil: CassandraUtil = null)
                                                (implicit val eventTypeInfo: TypeInformation[Event]) extends BaseProcessFunction[Event, String](config) {

  private[this] val logger = LoggerFactory.getLogger(classOf[CertificateGenerationCompletionProcessorFn])
  private var dataCache: DataCache = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    val redisConnect = new RedisConnect(config)
    dataCache = new DataCache(config, redisConnect, 0, List())
    dataCache.init()
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
    logger.info("CertificateGenerationCompletionProcessorFn: Redis connection initialized")
  }

  override def close(): Unit = {
    if (dataCache != null) {
      dataCache.close()
    }
    super.close()
    cassandraUtil.close()
  }

  override def metricsList(): List[String] = {
    List(
      config.totalEventsCount,
      config.successEventCount,
      config.failedEventCount,
      config.skippedEventCount
    )
  }

  override def processElement(event: Event,
                              context: ProcessFunction[Event, String]#Context,
                              metrics: Metrics): Unit = {
    metrics.incCounter(config.totalEventsCount)
    try {
      val eData = event.getMap().get(config.eData).asInstanceOf[scala.collection.immutable.Map[String, Any]]
      val usrId: String = eData.get(config.userId) match {
        case Some(value) => value.asInstanceOf[String]
        case None => ""
      }
      val related = event.related
      val primaryFields = Map(config.userId.toLowerCase() -> usrId,
        config.batchId.toLowerCase -> related.getOrElse(config.batchId, "").asInstanceOf[String],
        config.courseId.toLowerCase -> related.getOrElse(config.courseId, "").asInstanceOf[String])
      val records = getIssuedCertificatesFromUserEnrollmentTable(primaryFields)(metrics)
      records.foreach(row => {
        val certificates = row.getObject("issued_certificates")
          .asInstanceOf[java.util.List[java.util.Map[String, String]]]
        val status = row.getInt("status")
        val progress = row.getInt("progress")
        if (certificates != null && !certificates.isEmpty && status == 2 && progress == 2) {
          val redisKey = s"user:certCount:$usrId"
          val redisValue = 1.toString
          val redisUserCertificateCount = dataCache.getStringValue(redisKey)
          if (redisUserCertificateCount.nonEmpty) {
            val updatedRedisValue = redisUserCertificateCount.toInt + 1
            dataCache.setWithRetry(redisKey, updatedRedisValue.toString)
          } else {
            dataCache.setWithRetry(redisKey, redisValue)
          }

        }
      })
      logger.info(s"Retrieved issued certificates: $records")
    } catch {
      case e: Exception =>
        metrics.incCounter(config.failedEventCount)
        logger.error(s"Error persisting certificate generation data to Redis: ${e.getMessage}", e)
    }
  }

  private def getIssuedCertificatesFromUserEnrollmentTable(columns: Map[String, AnyRef])(implicit metrics: Metrics) = {
    logger.info("primary columns {}", columns)
    val selectWhere = QueryBuilder.select("issued_certificates", "status", "progress")
      .from(config.dbKeyspace, config.dbEnrollmentTable)
      .where()
    columns.map(col => {
      col._2 match {
        case value: List[Any] =>
          selectWhere.and(QueryBuilder.in(col._1, value.asJava))
        case _ =>
          selectWhere.and(QueryBuilder.eq(col._1, col._2))
      }
    })
    logger.info("select query {}", selectWhere.toString)
    cassandraUtil.find(selectWhere.toString).asScala.toList
  }
}