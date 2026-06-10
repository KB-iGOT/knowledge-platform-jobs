package org.sunbird.job.programaggregate.v2.task

import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.scala._
import org.sunbird.job.programaggregate.v2.domain.CollectionProgress
import org.sunbird.job.programaggregate.v2.functions.{
  ProgramActivityAggregatesEnrolUpdateFunctionV2,
  ProgramContentConsumptionDeDupFunctionV2,
  ProgramProgressCompleteFunctionV2,
  ProgramProgressUpdateFunctionV2
}
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.util.{FlinkUtil, HttpUtil}

import java.io.File
import java.util

/**
 * V2 StreamTask for program-activity-aggregate-updater.
 *
 * V2 changes vs V1:
 *  1. keyBy(userId_courseId_batchId) replaces countWindow(thresholdBatchReadSize).
 *     No GlobalWindow, no WindowBaseProcessFunction — events flow to
 *     EnrolUpdateFunctionV2 immediately after keying.
 *  2. ProgramActivityAggregatorKeySelectorV2 removed — inline lambda used.
 *  3. V1 BUG FIXED: audit sink from enrolmentCompleteStream was commented out.
 *     V2 wires it properly.
 *  4. All V2 function classes used throughout.
 */
class ProgramActivityAggregateUpdaterStreamTaskV2(
  config: ProgramActivityAggregateUpdaterConfigV2,
  kafkaConnector: FlinkKafkaConnector,
  httpUtil: HttpUtil
) {

  def process(): Unit = {
    implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
    env.getCheckpointConfig.setCheckpointingMode(CheckpointingMode.AT_LEAST_ONCE)

    implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] =
      TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])
    implicit val stringTypeInfo: TypeInformation[String] =
      TypeExtractor.getForClass(classOf[String])
    implicit val enrolmentCompleteTypeInfo: TypeInformation[List[CollectionProgress]] =
      TypeExtractor.getForClass(classOf[List[CollectionProgress]])

    val deDupStream: DataStream[String] = env
      .addSource(kafkaConnector.kafkaMapSource(config.kafkaInputTopic))
      .name(config.programActivityAggregateUpdaterConsumer)
      .uid(config.programActivityAggregateUpdaterConsumer)
      .setParallelism(config.kafkaConsumerParallelism)
      .rebalance
      .process(new ProgramContentConsumptionDeDupFunctionV2(config, httpUtil))
      .name(config.consumptionDeDupFn)
      .uid(config.consumptionDeDupFn)
      .setParallelism(config.deDupProcessParallelism)

    val uniqueConsumptionStream: DataStream[Map[String, AnyRef]] =
      deDupStream.getSideOutput(config.uniqueConsumptionOutput)

    val progressStream: DataStream[String] = uniqueConsumptionStream
      .keyBy(new ProgramActivityAggregatorKeySelectorV2(config))
      .process(new ProgramActivityAggregatesEnrolUpdateFunctionV2(config, httpUtil))
      .name(config.programactivityAggregateUpdaterFn)
      .uid(config.programactivityAggregateUpdaterFn)
      .setParallelism(config.activityAggregateUpdaterParallelism)

    val auditStream: DataStream[String] =
      progressStream.getSideOutput(config.auditEventOutputTag)
    auditStream
      .addSink(kafkaConnector.kafkaStringSink(config.kafkaAuditEventTopic))
      .name(config.programactivityAggregateUpdaterProducer)
      .uid(config.programactivityAggregateUpdaterProducer)

    val failedStream: DataStream[String] =
      progressStream.getSideOutput(config.failedEventOutputTag)
    failedStream
      .addSink(kafkaConnector.kafkaStringSink(config.kafkaFailedEventTopic))
      .name(config.programactivityAggFailedEventProducer)
      .uid(config.programactivityAggFailedEventProducer)

    val collectionUpdateStream: DataStream[List[CollectionProgress]] =
      progressStream.getSideOutput(config.collectionUpdateOutputTag)
    collectionUpdateStream
      .process(new ProgramProgressUpdateFunctionV2(config))
      .name(config.collectionProgressUpdateFn)
      .uid(config.collectionProgressUpdateFn)
      .setParallelism(config.enrolmentCompleteParallelism)

    val collectionCompleteStream: DataStream[List[CollectionProgress]] =
      progressStream.getSideOutput(config.collectionCompleteOutputTag)
    val enrolmentCompleteStream: DataStream[String] = collectionCompleteStream
      .process(new ProgramProgressCompleteFunctionV2(config))
      .name(config.collectionCompleteFn)
      .uid(config.collectionCompleteFn)
      .setParallelism(config.enrolmentCompleteParallelism)

    val certIssueStream: DataStream[String] =
      enrolmentCompleteStream.getSideOutput(config.certIssueOutputTag)
    certIssueStream
      .addSink(kafkaConnector.kafkaStringSink(config.kafkaCertIssueTopic))
      .name(config.certIssueEventProducer)
      .uid(config.certIssueEventProducer)

    val completionAuditStream: DataStream[String] =
      enrolmentCompleteStream.getSideOutput(config.auditEventOutputTag)
    completionAuditStream
      .addSink(kafkaConnector.kafkaStringSink(config.kafkaAuditEventTopic))
      .name(config.enrolmentCompleteEventProducer)
      .uid(config.enrolmentCompleteEventProducer)

    env.execute(config.jobName)
  }
}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster
object ProgramActivityAggregateUpdaterStreamTaskV2 {

  def main(args: Array[String]): Unit = {
    val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
    val config = configFilePath.map {
      path => ConfigFactory.parseFile(new File(path)).resolve()
    }.getOrElse(
      ConfigFactory
        .load("program-activity-aggregate-updater-v2.conf")
        .withFallback(ConfigFactory.systemEnvironment())
    )
    val courseAggregator = new ProgramActivityAggregateUpdaterConfigV2(config)
    val kafkaUtil        = new FlinkKafkaConnector(courseAggregator)
    val httpUtil         = new HttpUtil
    val task = new ProgramActivityAggregateUpdaterStreamTaskV2(courseAggregator, kafkaUtil, httpUtil)
    task.process()
  }
}
// $COVERAGE-ON$

/**
 * V2 KeySelector — keys by "userId_courseId_batchId" String.
 *
 * V2 change vs V1:
 *   V1 returned Int (userId.hashCode % windowShards) for use with countWindow.
 *   V2 returns String so Flink distributes events by exact (user, course, batch)
 *   combination — no window needed, EnrolUpdateFunctionV2 uses timer state instead.
 */
class ProgramActivityAggregatorKeySelectorV2(config: ProgramActivityAggregateUpdaterConfigV2)
  extends KeySelector[Map[String, AnyRef], String] {

  private val serialVersionUID = 7267989625042068737L

  override def getKey(event: Map[String, AnyRef]): String = {
    val userId   = event.getOrElse(config.userId,   "").toString
    val courseId = event.getOrElse(config.courseId, "").toString
    val batchId  = event.getOrElse(config.batchId,  "").toString
    s"${userId}_${courseId}_${batchId}"
  }
}

