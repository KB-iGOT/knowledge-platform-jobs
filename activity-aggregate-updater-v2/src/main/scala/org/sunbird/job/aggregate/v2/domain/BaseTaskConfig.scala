package org.sunbird.job.aggregate.v2.domain

import com.typesafe.config.Config
import org.apache.flink.util.OutputTag

abstract class BaseTaskConfig(config: Config, val jobName: String, val jobEnv: String = "dev") extends Serializable {

  val kafkaBrokerList: String = config.getString("kafka.broker-list")

  val userId: String = "userId"
  val courseId: String = "courseId"
  val batchId: String = "batchId"
  val contents: String = "contents"
  val eData: String = "edata"
  val action: String = "action"
  val language: String = "language"

  val batchEnrolmentUpdateCode: String = "batch-enrolment-update"

  val dedupEnabled: Boolean = config.getBoolean("dedup.enabled")
  val deDupStore: String = config.getString("dedup.store")
  val deDupExpirySec: Int = config.getInt("dedup.expiry-seconds")
  val deDupRedisHost: String = config.getString("redis.host")
  val deDupRedisPort: Int = config.getInt("redis.port")

  val totalEventCount: String = "total-event-count"
  val skipEventsCount: String = "skipped-event-count"
  val batchEnrolmentUpdateEventCount: String = "batch-enrolment-update-event-count"

  val kafkaInputTopic: String = config.getString("kafka.input.topic")
  val kafkaAuditEventTopic: String = config.getString("kafka.audit.topic")
  val kafkaFailedEventTopic: String = config.getString("kafka.failed.topic")
  val kafkaCertIssueTopic: String = config.getString("kafka.cert.topic")

  val kafkaConsumerParallelism: Int = config.getInt("task.consumer.parallelism")
  val deDupProcessParallelism: Int = config.getInt("task.dedup.parallelism")
  val activityAggregateUpdaterParallelism: Int = config.getInt("task.aggregate.parallelism")
  val enrolmentCompleteParallelism: Int = config.getInt("task.enrolment.parallelism")
  val thresholdBatchReadSize: Int = config.getInt("task.batch.read.threshold")
  val windowShards: Int = config.getInt("task.window.shards")

  // Function names
  val activityAggregateUpdaterConsumer: String = "ActivityAggregateConsumer"
  val consumptionDeDupFn: String = "ContentConsumptionDeDuplicationFunction"
  val activityAggregateUpdaterFn: String = "ActivityAggregateUpdaterFunction"
  val activityAggregateUpdaterProducer: String = "ActivityAggregateUpdaterProducer"
  val activityAggFailedEventProducer: String = "ActivityAggregateFailedEventProducer"
  val collectionCompleteFn: String = "CollectionProgressCompleteFunction"
  val collectionProgressUpdateFn: String = "CollectionProgressUpdateFunction"
  val enrolmentCompleteEventProducer: String = "EnrolmentCompleteEventProducer"
  val certIssueEventProducer: String = "CertificateIssueEventProducer"

  lazy val uniqueConsumptionOutput = new OutputTag[Map[String, AnyRef]]("unique-content-consumption")
  lazy val auditEventOutputTag = new OutputTag[String]("audit-event")
  lazy val failedEventOutputTag = new OutputTag[String]("failed-event")
  lazy val collectionUpdateOutputTag = new OutputTag[Map[String, AnyRef]]("collection-update")
  lazy val collectionCompleteOutputTag = new OutputTag[Map[String, AnyRef]]("collection-complete")
  lazy val certIssueOutputTag = new OutputTag[String]("cert-issue-event")
}