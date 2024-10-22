package org.sunbird.job.useractivity.task

import com.typesafe.config.Config
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.streaming.api.scala.OutputTag
import org.sunbird.job.BaseJobConfig

class UserActivityAnalysisUpdaterConfig(override val config: Config) extends BaseJobConfig(config, "user-activity-analysis-updater") {

  implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])

  //Redis config
  val collectionCacheStore: Int = 0

  val contentCacheStore: Int = 5


  //kafka config
  val kafkaInputTopic: String = config.getString("kafka.input.topic")

  val certificatePreProcessorConsumer: String = "user-activity-analysis-updator-consumer"

  override val kafkaConsumerParallelism: Int = config.getInt("task.consumer.parallelism")

 //Cassandra config
  val dbHost: String = config.getString("lms-cassandra.host")
  val dbPort: Int = config.getInt("lms-cassandra.port")
  val keyspace: String = config.getString("lms-cassandra.keyspace")
  val userTable: String = config.getString("lms-cassandra.user.table")

  // Metric List
  val totalEventsCount = "total-events-count"
  val successEventCount = "success-events-count"
  val failedEventCount = "failed-events-count"
  val skippedEventCount = "skipped-event-count"
  val dbReadCount = "db-read-count"
  val dbUpdateCount = "db-update-count"
  val cacheHitCount = "cache-hit-cout"
  val programCertIssueEventsCount = "program-cert-issue-events-count"
  val cacheMissCount = "cache-miss-count"

  //Constants
  val status: String = "status"
  val name: String = "name"
  val defaultHeaders = Map[String, String]("Content-Type" -> "application/json")
  val userAccBlockedErrCode = "UOS_USRRED0006"

 //Postgres config
  val postgresDbHost: String = config.getString("postgres.host")
  val postgresDbPort: Int = config.getInt("postgres.port")
  val postgresDbDatabase: String = config.getString("postgres.database")
  val postgresDbUsername: String = config.getString("postgres.username")
  val postgresDbPassword: String = config.getString("postgres.password")
  val postgresDbTable: String = "user_activity"

}
