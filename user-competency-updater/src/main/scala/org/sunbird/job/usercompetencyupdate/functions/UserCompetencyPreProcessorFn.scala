package org.sunbird.job.usercompetencyupdate.functions

import com.datastax.driver.core.Row
import com.google.common.reflect.TypeToken
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
import org.sunbird.job.util.ScalaJsonUtil

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
      logger.info(s"processElement - received event: userId=${event.userId}, contextType=${event.contextType}, contentId=${event.contentId}")
      if (event.isFirstTimeUser != null && event.isFirstTimeUser) {
        processFirstTimeUser(event, metrics)
      } else {
        val contextType = event.contextType
        if (contextType == config.achievements) {
          processAchievementEvent(event, metrics)
        } else if (contextType == "iGOTCourses") {
          processIGOTCourses(event, metrics)
        } else if (contextType != null && contextType.equalsIgnoreCase(config.extCoursesContextType)) {
          processExtCourses(event, metrics)
        } else if (contextType != null && contextType.equalsIgnoreCase(config.externalTraining)) {
          processExternalTraining(event, metrics)
        }
      }
    } catch {
      case ex: Exception =>
        metrics.incCounter(config.failedEventCount)
        logger.error("Error processing event: " + ex.getMessage, ex)
    }
  }

  private def processExternalTraining(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    val courseId = event.contentId
    val batchId = event.batchId
    val userCompetencyTable = config.userCompetencyTable
    val enrolmentQuery =
      s"""
       SELECT issued_certificates
       FROM ${config.coursesdb}.${config.userEntityEnrolmentsTable}
       WHERE userid='$userId'
       AND contextid='$courseId'
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
        config.issuedCertificatesKey,
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
    val certMap = issuedCertificates.head
    val certificateId =
      Option(certMap.get(config.identifierKey))
        .map(_.toString)
        .getOrElse("")

    val issuedDate =
      Option(certMap.get(config.lastIssuedOnKey))
        .map(_.toString)
        .getOrElse("")

    if (certificateId.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      return
    }
    val courseMetadata =
      getCourseInfo(courseId)(metrics, config, cache, httpUtil)
    val competencies =
      courseMetadata
        .getOrDefault(config.competenciesV6Key,
          new java.util.ArrayList[java.util.Map[String, AnyRef]]())
        .asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
    if (competencies.isEmpty) {
      logger.warn(s"No competencies found for courseId=$courseId")
      return
    }
    competencies.asScala.foreach { comp =>
      val areaId =
        comp.getOrDefault(config.competencyAreaIdentifierKey, "").toString
      val themeId =
        comp.getOrDefault(config.competencyThemeIdentifierKey, "").toString
      val subthemeId =
        comp.getOrDefault(config.competencySubThemeIdentifierKey, "").toString
      val newDetail: Map[String, String] =
        Map(
          config.acquiredContextIdKey -> courseId,
          config.certificateIdKey -> certificateId,
          config.acquiredAt -> issuedDate
        )
      val dbName = config.dbName
      val userCompetencyTableFull =
        if (userCompetencyTable.contains(".")) userCompetencyTable
        else s"$dbName.$userCompetencyTable"
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
        val typeToken =
          new TypeToken[java.util.Map[String,
            java.util.List[java.util.Map[String, String]]]]() {}
        val detailsObj =
          row.get(config.competencies, typeToken)
        if (detailsObj != null) {
          competencyDetails =
            detailsObj.asScala.map { case (k, v) =>
              k -> v.asScala.toList.map(_.asScala.toMap)
            }.toMap
        }
      }
      val updatedList =
        competencyDetails
          .getOrElse(config.externalTraining, List())
          .filterNot(_(config.acquiredContextIdKey) == courseId) :+ newDetail

      val updatedDetails =
        competencyDetails + (config.externalTraining -> updatedList)

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
    }

    metrics.incCounter(config.dbUpdateCount)
  }

  private def processFirstTimeUser(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    logger.info(s"processFirstTimeUser - starting for userId=$userId")
    fetchUserEnrollments(userId, metrics)
    processUserExtCourses(userId, metrics)
  }

  private def fetchUserEnrollments(userId: String, metrics: Metrics): Unit = {
    val batchSize = config.firstTimeUserFetchLimit
    var lastCourseId: String = null
    var lastBatchId: String = null
    var hasMore = true
    logger.info(s"fetchUserEnrollments - userId=$userId batchSize=$batchSize")
    while (hasMore) {
      val query = if (lastCourseId == null)
        s"SELECT courseid,batchid,status,issued_certificates FROM ${config.coursesdb}.${config.enrolmentTable} WHERE userid='$userId' LIMIT $batchSize;"
      else
        s"SELECT courseid,batchid,status,issued_certificates FROM ${config.coursesdb}.${config.enrolmentTable} WHERE userid='$userId' AND (courseid,batchid) > ('$lastCourseId','$lastBatchId') LIMIT $batchSize;"
      val rows = cassandraUtil.find(query)
      if (rows == null || rows.isEmpty) {
        logger.debug(s"fetchUserEnrollments - no rows for userId=$userId lastCourseId=$lastCourseId lastBatchId=$lastBatchId")
        hasMore = false
      } else {
        val enrolments = rows.asScala.map(rowToMap).toList
        enrolments
          .filter(_(config.status).toString.toInt == 2)
          .foreach { e =>
            try {
              processCourse(userId, e, metrics)
            } catch {
              case ex: Exception =>
                metrics.incCounter(config.failedEventCount)
                logger.error(s"Error processing course for firstTimeUser userId=$userId courseId=${e(config.courseid)}", ex)
            }
          }
        val lastRow = rows.get(rows.size() - 1)
        lastCourseId = lastRow.getString(config.courseid)
        lastBatchId = lastRow.getString(config.batchid)
        logger.debug(s"fetchUserEnrollments - processed batch size=${rows.size()} lastCourseId=$lastCourseId lastBatchId=$lastBatchId")
        if (rows.size() < batchSize) hasMore = false
      }
    }
  }

  private def processCourse(userId: String,enrolment: Map[String,AnyRef],metrics: Metrics): Unit = {
    import scala.collection.JavaConverters._
    val courseId = enrolment(config.courseid).toString
    logger.info(s"processCourse - userId=$userId courseId=$courseId")
    val courseInfo = getCourseInfo(courseId)(metrics,config,cache,httpUtil)
    val competencies = courseInfo.get(config.competenciesV6Key).asInstanceOf[java.util.List[java.util.Map[String,AnyRef]]].asScala.toList.map(_.asScala.toMap)
    var certificateId = ""
    var acquiredAt = ""
    val certs = enrolment.get(config.issuedCertificatesKey).map(_.asInstanceOf[java.util.List[java.util.Map[String,String]]])
    certs.foreach(l => if (!l.isEmpty) { val c=l.get(0); certificateId=c.getOrDefault(config.identifierKey,""); acquiredAt=c.getOrDefault(config.lastIssuedOnKey,"") })
    val detailsMap = Map(config.acquiredContextIdKey->courseId,config.certificateIdKey->certificateId,config.acquiredAt->acquiredAt)
    logger.debug(s"processCourse - competencies count=${competencies.size} for courseId=$courseId")
    competencies.foreach { comp =>
      val areaId = comp.getOrElse(config.competencyAreaIdentifierKey,"").toString
      val themeId = comp.getOrElse(config.competencyThemeIdentifierKey,"").toString
      val subthemeId = comp.getOrElse(config.competencySubThemeIdentifierKey,"").toString
      upsertUserCompetencyByContext(userId, areaId, themeId, subthemeId, detailsMap, config.iGOTCourses, config.dbName + "." + config.userCompetencyTable, metrics)
    }
  }

  private def upsertUserCompetencyByContext(
                                             userId: String,
                                             areaId: String,
                                             themeId: String,
                                             subthemeId: String,
                                             detailsMap: Map[String, String],
                                             competencyKey: String,
                                             userCompetencyTable: String,
                                             metrics: Metrics
                                           ): Unit = {
    logger.debug(s"upsertUserCompetencyByContext - reading competency_details for userId=$userId area=$areaId theme=$themeId subtheme=$subthemeId key=$competencyKey")
    val selectQuery =
      s"SELECT competency_details FROM $userCompetencyTable WHERE user_id='$userId' AND competency_area_id='$areaId' AND competency_theme_id='$themeId' AND competency_subtheme_id='$subthemeId';"
    val existingRows = cassandraUtil.find(selectQuery)
    var competencyDetails: Map[String, List[Map[String, String]]] = Map()
    if (existingRows != null && !existingRows.isEmpty) {
      val typeToken =
        new TypeToken[java.util.Map[String, java.util.List[java.util.Map[String, String]]]]() {}
      val detailsObj =
        existingRows.get(0).get(config.competencyDetails, typeToken)
      if (detailsObj != null) {
        competencyDetails =
          detailsObj.asScala.map { case (k, v) =>
            k -> v.asScala.toList.map(_.asScala.toMap)
          }.toMap
      }
    }
    val updatedList =
      competencyDetails
        .getOrElse(competencyKey, List())
        .filterNot(_(config.acquiredContextIdKey) == detailsMap(config.acquiredContextIdKey)) :+ detailsMap
    val updatedDetails =
      competencyDetails + (competencyKey -> updatedList)
    val cqlDetails = toCqlMap(updatedDetails)
    val upsertQuery =
      s"""
       INSERT INTO $userCompetencyTable
       (user_id, competency_area_id, competency_theme_id,
        competency_subtheme_id, competency_details)
       VALUES
       ('$userId', '$areaId', '$themeId', '$subthemeId', $cqlDetails);
     """
    logger.info(s"upsertUserCompetencyByContext - upserting competency for userId=$userId area=$areaId theme=$themeId subtheme=$subthemeId key=$competencyKey")
    cassandraUtil.upsert(upsertQuery)
    metrics.incCounter(config.dbUpdateCount)
  }

  private def rowToMap(row: Row): Map[String, AnyRef] = {
    row.getColumnDefinitions.asList().asScala.map(c => c.getName -> row.getObject(c.getName)).toMap
  }

  private def processUserExtCourses(userId: String, metrics: Metrics): Unit = {
    logger.info(s"processUserExtCourses - starting for userId=$userId")
    val query =
      s"SELECT courseid,status,issued_certificates FROM ${config.extContentUserExternalEnrolmentsDb}.${config.extContentUserExternalEnrolmentsTable} WHERE userid='$userId';"
    val rows = cassandraUtil.find(query)
    if (rows != null && !rows.isEmpty) {
      rows.asScala.foreach { row =>
        if (row.getInt(config.status) == 2) {
          val courseId = row.getString(config.courseid)
          val issuedCertificates =
            row.getObject(config.extContentUserExternalEnrolmentsIssuedCertificatesKey)
              .asInstanceOf[java.util.List[java.util.Map[String, String]]]
          processExtCourseForFirstTimeUser(
            userId,
            courseId,
            issuedCertificates,
            metrics
          )
        }
      }
    }
  }

  private def processExtCourseForFirstTimeUser(
                                                userId: String,
                                                courseId: String,
                                                issuedCertificates: java.util.List[java.util.Map[String, String]],
                                                metrics: Metrics
                                              ): Unit = {
    val contentUrl = config.extContentUrl + courseId
    val courseMetadata = cache.getWithRetry(courseId)
    val raw =
      if (courseMetadata != null && courseMetadata.contains(config.extContentResponseKey)) {
        val contentMap =
          courseMetadata(config.extContentResponseKey).asInstanceOf[java.util.Map[String, AnyRef]]
        contentMap.get(config.competenciesV6Key)
      } else {
        val response = getExtContentAPICall(contentUrl)(config, httpUtil, metrics)
        response.get(config.competenciesV6Key)
      }
    if (raw == null) {
      logger.warn(s"processExtCourseForFirstTimeUser - no competencies found for ext courseId=$courseId")
      return
    }
    val competencies =
      raw.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]].asScala
    val cert =
      if (issuedCertificates != null && !issuedCertificates.isEmpty)
        issuedCertificates.get(0)
      else
        new java.util.HashMap[String, String]()
    val certificateId =
      Option(cert.get(config.certificateIdKey))
        .orElse(Option(cert.get(config.identifierKey)))
        .getOrElse("")
    val issuedDate =
      Option(cert.get(config.lastIssuedOnKey)).getOrElse("")
    competencies.foreach { comp =>
      val areaId = comp.getOrDefault(config.competencyAreaIdentifierKey, "").toString
      val themeId = comp.getOrDefault(config.competencyThemeIdentifierKey, "").toString
      val subthemeId = comp.getOrDefault(config.competencySubThemeIdentifierKey, "").toString
      val detailsMap = Map(
        config.acquiredContextIdKey -> courseId,
        config.certificateIdKey -> certificateId,
        config.acquiredAt -> issuedDate
      )
      upsertUserCompetencyByContext(
        userId,
        areaId,
        themeId,
        subthemeId,
        detailsMap,
        config.extCoursesContextType,
        config.dbName + "." + config.userCompetencyTable,
        metrics
      )
    }
  }

  // New function for processing user-competency-mapping-event
    private def processAchievementEvent(event: Event, metrics: Metrics): Unit = {
      val userId = event.userId
      logger.info(s"processAchievementEvent - userId=$userId achievementId=${event.contentId} action=${event.action}")
      val achievementId = event.contentId
      val dbName = config.dbName
      val achievementTable = s"$dbName.${config.learnerAchievementTable}"
      val userCompetencyTable = s"$dbName.${config.userCompetencyTable}"
      val query =
        s"SELECT * FROM $achievementTable WHERE userid='$userId' AND id='$achievementId' AND contexttype='achievements';"
      val rows =
        if (event.action == null || event.action.isEmpty || event.action.equalsIgnoreCase(config.update))
          cassandraUtil.find(query)
        else
          null
      var contextData: Map[String, AnyRef] = Map()
      if (rows != null && !rows.isEmpty) {
        val row = rows.get(0)
        val contextDataJson = row.getString(config.contextData)
        contextData = ScalaJsonUtil.deserialize[Map[String, AnyRef]](contextDataJson)
      }
      val uploadedDocUrl =
        contextData.getOrElse(config.uploadedDocumentUrl, "").toString
      val externallyUploaded =
        if (uploadedDocUrl.nonEmpty) config.trueValue else config.falseValue
      val certificateId =
        if (uploadedDocUrl.nonEmpty)
          uploadedDocUrl
        else
          contextData.getOrElse(config.url, "").toString
      val detailsMap: Map[String, String] = Map(
        config.acquiredContextIdKey ->
          contextData.getOrElse(config.acquiredContextIdKey, event.contentId).toString,
        config.certificateIdKey -> certificateId,
        config.acquiredAt ->
          contextData.getOrElse(config.issuedOn, "").toString,
        config.externallyUploaded -> externallyUploaded
      )
      if (event.action == null || event.action.isEmpty) {
        val competencies = contextData.get(config.competenciesV6Key) match {
          case Some(list: java.util.List[_]) =>
            list.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]].asScala.toList.map(_.asScala.toMap)
          case Some(list: List[_]) =>
            list.asInstanceOf[List[Map[String, AnyRef]]]
          case _ => List.empty[Map[String, AnyRef]]
        }
        competencies.foreach { comp =>
          val areaId = comp.getOrElse(config.competencyAreaIdentifierKey, "").toString
          val themeId = comp.getOrElse(config.competencyThemeIdentifierKey, "").toString
          val subthemeId = comp.getOrElse(config.competencySubThemeIdentifierKey, "").toString
          upsertUserCompetency(userId, areaId, themeId, subthemeId, detailsMap, userCompetencyTable, metrics)
        }
      } else if (event.action.equalsIgnoreCase(config.update)) {
        val competencyIds = event.competencyIds.asInstanceOf[List[Map[String, AnyRef]]]
        competencyIds.foreach { comp =>
          val areaId = comp.getOrElse(config.competencyAreaId, "").toString
          val themeId = comp.getOrElse(config.competencyThemeId, "").toString
          val subthemeId = comp.getOrElse(config.competencySubThemeId, "").toString
          val action = comp.getOrElse(config.action, "").toString.trim.toLowerCase
          if (action == config.removed) {
            removeAchievementFromCompetency(
              userId,
              areaId,
              themeId,
              subthemeId,
              event.contentId,
              userCompetencyTable
            )
          } else if (action == config.added) {
            upsertUserCompetency(
              userId,
              areaId,
              themeId,
              subthemeId,
              detailsMap,
              userCompetencyTable,
              metrics
            )
          }
        }
      } else if (event.action.equalsIgnoreCase(config.delete)) {
        val competencyIds = event.competencyIds
        competencyIds.foreach { comp =>
          val areaId = comp(config.competencyAreaId).toString
          val themeId = comp(config.competencyThemeId).toString
          val subthemeId = comp(config.competencySubThemeId).toString
          removeAchievementFromCompetency(
            userId,
            areaId,
            themeId,
            subthemeId,
            event.contentId,
            userCompetencyTable
          )
        }
      }
    }

    private def upsertUserCompetency(
                                      userId: String,
                                      areaId: String,
                                      themeId: String,
                                      subthemeId: String,
                                      detailsMap: Map[String, String],
                                      userCompetencyTable: String,
                                      metrics: Metrics
                                    ): Unit = {

      logger.debug(s"upsertUserCompetency - selecting competency_details for userId=$userId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
      val selectQuery =
        s"SELECT competency_details FROM $userCompetencyTable WHERE user_id='$userId' AND competency_area_id='$areaId' AND competency_theme_id='$themeId' AND competency_subtheme_id='$subthemeId';"
      val existingRows = cassandraUtil.find(selectQuery)
      var competencyDetails: Map[String, List[Map[String, String]]] = Map()

      if (existingRows != null && !existingRows.isEmpty) {

        import com.google.common.reflect.TypeToken
        import scala.collection.JavaConverters._

        val typeToken =
          new TypeToken[java.util.Map[String, java.util.List[java.util.Map[String, String]]]]() {}

        val detailsObj =
          existingRows.get(0).get(config.competencyDetails, typeToken)

        if (detailsObj != null) {

          competencyDetails =
            detailsObj.asScala.map { case (k, v) =>
              k -> v.asScala.toList.map(_.asScala.toMap)
            }.toMap

        }
      }
      val updatedList =
        competencyDetails
          .getOrElse(config.selfAchievement, List())
          .filterNot(_(config.acquiredContextIdKey) == detailsMap(config.acquiredContextIdKey)) :+ detailsMap

      val updatedDetails =
        competencyDetails + (config.selfAchievement -> updatedList)
      val cqlDetails = toCqlMap(updatedDetails)
      val upsertQuery =
        s"""
         INSERT INTO $userCompetencyTable
         (user_id, competency_area_id, competency_theme_id,
          competency_subtheme_id, competency_details)
         VALUES
         ('$userId', '$areaId', '$themeId', '$subthemeId', $cqlDetails);
       """
      logger.info(s"upsertUserCompetency - upserting selfAchievement for userId=$userId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
      cassandraUtil.upsert(upsertQuery)
      metrics.incCounter(config.dbUpdateCount)
    }

    private def removeAchievementFromCompetency(
                                                 userId: String,
                                                 areaId: String,
                                                 themeId: String,
                                                 subthemeId: String,
                                                 contentId: String,
                                                 userCompetencyTable: String
                                               ): Unit = {
      logger.debug(s"removeAchievementFromCompetency - selecting competency_details for userId=$userId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
      val selectQuery =
        s"SELECT competency_details FROM $userCompetencyTable WHERE user_id='$userId' AND competency_area_id='$areaId' AND competency_theme_id='$themeId' AND competency_subtheme_id='$subthemeId';"
      val existingRows = cassandraUtil.find(selectQuery)
      var competencyDetails: Map[String, List[Map[String, String]]] = Map()
      if (existingRows != null && !existingRows.isEmpty) {
        val typeToken =
          new TypeToken[java.util.Map[String,
            java.util.List[java.util.Map[String, String]]]]() {}

        val detailsObj =
          existingRows.get(0).get(config.competencyDetails, typeToken)

        if (detailsObj != null) {
          competencyDetails =
            detailsObj.asScala.map { case (k, v) =>
              k -> v.asScala.toList.map(_.asScala.toMap)
            }.toMap
        }
        if (detailsObj != null) {
          val competencyDetails =
            detailsObj.asScala.map { case (k, v) =>
              k -> v.asScala.toList.map(_.asScala.toMap)
            }.toMap
          val updatedList =
            competencyDetails
              .getOrElse(config.selfAchievement, List())
              .filterNot(_(config.acquiredContextIdKey) == contentId)
          if (updatedList.isEmpty) {
            val deleteQuery =
              s"""
               DELETE FROM $userCompetencyTable
               WHERE user_id='$userId'
               AND competency_area_id='$areaId'
               AND competency_theme_id='$themeId'
               AND competency_subtheme_id='$subthemeId';
             """
            logger.info(s"removeAchievementFromCompetency - deleting competency row for userId=$userId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
            cassandraUtil.upsert(deleteQuery)
          } else {
            val updatedDetails =
              competencyDetails + (config.selfAchievement -> updatedList)
            val cqlDetails = toCqlMap(updatedDetails)
            val upsertQuery =
              s"""
               INSERT INTO $userCompetencyTable
               (user_id, competency_area_id, competency_theme_id,
                competency_subtheme_id, competency_details)
               VALUES
               ('$userId', '$areaId', '$themeId', '$subthemeId', $cqlDetails);
             """
            logger.info(s"removeAchievementFromCompetency - updating competency_details for userId=$userId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
            cassandraUtil.upsert(upsertQuery)
          }
        }
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
      if (result.contains(config.extContentResponseKey)) {
        val scalaMap = result(config.extContentResponseKey).asInstanceOf[Map[String, AnyRef]]
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
    competencies.asScala.foreach { comp =>
      val areaId = comp.getOrDefault(config.competencyAreaIdentifierKey, "").toString
      val themeId = comp.getOrDefault(config.competencyThemeIdentifierKey, "").toString
      val subthemeId = comp.getOrDefault(config.competencySubThemeIdentifierKey, "").toString
      val description = comp.getOrDefault("competencyAreaDescription", "").toString
      val newDetail: Map[String, String] = Map(
        "acquiredContextId" -> courseId,
        "certificateId"     -> certificateId,
        config.acquiredAt       -> issuedDate
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
    import scala.collection.JavaConverters._
    val raw =
      if (courseMetadata != null && courseMetadata.contains(config.extContentResponseKey)) {
        val contentMap =
          courseMetadata(config.extContentResponseKey).asInstanceOf[java.util.Map[String, AnyRef]]
        val competenciesValue = contentMap.get(config.competenciesV6Key)
        competenciesValue match {
          case javaCompetenciesList: java.util.List[_] =>
            javaCompetenciesList
          case scalaCompetenciesSeq: scala.collection.Seq[_] =>
            scalaCompetenciesSeq.asJava
          case null =>
            logger.warn(
              s"Key '${config.competenciesV6Key}' found but value is null for courseId=$courseId"
            )
            new java.util.ArrayList[java.util.Map[String, AnyRef]]()
          case other =>
            logger.warn(
              s"Key '${config.competenciesV6Key}' found but value type is invalid: ${other.getClass.getName} for courseId=$courseId"
            )
            new java.util.ArrayList[java.util.Map[String, AnyRef]]()
        }
      } else {
        logger.warn(
          s"Key '${config.extContentResponseKey}' not found in courseMetadata for courseId=$courseId. Falling back to API call."
        )
        val response = getExtContentAPICall(contentUrl)(config, httpUtil, metrics)
        response.get(config.competenciesV6Key)
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
    val externalEnrolDb = config.extContentUserExternalEnrolmentsDb
    val externalEnrolTable = config.extContentUserExternalEnrolmentsTable
    val externalEnrolQuery =
      s"""
         SELECT issued_certificates
         FROM $externalEnrolDb.$externalEnrolTable
         WHERE userid='$userId'
         AND courseid='$courseId';
       """
    import scala.collection.JavaConverters._
    import com.google.common.reflect.TypeToken
    val enrolmentRows = cassandraUtil.find(externalEnrolQuery)
    var issuedCertificates: List[java.util.Map[String, String]] = List.empty
    if (enrolmentRows != null && !enrolmentRows.isEmpty) {
      val row = enrolmentRows.get(0)
      val issuedCertificatesKey = config.extContentUserExternalEnrolmentsIssuedCertificatesKey
      val certsRaw = row.getObject(issuedCertificatesKey).asInstanceOf[java.util.List[java.util.Map[String, String]]]
      if (certsRaw != null) {
        issuedCertificates ++= certsRaw.asScala.toList
      }
    }
    // Add logic to pick version v2 if present, else first
    val certMapOpt =
      issuedCertificates.find(c => Option(c.get(config.enrolmentsCertificateVersionKey)).exists(_.equalsIgnoreCase(config.certificateVersion2Value)))
        .orElse(issuedCertificates.headOption)
    val certificateId = certMapOpt.flatMap(c => Option(c.get(config.certificateIdKey)).orElse(Option(c.get(config.identifierKey)))).getOrElse("")
    val issuedDate = certMapOpt.flatMap(c => Option(c.get(config.lastIssuedOnKey))).getOrElse("")
    competencies.asScala.foreach { comp =>
      val areaId = comp.getOrDefault(config.competencyAreaIdentifierKey, "").toString
      val themeId = comp.getOrDefault(config.competencyThemeIdentifierKey, "").toString
      val subthemeId = comp.getOrDefault(config.competencySubThemeIdentifierKey, "").toString
      val newDetail: Map[String, String] = Map(
        config.acquiredContextIdKey -> courseId,
        config.certificateIdKey     -> certificateId,
        config.acquiredAt       -> issuedDate
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
          row.get(config.competencyDetails, typeToken)

        if (detailsObj != null) {
          competencyDetails =
            detailsObj.asScala.map { case (k, v) =>
              k -> v.asScala.toList.map(_.asScala.toMap)
            }.toMap
        }
      }
      val updatedList =
        competencyDetails
          .getOrElse(config.extCoursesContextType, List())
          .filterNot(_(config.acquiredContextIdKey) == courseId) :+ newDetail
      val updatedDetails =
        competencyDetails + (config.extCoursesContextType-> updatedList)
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
