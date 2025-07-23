package org.sunbird.job.aggregate.v2.functions

import java.lang.reflect.Type
import java.security.MessageDigest
import java.util
import com.google.gson.reflect.TypeToken
import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.aggregate.v2.common.DeDupHelper
import org.sunbird.job.aggregate.v2.domain.Event
import org.sunbird.job.aggregate.v2.task.ActivityAggregateUpdaterConfig
import org.sunbird.job.cache.RedisConnect
import org.sunbird.job.dedup.DeDupEngine
import org.sunbird.job.{BaseProcessFunction, Metrics}

import scala.collection.JavaConverters._

class ContentConsumptionDeDupFunction(config: ActivityAggregateUpdaterConfig)
                                     (implicit val stringTypeInfo: TypeInformation[String])
  extends BaseProcessFunction[Event, String](config) {

  val mapType: Type = new TypeToken[Map[String, AnyRef]]() {}.getType
  private[this] val logger = LoggerFactory.getLogger(classOf[ContentConsumptionDeDupFunction])
  var deDupEngine: DeDupEngine = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    deDupEngine = new DeDupEngine(config, new RedisConnect(config, Option(config.deDupRedisHost), Option(config.deDupRedisPort)), config.deDupStore, config.deDupExpirySec)
    deDupEngine.init()
  }

  override def close(): Unit = {
    deDupEngine.close()
    super.close()
  }

  override def metricsList(): List[String] = {
    List(config.totalEventCount, config.skipEventsCount)
  }

  override def processElement(event: Event, context: ProcessFunction[Event, String]#Context, metrics: Metrics): Unit = {
    metrics.incCounter(config.totalEventCount)

    val userId = event.userId
    val courseId = event.courseId
    val batchId = event.batchId
    val contentId = event.contents.headOption.map(_.contentId).getOrElse("")
    val status = event.contents.headOption.map(_.status).getOrElse(0)
    val language = event.language

    val checksum = DeDupHelper.getMessageId(courseId, batchId, userId, contentId, status, language)
    println("checksum: " + checksum)

    if (deDupEngine.isUniqueEvent(checksum)) {
      //context.output(config.uniqueConsumptionOutput, event)
      context.output(config.uniqueConsumptionOutput.asInstanceOf[org.apache.flink.util.OutputTag[Object]], event.asInstanceOf[Object])
    } else {
      metrics.incCounter(config.skipEventsCount)
    }
  }


}
