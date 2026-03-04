package org.sunbird.job.usercompetencyupdate.functions

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.usercompetencyupdate.domain.Event
import org.sunbird.job.usercompetencyupdate.task.UserCompetencyUpdaterConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil, JSONUtil, ScalaJsonUtil}
import org.sunbird.job.{BaseProcessKeyedFunction, Metrics}

import scala.collection.JavaConverters._

class UserCompetencyPreProcessorFn(config: UserCompetencyUpdaterConfig, httpUtil: HttpUtil)
  (implicit val stringTypeInfo: TypeInformation[String],
   @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessKeyedFunction[String, Event, String](config) {

  private[this] val logger = LoggerFactory.getLogger(classOf[UserCompetencyPreProcessorFn])
  private var cache: DataCache = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
    val redisConnect = new RedisConnect(config)
    cache = new DataCache(config, redisConnect, config.collectionCacheStore, List())
    cache.init()
  }

  override def close(): Unit = {
    cassandraUtil.close()
    super.close()
  }

  override def metricsList(): List[String] = {
    List(config.totalEventsCount, config.dbReadCount, config.dbUpdateCount, config.failedEventCount, config.skippedEventCount, config.successEventCount)
  }

  override def processElement(event: Event,
                              context: KeyedProcessFunction[String, Event, String]#Context,
                              metrics: Metrics): Unit = {
    try {
      val contextType = event.contextType
      if (contextType == "achievement") {
        processAchievementEventById(event, metrics)
      } else if (contextType == "iGOTCourses") {
        processIGOTCourses(event, metrics)
      } else if (contextType == "extCourses") {
        processExtCourses(event, metrics)
      }
      // No generic upsert logic for other types
    } catch {
      case ex: Exception =>
        metrics.incCounter(config.failedEventCount)
        logger.error("Error processing event: " + ex.getMessage, ex)
    }
  }

  private def processAchievementEventById(event: Event, metrics: Metrics): Unit = {
    val achievementTable = config.learnerAchievementTable
    val userCompetencyTable = config.userCompetencyTable
    val achievementId = event.contentId // Assuming contentId is used as achievement id
    val userId = event.userId
    val contextType = event.contextType
    val contentId = event.contentId
    // Query by userid, contexttype, and id (Cassandra requires full partition key)
    val query = s"SELECT * FROM $achievementTable WHERE userid='$userId' AND contexttype='$contextType' AND id='$contentId';"
    val rows = cassandraUtil.find(query)
    if (rows != null && !rows.isEmpty) {
      for (row <- rows.asScala) {
        val contextDataJson = row.getString("contextdata")
        val contextData = org.sunbird.job.util.ScalaJsonUtil.deserialize[Map[String, AnyRef]](contextDataJson)
        val competencies = contextData.get("competencies_v6") match {
          case Some(list: java.util.List[_]) => list.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]].asScala.toList.map(_.asScala.toMap)
          case Some(list: List[_]) => list.asInstanceOf[List[Map[String, AnyRef]]]
          case _ => List.empty[Map[String, AnyRef]]
        }
        competencies.foreach { comp =>
          val areaId = comp.getOrElse("competencyAreaIdentifier", "").toString
          val themeId = comp.getOrElse("competencyThemeIdentifier", "").toString
          val subthemeId = comp.getOrElse("competencySubThemeIdentifier", "").toString
          // Determine the details type (selfAchievemt, iGOTCourses, iGOTEvent) based on contextType or other event property
          val detailsType = contextType match {
            case "achievement" => "selfAchievemt"
            case "iGOTCourses" => "iGOTCourses"
            case "iGOTEvent" => "iGOTEvent"
            case _ => "selfAchievemt" // default fallback
          }
          val detailsMap = Map(
            "acquiredContextId" -> achievementId,
            "certificateId" -> contextData.getOrElse("certificateId", "").toString,
            "acquired_at" -> contextData.getOrElse("issuedDate", Option(row.getTimestamp("createdon")).map(_.toString).getOrElse("")).toString,
            "uploadedDocumentUrl" -> contextData.getOrElse("uploadedDocumentUrl", "").toString
          )
          // Fetch existing competency_details
          val selectQuery = s"SELECT competency_details FROM $userCompetencyTable WHERE user_id='$userId' AND competency_area_id='$areaId' AND competency_theme_id='$themeId' AND competency_subtheme_id='$subthemeId';"
          val existingRows = cassandraUtil.find(selectQuery)
          var competencyDetails: Map[String, List[Map[String, String]]] = Map()
          if (existingRows != null && !existingRows.isEmpty) {
            val detailsObj = existingRows.get(0).getMap("competency_details", classOf[String], classOf[java.util.List[java.util.Map[String, String]]])
            if (detailsObj != null) {
              // Convert Java Map[String, List[Java Map[String, String]]] to Scala Map[String, List[Map[String, String]]]
              competencyDetails = detailsObj.asScala.map { case (k, v) =>
                k -> v.asScala.toList.map(_.asScala.toMap)
              }.toMap
            }
          }
          // Append new details to the correct detailsType (selfAchievemt, iGOTCourses, iGOTEvent)
          val updatedList = competencyDetails.getOrElse(detailsType, List()) :+ detailsMap
          val updatedDetails = competencyDetails + (detailsType -> updatedList)
          // Upsert into Cassandra
          val upsertQuery = s"INSERT INTO $userCompetencyTable (user_id, competency_area_id, competency_theme_id, competency_subtheme_id, competency_details) VALUES ('$userId', '$areaId', '$themeId', '$subthemeId', '${org.sunbird.job.util.ScalaJsonUtil.serialize(updatedDetails)}');"
          cassandraUtil.upsert(upsertQuery)
          metrics.incCounter(config.dbUpdateCount)
          logger.info(s"Upserted $detailsType competency for userId=$userId, areaId=$areaId, themeId=$themeId, subthemeId=$subthemeId")
        }
      }
    } else {
      metrics.incCounter(config.failedEventCount)
      logger.warn(s"No achievement data found for userId=$userId, contextType=$contextType, id=$achievementId")
    }
  }

  // Helper for API call, returns the required response map or throws on error
  private def getAPICall(url: String, responseParam: String)(config: UserCompetencyUpdaterConfig, httpUtil: HttpUtil, metrics: Metrics): java.util.Map[String, AnyRef] = {
    val response = httpUtil.get(url, config.defaultHeaders)
    if (200 == response.status) {
      val result = JSONUtil.deserialize[Map[String, AnyRef]](response.body)
        .getOrElse("result", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
      if (result.contains(responseParam)) {
        val scalaMap = result(responseParam).asInstanceOf[Map[String, AnyRef]]
        new java.util.HashMap[String, AnyRef](scalaMap.asJava)
      } else {
        new java.util.HashMap[String, AnyRef]()
      }
    } else if (400 == response.status && response.body.contains(config.userAccBlockedErrCode)) {
      metrics.incCounter(config.skippedEventCount)
      logger.error(s"Error while fetching user details for ${url}: " + response.status + " :: " + response.body)
      new java.util.HashMap[String, AnyRef]()
    } else {
      throw new Exception(s"Error from get API : ${url}, with response: ${response}")
    }
  }

  // Helper for API call for extcontent, returns the required response map or throws on error
  private def getExtContentAPICall(url: String)(config: UserCompetencyUpdaterConfig, httpUtil: HttpUtil, metrics: Metrics): java.util.Map[String, AnyRef] = {
    val response = httpUtil.get(url, config.defaultHeaders)
    if (200 == response.status) {
      val result = JSONUtil.deserialize[Map[String, AnyRef]](response.body)
      if (result.contains("content")) {
        val scalaMap = result("content").asInstanceOf[Map[String, AnyRef]]
        new java.util.HashMap[String, AnyRef](scalaMap.asJava)
      } else {
        new java.util.HashMap[String, AnyRef]()
      }
    } else if (400 == response.status && response.body.contains(config.userAccBlockedErrCode)) {
      metrics.incCounter(config.skippedEventCount)
      logger.error(s"Error while fetching extcontent details for ${url}: " + response.status + " :: " + response.body)
      new java.util.HashMap[String, AnyRef]()
    } else {
      throw new Exception(s"Error from extcontent get API : ${url}, with response: ${response}")
    }
  }

  private def processIGOTCourses(event: Event, metrics: Metrics): Unit = {

    import scala.collection.JavaConverters._
    import com.google.common.reflect.TypeToken

    val userId = event.userId
    val courseId = event.contentId
    val batchId = event.batchId
    val userCompetencyTable = config.userCompetencyTable
    val enrolmentQuery =
      s"""
       SELECT issued_certificates
       FROM sunbird_courses.user_enrolments_v2
       WHERE userid='$userId'
       AND courseid='$courseId'
       AND batchid='$batchId';
     """

    val enrolmentRows = cassandraUtil.find(enrolmentQuery)

    if (enrolmentRows == null || enrolmentRows.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      logger.error(s"No enrolment found for userId=$userId courseId=$courseId")
      return
    }

    var issuedCertificates: List[java.util.Map[String, String]] = List.empty

    for (i <- 0 until enrolmentRows.size()) {
      val row = enrolmentRows.get(i)

      val certsRaw = row.getList(
        "issued_certificates",
        new TypeToken[java.util.Map[String, String]]() {}
      )
      if (certsRaw != null) {
        issuedCertificates ++= certsRaw.asScala.toList
      }
    }
    if (issuedCertificates.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      logger.error(s"No issued_certificates found for userId=$userId")
      return
    }
    val certMapOpt =
      issuedCertificates
        .find(c => Option(c.get("version")).exists(_.equalsIgnoreCase("v2")))
        .orElse(issuedCertificates.headOption)

    if (certMapOpt.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      return
    }
    val certMap = certMapOpt.get
    val certificateId =
      Option(certMap.get("certificateId"))
        .orElse(Option(certMap.get("identifier")))
        .map(_.toString)
        .getOrElse("")

    val issuedDate =
      Option(certMap.get("lastIssuedOn")).map(_.toString).getOrElse("")

    if (certificateId.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      return
    }
    val courseMetadata: java.util.Map[String, AnyRef] = getCourseInfo(courseId)(metrics, config, cache, httpUtil)
    val competencies = courseMetadata.getOrDefault("competencies_v6", new java.util.ArrayList[java.util.Map[String, AnyRef]]()).asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]

    if (competencies.isEmpty) {
      logger.warn(s"No competencies found for courseId=$courseId")
      return
    }

    // Process each competency object
    import scala.collection.JavaConverters._
    competencies.asScala.foreach { comp =>
      val areaId = comp.getOrDefault("competencyAreaIdentifier", "").toString
      val themeId = comp.getOrDefault("competencyThemeIdentifier", "").toString
      val subthemeId = comp.getOrDefault("competencySubThemeIdentifier", "").toString
      val description = comp.getOrDefault("competencyAreaDescription", "").toString
      val newDetail: Map[String, String] = Map(
        "acquiredContextId" -> courseId,
        "certificateId"     -> certificateId,
        "acquired_at"       -> issuedDate
      )
      // Ensure userCompetencyTable is fully qualified with DB name from config
      val dbName = config.dbName // assuming config.dbName is set to "sunbird"
      val userCompetencyTableFull = if (userCompetencyTable.contains(".")) userCompetencyTable else s"$dbName.$userCompetencyTable"
      val selectQuery =
        s"""
           SELECT competency_details
           FROM $userCompetencyTableFull
           WHERE user_id='$userId'
           AND competency_area_id='$areaId'
           AND competency_theme_id='$themeId'
           AND competency_subtheme_id='$subthemeId';
         """
      val existingRows = cassandraUtil.find(selectQuery)
      var competencyDetails: Map[String, List[Map[String, String]]] = Map()
      if (existingRows != null && !existingRows.isEmpty) {

        val row = existingRows.get(0)

        import com.google.common.reflect.TypeToken

        val typeToken =
          new TypeToken[java.util.Map[String,
            java.util.List[java.util.Map[String, String]]]]() {}

        val detailsObj =
          row.get("competency_details", typeToken)

        if (detailsObj != null) {
          competencyDetails =
            detailsObj.asScala.map { case (k, v) =>
              k -> v.asScala.toList.map(_.asScala.toMap)
            }.toMap
        }
      }
      val updatedList =
        competencyDetails
          .getOrElse("iGOTCourses", List())
          .filterNot(_("acquiredContextId") == courseId) :+ newDetail
      val updatedDetails =
        competencyDetails + ("iGOTCourses" -> updatedList)
      val cqlDetails = toCqlMap(updatedDetails)
      val upsertQuery =
        s"""
           INSERT INTO $userCompetencyTableFull
           (user_id, competency_area_id, competency_theme_id,
            competency_subtheme_id, competency_details)
           VALUES
           ('$userId', '$areaId', '$themeId',
            '$subthemeId', $cqlDetails);
         """
      cassandraUtil.upsert(upsertQuery)
      logger.info(s"Processing competency: areaId=$areaId, themeId=$themeId, subthemeId=$subthemeId, description=$description")
    }
    metrics.incCounter(config.dbUpdateCount)
  }

  private def toCqlMap(
                        data: Map[String, List[Map[String, String]]]
                      ): String = {
    val outer = data.map { case (key, list) =>
      val listStr = list.map { innerMap =>
        val innerStr = innerMap.map {
          case (k, v) =>
            s"'$k': '${v.replace("'", "''")}'"
        }.mkString(", ")
        s"{ $innerStr }"
      }.mkString(", ")
      s"'$key': [ $listStr ]"
    }.mkString(", ")
    s"{ $outer }"
  }
  // Make getCourseInfo private and only return competencies_v6
  private def getCourseInfo(courseId: String)(
    metrics: Metrics,
    config: UserCompetencyUpdaterConfig,
    cache: DataCache,
    httpUtil: HttpUtil
  ): java.util.Map[String, AnyRef] = {
    val courseMetadata = cache.getWithRetry(courseId)
    val courseInfoMap: java.util.Map[String, AnyRef] = new java.util.HashMap[String, AnyRef]()
    val competencies: java.util.List[java.util.Map[String, AnyRef]] = {
      val raw = if (courseMetadata == null || courseMetadata.isEmpty || !courseMetadata.contains("competencies_v6")) {
        val url = config.contentReadURL + courseId + "?fields=competencies_v6"
        val response = getAPICall(url, "content")(config, httpUtil, metrics)
        response.get("competencies_v6")
      } else {
        courseMetadata.get("competencies_v6")
      }
      raw match {
        case jl: java.util.List[_] =>
          jl.asScala.map {
            case jm: java.util.Map[_, _] => jm.asInstanceOf[java.util.Map[String, AnyRef]]
            case sm: Map[_, _] => sm.asJava.asInstanceOf[java.util.Map[String, AnyRef]]
            case other => other.asInstanceOf[java.util.Map[String, AnyRef]]
          }.toList.asJava
        case s: Seq[_] =>
          s.map {
            case jm: java.util.Map[_, _] => jm.asInstanceOf[java.util.Map[String, AnyRef]]
            case sm: Map[_, _] => sm.asJava.asInstanceOf[java.util.Map[String, AnyRef]]
            case other => other.asInstanceOf[java.util.Map[String, AnyRef]]
          }.toList.asJava
        case _ => new java.util.ArrayList[java.util.Map[String, AnyRef]]()
      }
    }
    courseInfoMap.put("competencies_v6", competencies)
    courseInfoMap
  }

  // Add new method for extCourses
  private def processExtCourses(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    val courseId = event.contentId
    val userCompetencyTable = config.userCompetencyTable
    val extContentReadUrl = config.extContentUrl
    val contentUrl = s"$extContentReadUrl$courseId"
    val courseMetadata = cache.getWithRetry(courseId)
    val raw =
      if (courseMetadata != null && courseMetadata.contains("content")) {
        val contentMap =
          courseMetadata("content").asInstanceOf[java.util.Map[String, AnyRef]]
        if (contentMap.containsKey("competencies_v6")) {
          contentMap.get("competencies_v6")
        } else {
          val response = getExtContentAPICall(contentUrl)(config, httpUtil, metrics)
          response.get("competencies_v6")
        }
      } else {
        val response = getExtContentAPICall(contentUrl)(config, httpUtil, metrics)
        response.get("competencies_v6")
      }
    val competencies: java.util.List[java.util.Map[String, AnyRef]] = raw match {
      case null => new java.util.ArrayList[java.util.Map[String, AnyRef]]()
      case jl: java.util.List[_] =>
        jl.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
      case s: Seq[_] =>
        s.map {
          case jm: java.util.Map[_, _] => jm.asInstanceOf[java.util.Map[String, AnyRef]]
          case sm: Map[_, _] => sm.asJava.asInstanceOf[java.util.Map[String, AnyRef]]
          case other => other.asInstanceOf[java.util.Map[String, AnyRef]]
        }.toList.asJava
      case _ => new java.util.ArrayList[java.util.Map[String, AnyRef]]()
    }
    if (competencies.isEmpty) {
      logger.warn(s"No competencies found for extCourseId=$courseId")
      return
    }
    // Fetch issued_certificates from user_external_enrolments
    val externalEnrolQuery =
      s"""
         SELECT issued_certificates
         FROM sunbird_courses.user_external_enrolments
         WHERE userid='$userId'
         AND courseid='$courseId';
       """
    import scala.collection.JavaConverters._
    import com.google.common.reflect.TypeToken
    val enrolmentRows = cassandraUtil.find(externalEnrolQuery)
    var issuedCertificates: List[java.util.Map[String, String]] = List.empty
    if (enrolmentRows != null && !enrolmentRows.isEmpty) {
      val row = enrolmentRows.get(0)
      val certsRaw = row.getObject("issued_certificates").asInstanceOf[java.util.List[java.util.Map[String, String]]]
      if (certsRaw != null) {
        issuedCertificates ++= certsRaw.asScala.toList
      }
    }
    // Add logic to pick version v2 if present, else first
    val certMapOpt =
      issuedCertificates.find(c => Option(c.get("version")).exists(_.equalsIgnoreCase("v2")))
        .orElse(issuedCertificates.headOption)
    val certificateId = certMapOpt.flatMap(c => Option(c.get("certificateId")).orElse(Option(c.get("identifier")))).getOrElse("")
    val issuedDate = certMapOpt.flatMap(c => Option(c.get("lastIssuedOn"))).getOrElse("")
    competencies.asScala.foreach { comp =>
      val areaId = comp.getOrDefault("competencyAreaIdentifier", "").toString
      val themeId = comp.getOrDefault("competencyThemeIdentifier", "").toString
      val subthemeId = comp.getOrDefault("competencySubThemeIdentifier", "").toString
      val newDetail: Map[String, String] = Map(
        "acquiredContextId" -> courseId,
        "certificateId"     -> certificateId,
        "acquired_at"       -> issuedDate
      )
      val dbName = config.dbName
      val userCompetencyTableFull = if (userCompetencyTable.contains(".")) userCompetencyTable else s"$dbName.$userCompetencyTable"
      val selectQuery =
        s"""
           SELECT competency_details
           FROM $userCompetencyTableFull
           WHERE user_id='$userId'
           AND competency_area_id='$areaId'
           AND competency_theme_id='$themeId'
           AND competency_subtheme_id='$subthemeId';
         """
      val existingRows = cassandraUtil.find(selectQuery)
      var competencyDetails: Map[String, List[Map[String, String]]] = Map()
      if (existingRows != null && !existingRows.isEmpty) {
        val row = existingRows.get(0)

        import com.google.common.reflect.TypeToken

        val typeToken =
          new TypeToken[java.util.Map[String,
            java.util.List[java.util.Map[String, String]]]]() {}

        val detailsObj =
          row.get("competency_details", typeToken)

        if (detailsObj != null) {
          competencyDetails =
            detailsObj.asScala.map { case (k, v) =>
              k -> v.asScala.toList.map(_.asScala.toMap)
            }.toMap
        }
      }
      val updatedList =
        competencyDetails
          .getOrElse("extCourses", List())
          .filterNot(_("acquiredContextId") == courseId) :+ newDetail
      val updatedDetails =
        competencyDetails + ("extCourses" -> updatedList)
      val cqlDetails = toCqlMap(updatedDetails)
      val upsertQuery =
        s"""
           INSERT INTO $userCompetencyTableFull
           (user_id, competency_area_id, competency_theme_id,
            competency_subtheme_id, competency_details)
           VALUES
           ('$userId', '$areaId', '$themeId',
            '$subthemeId', $cqlDetails);
         """
      cassandraUtil.upsert(upsertQuery)
      metrics.incCounter(config.dbUpdateCount)
      logger.info(s"Processing extCourse competency: areaId=$areaId, themeId=$themeId, subthemeId=$subthemeId")
    }
  }
}
