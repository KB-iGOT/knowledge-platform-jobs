package org.sunbird.job.aggregate.v2.task

import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala._
import org.sunbird.job.aggregate.v2.domain.Event
import org.sunbird.job.aggregate.v2.functions.{ActivityAggregatesFunction, ContentConsumptionDeDupFunction}
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.util.HttpUtil
import scala.collection.JavaConverters._

import java.io.File

class ActivityAggregatorV2StreamTask(config: ActivityAggregateUpdaterConfig,
                                     kafkaConnector: FlinkKafkaConnector,
                                     httpUtil: HttpUtil) {

  def process(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    // Type info
    implicit val eventTypeInfo: TypeInformation[Event] = TypeInformation.of(classOf[Event])
    implicit val stringTypeInfo: TypeInformation[String] = TypeInformation.of(classOf[String])

    // Read raw input from Kafka and convert to Event
    val rawStream: DataStream[Event] = env
      .addSource(kafkaConnector.kafkaMapSource(config.kafkaInputTopic))
      .map(record => Event(record.asScala.toMap))
      .name(config.activityAggregateUpdaterConsumer)
      .uid(config.activityAggregateUpdaterConsumer)
      .setParallelism(config.kafkaConsumerParallelism)

    // Apply de-duplication
    val dedupedProcess = rawStream
      .process(new ContentConsumptionDeDupFunction(config))
      .name(config.consumptionDeDupFn)
      .uid(config.consumptionDeDupFn)
      .setParallelism(config.deDupProcessParallelism)

    val dedupedStream: DataStream[Event] =
      dedupedProcess.getSideOutput(config.uniqueConsumptionOutput).asInstanceOf[DataStream[Event]]

    // Keyed by userId_courseId_batchId_language
    val keyedStream = dedupedStream
      .keyBy(event => s"${event.userId}_${event.courseId}_${event.batchId}_${event.language}")

    // Aggregation process
    val processedStream = keyedStream
      .process(new ActivityAggregatesFunction(config, httpUtil))
      .name(config.activityAggregateUpdaterFn)
      .uid(config.activityAggregateUpdaterFn)
      .setParallelism(config.activityAggregateUpdaterParallelism)

    // Certificate Issue Events
    processedStream
      .getSideOutput(config.certIssueOutputTag)
      .addSink(kafkaConnector.kafkaStringSink(config.kafkaCertIssueTopic))
      .name(config.certIssueEventProducer)
      .uid(config.certIssueEventProducer)

    env.execute(config.jobName)
  }
}

object ActivityAggregateUpdaterStreamTaskV2 {
  def main(args: Array[String]): Unit = {
    val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
    val config = configFilePath.map(path =>
      ConfigFactory.parseFile(new File(path)).resolve()
    ).getOrElse(ConfigFactory.load("activity-aggregate-updater.conf").withFallback(ConfigFactory.systemEnvironment()))

    val taskConfig = new ActivityAggregateUpdaterConfig(config)
    val kafkaConnector = new FlinkKafkaConnector(taskConfig)
    val httpUtil = new HttpUtil

    val streamTask = new ActivityAggregatorV2StreamTask(taskConfig, kafkaConnector, httpUtil)
    streamTask.process()
  }
}
