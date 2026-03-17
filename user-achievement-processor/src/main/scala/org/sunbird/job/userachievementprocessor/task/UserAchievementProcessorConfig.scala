package org.sunbird.job.userbadgeawarding.task

import com.typesafe.config.Config
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.streaming.api.scala.OutputTag
import org.sunbird.job.BaseJobConfig

class UserBadgeAwardingConfig(override val config: Config) extends BaseJobConfig(config, "user-badge-awarding-processor") {

  implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])

  //Redis config
  val collectionCacheStore: Int = 0
  val contentCacheStore: Int = 5
  val recentBadgeActivityKey: String = config.getString("redis.recentBadgeActivityKey")
  val recentBadgeActivityMaxSize: Int = config.getInt("redis.recentBadgeActivityMaxSize")


  //kafka config
  val kafkaInputTopic: String = config.getString("kafka.input.topic")
  override val kafkaConsumerParallelism: Int = config.getInt("task.consumer.parallelism")

  //Cassandra config
  val dbHost: String = config.getString("lms-cassandra.host")
  val dbPort: Int = config.getInt("lms-cassandra.port")
  val dbName: String = config.getString("lms-cassandra.sunbird.db")

  // Metric List
  val totalEventsCount = "total-events-count"
  val successEventCount = "success-events-count"
  val failedEventCount = "failed-events-count"
  val skippedEventCount = "skipped-event-count"
  val dbUpdateCount = "db-update-count"

  //Constants
  val defaultHeaders = Map[String, String]("Content-Type" -> "application/json")
  val userAccBlockedErrCode = "UOS_USRRED0006"

  // Content and External Content config
  val contentReadURL: String = config.getString("content.read.url")
  val userReadURL: String = config.getString("user.read.url")
  val extContentUrl: String = config.getString("extcontent.read.url")
  val extCoursesContextType: String = config.getString("extcontent.extCourses")
  val iGOTCoursesContextType: String = config.getString("extcontent.iGOTCourses")
  val extContentResponseKey: String = config.getString("extcontent.responseKey")
  val extContentUserExternalEnrolmentsDb: String = config.getString("extcontent.db")
  val extContentUserExternalEnrolmentsTable: String = config.getString("extcontent.table")
  val extContentUserExternalEnrolmentsIssuedCertificatesKey: String = config.getString("extcontent.issuedCertificatesKey")
  val lastIssuedOnKey: String = config.getString("extcontent.lastIssuedOnKey")
  val coursesdb: String = config.getString("extcontent.db")
  val enrolmentTable: String = config.getString("extcontent.enrolmenttable")
  val issuedCertificatesKey: String = config.getString("extcontent.issuedCertificatesKey")

  // Badge awarding config
  val badgeDetailsV1Key: String = config.getString("extcontent.badgeDetailsV1Key")
  val badgeEarningDateEnabledKey: String = config.getString("extcontent.badgeEarningDateEnabledKey")
  val badgeEarningDateTimeKey: String = config.getString("extcontent.badgeEarningDateTimeKey")
  val issuedBadgesKey: String = config.getString("extcontent.issuedBadgesKey")
  val criteriaKey: String = config.getString("extcontent.criteriaKey")
  val badgeIdKey: String = config.getString("extcontent.badgeIdKey")
  val issuedOnKey: String = config.getString("extcontent.issuedOnKey")
  val templateUrlKey: String = config.getString("extcontent.templateUrlKey")
  val badgeTemplateKey: String = config.getString("extcontent.badgeTemplateKey")
}
