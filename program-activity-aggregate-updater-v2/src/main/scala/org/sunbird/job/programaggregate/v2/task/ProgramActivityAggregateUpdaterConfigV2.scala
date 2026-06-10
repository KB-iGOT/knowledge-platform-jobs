package org.sunbird.job.programaggregate.v2.task

import com.typesafe.config.Config
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.streaming.api.scala.OutputTag
import org.sunbird.job.BaseJobConfig
import org.sunbird.job.programaggregate.v2.domain.CollectionProgress

import java.util

/**
 * V2 Config for program-activity-aggregate-updater-v2.
 *
 * Design:
 *  - Extends BaseJobConfig directly (same as activity-aggregate-updater-v2 reference).
 *  - Extending V1 Config is NOT done because:
 *      (a) V1 hardcodes job name "program-activity-aggregate-updater" in its constructor.
 *      (b) V1 OutputTags are typed to v1.domain.CollectionProgress — incompatible with
 *          v2.domain.CollectionProgress used by V2 functions.
 *  - All V1 keys are copied verbatim, then new V2 keys are appended.
 */
class ProgramActivityAggregateUpdaterConfigV2(override val config: Config)
    extends BaseJobConfig(config, "program-activity-aggregate-updater-v2") {

  private val serialVersionUID = 2905979434303791381L

  implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] =
    TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])
  implicit val scalaMapTypeInfo: TypeInformation[Map[String, AnyRef]] =
    TypeExtractor.getForClass(classOf[Map[String, AnyRef]])
  implicit val stringTypeInfo: TypeInformation[String] =
    TypeExtractor.getForClass(classOf[String])
  implicit val enrolmentCompleteTypeInfo: TypeInformation[List[CollectionProgress]] =
    TypeExtractor.getForClass(classOf[List[CollectionProgress]])

  // ── Kafka ──────────────────────────────────────────────────────────────────
  val kafkaInputTopic: String        = config.getString("kafka.input.topic")
  val kafkaAuditEventTopic: String   = config.getString("kafka.output.audit.topic")
  val kafkaFailedEventTopic: String  = config.getString("kafka.output.failed.topic")
  val kafkaCertIssueTopic: String    = config.getString("kafka.output.certissue.topic")

  // ── Parallelism ────────────────────────────────────────────────────────────
  override val kafkaConsumerParallelism: Int       = config.getInt("task.consumer.parallelism")
  val activityAggregateUpdaterParallelism: Int     = config.getInt("task.activity.agg.parallelism")
  val deDupProcessParallelism: Int                 = config.getInt("task.dedup.parallelism")
  val enrolmentCompleteParallelism: Int            = config.getInt("task.enrolment.complete.parallelism")
  val windowShards: Int                            = config.getInt("task.window.shards")

  // ── Metrics ────────────────────────────────────────────────────────────────
  val totalEventCount              = "total-events-count"
  val failedEventCount             = "failed-events-count"
  val dbUpdateCount                = "db-update-count"
  val dbReadCount                  = "db-read-count"
  val cacheHitCount                = "cache-hit-count"
  val cacheMissCount               = "cache-miss-count"
  val batchEnrolmentUpdateEventCount = "batch-enrolment-update-count"
  val skipEventsCount              = "skipped-events-count"
  val processedEnrolmentCount      = "processed-enrolment-count"
  val enrolmentCompleteCount       = "enrolment-complete-count"
  val certIssueEventsCount         = "cert-issue-events-count"
  val retiredCCEventsCount         = "retired-consumption-events-count"
  val skippedEventCount            = "skipped-event-count"

  // ── Cassandra ──────────────────────────────────────────────────────────────
  val dbUserContentConsumptionTable: String = config.getString("lms-cassandra.consumption.table")
  val dbUserActivityAggTable: String        = config.getString("lms-cassandra.user_activity_agg.table")
  val dbUserEnrolmentsTable: String         = config.getString("lms-cassandra.user_enrolments.table")
  val dbKeyspace: String                    = config.getString("lms-cassandra.keyspace")
  val dbHost: String                        = config.getString("lms-cassandra.host")
  val dbPort: Int                           = config.getInt("lms-cassandra.port")

  // ── Redis ──────────────────────────────────────────────────────────────────
  val nodeStore: Int       = config.getInt("redis.database.relationCache.id")
  val contentStoreIndex: Int =
    if (config.hasPath("redis.database.contentCache.id")) config.getInt("redis.database.contentCache.id") else 0
  val deDupRedisHost: String = config.getString("dedup-redis.host")
  val deDupRedisPort: Int    = config.getInt("dedup-redis.port")
  val deDupStore: Int        = config.getInt("dedup-redis.database.index")
  val deDupExpirySec: Int    = config.getInt("dedup-redis.database.expiry")

  // ── Cache TTL ──────────────────────────────────────────────────────────────
  val courseCacheExpiry: Int =
    if (config.hasPath("activity.course.cache.expiry")) config.getInt("activity.course.cache.expiry") else 3600000
  val relationCacheExpiry: Int =
    if (config.hasPath("activity.course.relation.cache.expiry")) config.getInt("activity.course.relation.cache.expiry") else 3600000
  val courseInMemoryCacheExpiry: Int =
    if (config.hasPath("activity.course.in.memory.cache.expiry")) config.getInt("activity.course.in.memory.cache.expiry") else 3600000

  // ── Output Tags (typed to v2.domain.CollectionProgress) ───────────────────
  val uniqueConsumptionOutputTagName  = "program-unique-consumption-events"
  val uniqueConsumptionOutput: OutputTag[Map[String, AnyRef]] =
    OutputTag[Map[String, AnyRef]](uniqueConsumptionOutputTagName)
  val auditEventOutputTagName         = "audit-events"
  val auditEventOutputTag: OutputTag[String] = OutputTag[String](auditEventOutputTagName)
  val failedEventOutputTagName        = "failed-events"
  val failedEventOutputTag: OutputTag[String] = OutputTag[String](failedEventOutputTagName)
  val collectionCompleteOutputTagName = "program-collection-progress-complete-events"
  val collectionCompleteOutputTag: OutputTag[List[CollectionProgress]] =
    OutputTag[List[CollectionProgress]](collectionCompleteOutputTagName)
  val collectionUpdateOutputTagName   = "program-collection-progress-update-events"
  val collectionUpdateOutputTag: OutputTag[List[CollectionProgress]] =
    OutputTag[List[CollectionProgress]](collectionUpdateOutputTagName)
  val certIssueOutputTagName          = "program-certificate-issue-events"
  val certIssueOutputTag: OutputTag[String] = OutputTag[String](certIssueOutputTagName)

  // ── Constants ──────────────────────────────────────────────────────────────
  val activityType         = "activity_type"
  val activityId           = "activity_id"
  val contextId            = "context_id"
  val activityUser         = "user_id"
  val aggLastUpdated       = "agg_last_updated"
  val agg                  = "agg"
  val courseId             = "courseId"
  val batchId              = "batchId"
  val contentId            = "contentId"
  val progress             = "progress"
  val contents             = "contents"
  val contentStatus        = "contentStatus"
  val userId               = "userId"
  val status               = "status"
  val unitActivityType     = "course-unit"
  val courseActivityType   = "course"
  val leafNodes            = "leafnodes"
  val ancestors            = "ancestors"
  val viewcount            = "viewcount"
  val completedcount       = "completedcount"
  val complete             = "complete"
  val eData                = "edata"
  val action               = "action"
  val batchEnrolmentUpdateCode     = "batch-enrolment-update"
  val routerFn             = "RouterFn"
  val consumptionDeDupFn   = "program-consumption-dedup-process-v2"
  val programactivityAggregateUpdaterFn = "program-activity-aggregate-updater-fn-v2"
  val partition            = "partition"
  val courseBatch          = "CourseBatch"
  val collectionProgressUpdateFn   = "progress-update-process-v2"
  val collectionCompleteFn         = "collection-completion-process-v2"
  val aggregates           = "aggregates"

  // ── Consumers / Producers ──────────────────────────────────────────────────
  val programActivityAggregateUpdaterConsumer     = "program-activity-aggregate-updater-consumer-v2"
  val programactivityAggregateUpdaterProducer     = "program-activity-aggregate-updater-audit-events-sink-v2"
  val enrolmentCompleteEventProducer              = "enrolment-complete-audit-sink-v2"
  val programactivityAggFailedEventProducer       = "program-activity-aggregate-updater-failed-sink-v2"
  val certIssueEventProducer                      = "certificate-issue-event-producer-v2"

  // ── Thresholds ─────────────────────────────────────────────────────────────
  val thresholdBatchReadInterval: Int  = config.getInt("threshold.batch.read.interval")
  val thresholdBatchReadSize: Int      = config.getInt("threshold.batch.read.size")
  val thresholdBatchWriteSize: Int     = config.getInt("threshold.batch.write.size")

  // ── Feature Flags ──────────────────────────────────────────────────────────
  val moduleAggEnabled: Boolean =
    config.getBoolean("activity.module.aggs.enabled")
  val dedupEnabled: Boolean =
    config.getBoolean("activity.input.dedup.enabled")
  val statusCacheExpirySec: Int =
    config.getInt("activity.collection.status.cache.expiry")
  val filterCompletedEnrolments: Boolean =
    if (config.hasPath("activity.filter.processed.enrolments")) config.getBoolean("activity.filter.processed.enrolments") else true

  // ── External Service URLs ──────────────────────────────────────────────────
  val searchServiceBasePath: String = config.getString("service.search.basePath")
  val searchAPIURL: String          = searchServiceBasePath + "/v3/search"
  val contentServiceBase: String    = config.getString("service.content.basePath")
  val contentReadURL: String        = contentServiceBase + "/content/v3/read/"

  // ── Misc Constants ─────────────────────────────────────────────────────────
  val identifier: String            = "identifier"
  val primaryCategory: String       = "primaryCategory"
  val versionKey: String            = "versionKey"
  val course: String                = "Course"
  val parentCollections: String     = "parentCollections"
  val defaultHeaders: Map[String, String] = Map("Content-Type" -> "application/json")
  val userAccBlockedErrCode: String = "UOS_USRRED0006"
  val name: String                  = "name"
  val validProgramPrimaryCategory: List[String] =
    List("Program", "Curated Program", "Blended Program")
  val coursecategory: String        = "coursecategory"
  val preliminaryAssessment: String = "preliminaryAssessment"
  val preliminary_Assessment_Key: String = "preliminaryassessment"
  val leafNodesKey: String          = "leafNodes"
  val contentReadFields: String     =
    if (config.hasPath("content.read.fields")) config.getString("content.read.fields")
    else "identifier,name,versionKey,parentCollections,primaryCategory,courseCategory,languageMapV1,leafNodes,language,milestones_v1,preliminaryAssessment"

  val userid       = "userid"
  val courseid     = "courseid"
  val batchid      = "batchid"
  val active       = "active"
  val contentstatus = "contentstatus"

  //  column-name config (reads from conf; defaults match Cassandra column names)
  val dbUserId: String   = if (config.hasPath("user_enrolments.userid.column"))   config.getString("user_enrolments.userid.column")   else "userid"
  val dbCourseId: String = if (config.hasPath("user_enrolments.courseid.column")) config.getString("user_enrolments.courseid.column") else "courseid"
  val dbBatchId: String  = if (config.hasPath("user_enrolments.batchid.column"))  config.getString("user_enrolments.batchid.column")  else "batchid"
  val dbStatus: String   = if (config.hasPath("user_enrolments.status.column"))   config.getString("user_enrolments.status.column")   else "status"

  val cassandraConsistencyLevel: String =
    if (config.hasPath("cassandra.consistency.level")) config.getString("cassandra.consistency.level") else "LOCAL_QUORUM"
  val enrolmentBatchChunkSize: Int =
    if (config.hasPath("enrolment.batch.read.chunk.size")) config.getInt("enrolment.batch.read.chunk.size") else 50


  val dbContentStatus: String = "contentstatus"
  val dbProgress: String      = "progress"
  val dbActive: String        = "active"
  val dbCompletedon: String   = "completedon"
  val dbDatetime: String      = "datetime"

  // Group 2: Cassandra connection timeouts
  // Why: V1 used CassandraUtil default timeouts. V2 makes them explicit and configurable.
  override val cassandraReadTimeoutMs: Int    = config.getInt("lms-cassandra.read.timeout.ms")
  override val cassandraConnectTimeoutMs: Int = config.getInt("lms-cassandra.connect.timeout.ms")
  override val cassandraMaxRetries: Int       = config.getInt("lms-cassandra.max.retries")

  // Group 3: Timer-based micro-batching interval
  // Why: V2 uses ProcessFunction timer instead of GlobalWindow to reduce latency.
  val processingTimerIntervalMs: Long = config.getLong("processing.timer.interval.ms")

  // Group 4: Feature flag — optimised single-partition batch enrolment read
  // Why: V1 did N Cassandra reads for N events. V2 batches by userId = 1 read per user.
  val enrolmentBatchReadEnabled: Boolean =
    if (config.hasPath("enrolment.batch.read.enabled")) config.getBoolean("enrolment.batch.read.enabled") else true

}
