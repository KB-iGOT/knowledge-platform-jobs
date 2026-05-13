package org.sunbird.job.usercompetencyupdate.functions

import com.datastax.driver.core.querybuilder.QueryBuilder
import com.datastax.driver.core.{ConsistencyLevel, SimpleStatement}
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

import java.util.UUID
import scala.collection.JavaConverters._

class UserCompetencyPreProcessorFn(config: UserCompetencyUpdaterConfig, httpUtil: HttpUtil)
  (implicit val stringTypeInfo: TypeInformation[String],
   @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessKeyedFunction[String, Event, String](config) {

  private[this] val logger = LoggerFactory.getLogger(classOf[UserCompetencyPreProcessorFn])
  private var cache: DataCache = _

  @transient private var courseInfoCache: java.util.concurrent.ConcurrentHashMap[String, (java.util.Map[String, AnyRef], Long)] = _
  @transient private var extContentInfoCache: java.util.concurrent.ConcurrentHashMap[String, (java.util.Map[String, AnyRef], Long)] = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    if (cassandraUtil == null)
      cassandraUtil = new CassandraUtil(
        config.dbHost,
        config.dbPort,
        config.cassandraReadTimeoutMs,
        config.cassandraConnectTimeoutMs,
        config.cassandraMaxRetries
      )
    val redisConnect = new RedisConnect(config)
    cache = new DataCache(config, redisConnect, config.collectionCacheStore, List())
    cache.init()
    courseInfoCache    = new java.util.concurrent.ConcurrentHashMap[String, (java.util.Map[String, AnyRef], Long)]()
    extContentInfoCache = new java.util.concurrent.ConcurrentHashMap[String, (java.util.Map[String, AnyRef], Long)]()
  }

  override def close(): Unit = {
    cassandraUtil.close()
    if (cache != null) cache.close()
    super.close()
  }

  override def metricsList(): List[String] = {
    List(config.totalEventsCount, config.dbReadCount, config.dbUpdateCount, config.failedEventCount, config.skippedEventCount, config.successEventCount)
  }

  override def processElement(event: Event,
                              context: KeyedProcessFunction[String, Event, String]#Context,
                              metrics: Metrics): Unit = {

      logger.info(s"processElement - received event: userId=${event.userId}, contextType=${event.contextType}, contentId=${event.contentId}")
      if (event.isFirstTimeUser != null && event.isFirstTimeUser) {
        try {
          processFirstTimeUser(event, metrics)
        } catch {
          case ex: Exception =>
            metrics.incCounter(config.failedEventCount)
            context.output(config.generateCompetencyFailedOutputTag, generateFailedEvent(event.userId, event.batchId, event.contentId))
            logger.error("Error processing first time user event: " + ex.getMessage, ex)
        }
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
  }

  private def processExternalTraining(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    val courseId = event.contentId
    val batchId = event.batchId

    // Fetch certificates (separate method)
    val issuedCertificates = fetchIssuedCertificates(userId, courseId, batchId)

    if (issuedCertificates.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      logger.error(s"No issued_certificates found for userId=$userId")
      return
    }

    //  Extract certificate details
    val (certificateId, issuedDate) =
      extractCertificateDetails(issuedCertificates)

    if (certificateId.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      return
    }

    //  Fetch course competencies
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

    val newDetail = Map(
      config.acquiredContextIdKey -> courseId,
      config.certificateIdKey     -> certificateId,
      config.acquiredAt           -> issuedDate
    )

    // Pass full list — upsertCompetencyWithFetch fetches all DB rows once and loops internally
    upsertCompetencyWithFetch(
      userId,
      competencies.asScala.toList.map(_.asScala.toMap),
      newDetail,
      config.externalTraining,
      metrics
    )
  }

  private def processFirstTimeUser(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    logger.info(s"processFirstTimeUser - starting for userId=$userId")
    fetchUserEnrollments(userId, metrics)
    processUserExtCourses(userId, metrics)
  }

  private def fetchUserEnrollments(userId: String, metrics: Metrics): Unit = {

    import scala.collection.JavaConverters._

    val cql = QueryBuilder
      .select(config.courseid, config.status, config.issuedCertificatesKey)
      .from(config.coursesdb, config.enrolmentTable)
      .where(QueryBuilder.eq("userid", userId))

    logger.info(s"fetchUserEnrollments - querying for userId=$userId" +
      s" table=${config.coursesdb}.${config.enrolmentTable}")
    val stmt = new SimpleStatement(cql.toString)
      .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    val rows = cassandraUtil.findAllWithStatement(stmt)
    //val rows = cassandraUtil.find(cql.toString)

    if (rows == null || rows.isEmpty) {
      logger.debug(s"fetchUserEnrollments - no enrollments found for userId=$userId")
      return
    }

    logger.info(s"fetchUserEnrollments - total rows=${rows.size()} for userId=$userId (will filter status=2)")

    // TypeToken for list<frozen<map<text,text>>> — created once, reused per row
    val certsTypeToken = new TypeToken[java.util.Map[String, String]]() {}

    rows.asScala
      .filter(row => row.getInt(config.status) == 2)
      .foreach { row =>
        val courseId = row.getString(config.courseid)

        // (cont.) — row.getList with TypeToken is null-safe: returns null
        val certsRaw = row.getList(config.issuedCertificatesKey, certsTypeToken)
        val issuedCerts: java.util.List[java.util.Map[String, String]] =
          if (certsRaw != null) certsRaw
          else java.util.Collections.emptyList[java.util.Map[String, String]]()

        // Build the enrolment map that processCourse expects.
        // Only courseid and issuedCertificatesKey are actually read by processCourse.
        val enrolment: Map[String, AnyRef] = Map(
          config.courseid              -> courseId,
          config.status                -> java.lang.Integer.valueOf(2),
          config.issuedCertificatesKey -> issuedCerts
        )

        try {
          processCourse(userId, enrolment, metrics)
        } catch {
          case ex: Exception =>
            metrics.incCounter(config.failedEventCount)
            logger.error(
              s"fetchUserEnrollments - data error processing courseId=$courseId" +
                s" userId=$userId — skipping course. Cause: ${ex.getClass.getSimpleName}: ${ex.getMessage}",
              ex
            )
        }
      }
  }

  private def processCourse(
                             userId: String,
                             enrolment: Map[String, AnyRef],
                             metrics: Metrics
                           ): Unit = {

    import scala.collection.JavaConverters._

    val courseId = enrolment(config.courseid).toString
    logger.debug(s"processCourse - userId=$userId courseId=$courseId")

    val courseInfo = getCourseInfo(courseId)(metrics, config, cache, httpUtil)

    val competencies =
      courseInfo
        .get(config.competenciesV6Key)
        .asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
        .asScala
        .toList
        .map(_.asScala.toMap)

    //  GUARD — getCourseInfo returns an empty ArrayList (never null) when the
    //    content API has no competencies_v6 for this course.  Without this early
    if (competencies.isEmpty) {
      logger.warn(
        s"processCourse - courseId=$courseId has no '${config.competenciesV6Key}'" +
          s" (course may not be competency-mapped) — skipping for userId=$userId"
      )
      metrics.incCounter(config.skippedEventCount)
      return
    }
    logger.info(s"processCourse - courseId=$courseId has ${competencies.size} competencies for userId=$userId")

    var certificateId = ""
    var acquiredAt = ""

    // — enrolment.get(key) returns Some(null) when the key is present but
    //    the Cassandra column was unset (rowToMap maps null AnyRef values).
    //    The old code called null.asInstanceOf[...].asScala.toList → NullPointerException.
    //    Option(value) wraps null → None so the match is exhaustive and safe.
    val certsList: List[java.util.Map[String, String]] =
      enrolment.getOrElse(config.issuedCertificatesKey, null) match {
        case list: java.util.List[_] if list != null =>
          list.asInstanceOf[java.util.List[java.util.Map[String, String]]].asScala.toList
        case _ => List.empty
      }

    val certMapOpt =
      certsList
        .find(c => Option(c.get(config.enrolmentsCertificateVersionKey))
          .exists(_.equalsIgnoreCase(config.certificateVersion2Value)))
        .orElse(certsList.headOption)

    certMapOpt.foreach { c =>
      certificateId = Option(c.get(config.certificateIdKey))
        .orElse(Option(c.get(config.identifierKey)))
        .map(_.toString).getOrElse("")
      acquiredAt = Option(c.get(config.lastIssuedOnKey))
        .map(_.toString).getOrElse("")
    }

    val detailsMap = Map(
      config.acquiredContextIdKey -> courseId,
      config.certificateIdKey     -> certificateId,
      config.acquiredAt           -> acquiredAt
    )

    // Pass full list — upsertCompetencyWithFetch fetches all DB rows once and loops internally
    upsertCompetencyWithFetch(userId, competencies, detailsMap, config.iGOTCourses, metrics)
  }



  private def processUserExtCourses(userId: String, metrics: Metrics): Unit = {
    logger.debug(s"processUserExtCourses - starting for userId=$userId")

    val certsKey = config.extContentUserExternalEnrolmentsIssuedCertificatesKey
    val certsTypeToken = new TypeToken[java.util.Map[String, String]]() {}

    val query = QueryBuilder
      .select(config.courseid, config.status, certsKey)
      .from(config.extContentUserExternalEnrolmentsDb, config.extContentUserExternalEnrolmentsTable)
      .where(QueryBuilder.eq("userid", userId))

    val stmt = new SimpleStatement(query.toString)
      .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    val rows = cassandraUtil.findAllWithStatement(stmt)

    //val rows = cassandraUtil.find(query)
    if (rows == null || rows.isEmpty) return

    rows.asScala
      .filter(_.getInt(config.status) == 2)
      .foreach { row =>
        val courseId = row.getString(config.courseid)
        val issuedCertificates =
          Option(row.getList(certsKey, certsTypeToken))
            .fold(List.empty[java.util.Map[String, String]])(_.asScala.toList)
        try {
          // upserts directly — no sharedCache needed
          accumulateExtCourseIntoCache(userId, courseId, issuedCertificates, metrics)
        } catch {
          case ex: Exception =>
            metrics.incCounter(config.failedEventCount)
            logger.error(s"Error processing ext course userId=$userId courseId=$courseId", ex)
        }
      }
  }


  private def accumulateExtCourseIntoCache(
                                            userId: String,
                                            courseId: String,
                                            issuedCertificates: List[java.util.Map[String, String]],
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
      logger.warn(s"accumulateExtCourseIntoCache - no competencies found for ext courseId=$courseId")
      return
    }

    val competencies =
      raw.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]].asScala.toList
    if (competencies.isEmpty) return

    val certMapOpt =
      issuedCertificates
        .find(c => Option(c.get(config.enrolmentsCertificateVersionKey))
          .exists(_.equalsIgnoreCase(config.certificateVersion2Value)))
        .orElse(issuedCertificates.headOption)

    val certificateId =
      certMapOpt
        .flatMap(c => Option(c.get(config.certificateIdKey)).orElse(Option(c.get(config.identifierKey))))
        .getOrElse("")
    val issuedDate =
      certMapOpt.flatMap(c => Option(c.get(config.lastIssuedOnKey))).getOrElse("")

    val newDetail: Map[String, String] = Map(
      config.acquiredContextIdKey -> courseId,
      config.certificateIdKey     -> certificateId,
      config.acquiredAt           -> issuedDate
    )

    // Pass full list — upsertCompetencyWithFetch fetches all DB rows once and loops internally
    upsertCompetencyWithFetch(
      userId,
      competencies.map(_.asScala.toMap),
      newDetail,
      config.extCoursesContextType,
      metrics
    )
  }



  private def processAchievementEvent(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    logger.debug(s"processAchievementEvent - userId=$userId achievementId=${event.contentId} action=${event.action}")

    val isCreateAction = event.action == null || event.action.isEmpty
    val isUpdateAction = !isCreateAction && event.action.equalsIgnoreCase(config.update)
    val isDeleteAction = !isCreateAction && event.action.equalsIgnoreCase(config.delete)

    //  Fetch contextData
    val contextData =
      if (isCreateAction || isUpdateAction)
        fetchAchievementContext(userId, event.contentId)
      else Map.empty[String, AnyRef]

    // Build detailsMap
    val detailsMap =
      if (isCreateAction || isUpdateAction)
        buildAchievementDetailsMap(contextData, event)
      else Map.empty[String, String]

    if (isCreateAction) {
      //CREATE FLOW
      val competencies = extractCompetencies(contextData)
      // Pass full list — upsertCompetencyWithFetch fetches all DB rows once and loops internally
      upsertCompetencyWithFetch(userId, competencies, detailsMap, config.selfAchievement, metrics)

    } else if (isUpdateAction) {
      // UPDATE FLOW — separate removed vs added, call upsertCompetencyWithFetch once for all added
      val competencyIds = event.competencyIds

      // Process removals individually (each needs its own targeted delete)
      competencyIds
        .filter(_.getOrElse(config.action, "").toString.trim.toLowerCase == config.removed)
        .foreach { comp =>
          removeAchievementFromCompetency(
            userId,
            comp.getOrElse(config.competencyAreaId, "").toString,
            comp.getOrElse(config.competencyThemeId, "").toString,
            comp.getOrElse(config.competencySubThemeId, "").toString,
            event.contentId)
        }

      // Process all added competencies in a single upsertCompetencyWithFetch call (1 DB read)
      val addedCompetencies = competencyIds
        .filter(_.getOrElse(config.action, "").toString.trim.toLowerCase == config.added)
      if (addedCompetencies.nonEmpty) {
        upsertCompetencyWithFetch(userId, addedCompetencies, detailsMap, config.selfAchievement, metrics)
      }
      val changeUrlCompetencies = competencyIds
        .filter(_.getOrElse(config.action, "").toString.trim.toLowerCase == config.changeUrl)
      if (changeUrlCompetencies.nonEmpty) {
        upsertCompetencyWithFetch(userId, changeUrlCompetencies, detailsMap, config.selfAchievement, metrics)
      }
    } else if (isDeleteAction) {
      // DELETE FLOW
      event.competencyIds
        .map(comp => (
          comp(config.competencyAreaId).toString,
          comp(config.competencyThemeId).toString,
          comp(config.competencySubThemeId).toString
        ))
        .distinct
        .foreach { case (areaId, themeId, subthemeId) =>
          removeAchievementFromCompetency(userId, areaId, themeId, subthemeId, event.contentId)
        }
    }
  }

    private def removeAchievementFromCompetency(
                                                 userId: String,
                                                 areaId: String,
                                                 themeId: String,
                                                 subthemeId: String,
                                                 contentId: String
                                               ): Unit = {
      val logKey = s"userId=$userId areaId=$areaId themeId=$themeId subthemeId=$subthemeId"
      logger.debug(s"removeAchievementFromCompetency - selecting competency_details for $logKey")
      //  two-arg from(keyspace, table)
      val selectQuery = QueryBuilder
        .select(config.competencyDetails)
        .from(config.dbName, config.userCompetencyTable)
        .where(QueryBuilder.eq("user_id", userId))
        .and(QueryBuilder.eq("competency_area_id", areaId))
        .and(QueryBuilder.eq("competency_theme_id", themeId))
        .and(QueryBuilder.eq("competency_subtheme_id", subthemeId))
        .toString
      // LOCAL_QUORUM: must read latest details before modifying them
      val existingRow = cassandraUtil.findOneWithStatement(
        new SimpleStatement(selectQuery).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
      )
      if (existingRow == null) return
      val typeToken =
        new TypeToken[java.util.Map[String, java.util.List[java.util.Map[String, String]]]]() {}
      val detailsObj = existingRow.get(config.competencyDetails, typeToken)
      if (detailsObj == null) return
      val competencyDetails: Map[String, List[Map[String, String]]] =
        detailsObj.asScala.map { case (k, v) =>
          k -> v.asScala.toList.map(_.asScala.toMap)
        }.toMap
      val updatedList =
        competencyDetails
          .getOrElse(config.selfAchievement, List())
          .filterNot(_(config.acquiredContextIdKey) == contentId)
      if (updatedList.isEmpty) {
        // ─── selfAchievement is now empty → DELETE the entire row
        // two-arg delete().from(keyspace, table)
        val deleteQuery = QueryBuilder.delete()
          .from(config.dbName, config.userCompetencyTable)
          .where(QueryBuilder.eq("user_id", userId))
          .and(QueryBuilder.eq("competency_area_id", areaId))
          .and(QueryBuilder.eq("competency_theme_id", themeId))
          .and(QueryBuilder.eq("competency_subtheme_id", subthemeId))
        deleteQuery.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        logger.info(s"removeAchievementFromCompetency - deleting competency row for $logKey")
        cassandraUtil.upsert(deleteQuery.toString)
      } else {
        val updatedDetails = competencyDetails + (config.selfAchievement -> updatedList)
        val detailsJavaMap: java.util.Map[String, java.util.List[java.util.Map[String, String]]] =
          updatedDetails.map { case (k, v) => k -> v.map(_.asJava).asJava }.asJava

        val insertQuery = QueryBuilder.insertInto(config.dbName, config.userCompetencyTable)
          .value("user_id", userId)
          .value("competency_area_id", areaId)
          .value("competency_theme_id", themeId)
          .value("competency_subtheme_id", subthemeId)
          .value("competency_details", detailsJavaMap)
        insertQuery.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        logger.info(s"removeAchievementFromCompetency - updating competency_details for $logKey")
        cassandraUtil.upsert(insertQuery.toString)
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

    import scala.collection.JavaConverters._

    val userId   = event.userId
    val courseId = event.contentId
    val batchId  = event.batchId

    //  Fetch certificate
    val (certificateId, issuedDate) =
      getIGOTCourseCertificate(userId, courseId, batchId, metrics)

    if (certificateId.isEmpty) return

    // Fetch competencies
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

    val newDetail = Map(
      config.acquiredContextIdKey -> courseId,
      config.certificateIdKey     -> certificateId,
      config.acquiredAt           -> issuedDate
    )

    // Pass full list — upsertCompetencyWithFetch fetches all DB rows once and loops internally
    upsertCompetencyWithFetch(
      userId,
      competencies.asScala.toList.map(_.asScala.toMap),
      newDetail,
      config.iGOTCourses,
      metrics
    )
  }

  // Make getCourseInfo
  private def getCourseInfo(courseId: String)(
    metrics: Metrics,
    config: UserCompetencyUpdaterConfig,
    cache: DataCache,
    httpUtil: HttpUtil
  ): java.util.Map[String, AnyRef] = {

    //in-memory cach
    val now = System.currentTimeMillis()
    val inMemoryCourseInfo = courseInfoCache.get(courseId)
    if (inMemoryCourseInfo != null) {
      if (inMemoryCourseInfo._2 > now) {
        logger.info(s"getCourseInfo -  in-memory cache HIT for courseId=$courseId")
        return inMemoryCourseInfo._1
      }
    }
    val courseMetadata = cache.getWithRetry(courseId)
    val isRedisCacheMiss = courseMetadata == null || courseMetadata.isEmpty || !courseMetadata.contains("competenciesv6")
    val courseInfoMap: java.util.Map[String, AnyRef] = new java.util.HashMap[String, AnyRef]()
    val competencies: java.util.List[java.util.Map[String, AnyRef]] = {
      val raw = if (isRedisCacheMiss) {
        logger.info(s"getCourseInfo - calling Content API for courseId=$courseId")
        val url = config.contentReadURL + courseId + "?fields=competencies_v6"
        val response = getAPICall(url, "content")(config, httpUtil, metrics)
        response.get("competencies_v6")
      } else {
        logger.info(s"getCourseInfo - calling Redis for courseId=$courseId")
        courseMetadata.get("competenciesv6")
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
        case Some(value: java.util.List[_]) =>
          value.asScala.map {
            case jm: java.util.Map[_, _] =>
              jm.asInstanceOf[java.util.Map[String, AnyRef]]

            case sm: Map[_, _] =>
              sm.asJava.asInstanceOf[java.util.Map[String, AnyRef]]

            case other =>
              other.asInstanceOf[java.util.Map[String, AnyRef]]
          }.toList.asJava
        case _ => new java.util.ArrayList[java.util.Map[String, AnyRef]]()
      }
    }
    courseInfoMap.put("competencies_v6", competencies)
    courseInfoCache.put(courseId, (courseInfoMap, now + config.contentCacheExpiry))
    courseInfoMap
  }

  // Add new method for extCourses
  private def processExtCourses(event: Event, metrics: Metrics): Unit = {
    val userId   = event.userId
    val courseId = event.contentId

    // Get competencies
    val competencies = getExtCourseCompetencies(courseId, metrics)
    if (competencies.isEmpty) {
      logger.warn(s"No competencies found for extCourseId=$courseId")
      return
    }

    // Get certificate details
    val (certificateId, issuedDate) = getExtCourseCertificate(userId, courseId)

    val newDetail = Map(
      config.acquiredContextIdKey -> courseId,
      config.certificateIdKey     -> certificateId,
      config.acquiredAt           -> issuedDate
    )

    // No local cache — upsertCompetencyWithFetch handles fetch + compare + upsert per competency
    upsertCompetencyWithFetch(
      userId,
      competencies.asScala.toList.map(_.asScala.toMap),
      newDetail,
      config.extCoursesContextType,
      metrics
    )
  }

  private def generateFailedEvent(userId: String, batchId: String, contentId: String): String = {
    val ets = System.currentTimeMillis
    val mid = s"LP.${ets}.${UUID.randomUUID}"
    val eventString = s"""{"eid": "BE_JOB_REQUEST", "ets": $ets, "mid": "$mid", "actor": {"id": "Program Certificate Pre Processor Generator", "type": "System"}, "context": {"pdata": {"ver": "1.0", "id": "org.sunbird.platform"}}, "object": {"id": "${batchId}_${contentId}", "type": "ProgramCertificatePreProcessorGeneration"}, "edata": {"userId": "$userId", "action": "program-issue-certificate", "iteration": 1, "trigger": "auto-issue", "batchId": "$batchId", "parentCollections": ["$contentId"], "courseId": "$contentId"}}"""
    eventString
  }

  /**
   * 1 SELECT (LOCAL_QUORUM) + N upserts (LOCAL_QUORUM).* N = number of competencies in the event, typically 1 for achievement events and 1-5 for course events.
   * This method we fetched the competency and  update the competency details with new details and then upsert the data into user competency table
    *
   */
  private def upsertCompetencyWithFetch(
                                         userId: String,
                                         competencies: List[Map[String, AnyRef]],
                                         newDetail: Map[String, String],
                                         contextType: String,
                                         metrics: Metrics
                                       ): Unit = {

    logger.info(s"upsertCompetencyWithFetch - ENTRY userId=$userId contextType=$contextType" +
      s" competencyCount=${competencies.size}" +
      s" acquiredContextId=${newDetail.getOrElse(config.acquiredContextIdKey, "")}")

    if (competencies.isEmpty) {
      logger.warn(s"upsertCompetencyWithFetch - competencies list is empty for userId=$userId contextType=$contextType, skipping")
      return
    }

    val selectQuery = QueryBuilder
      .select("competency_area_id", "competency_theme_id", "competency_subtheme_id",
        config.competencyDetails)
      .from(config.dbName, config.userCompetencyTable)
      .where(QueryBuilder.eq("user_id", userId))

    // SELECT all competency rows for this user (WHERE user_id only — not full PK → multiple rows)
    val stmt = new SimpleStatement(selectQuery.toString)
      .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    val rows = cassandraUtil.findAllWithStatement(stmt)
    //val rows = cassandraUtil.find(selectQuery)

    val typeToken =
      new TypeToken[java.util.Map[String, java.util.List[java.util.Map[String, String]]]]() {}

    // ── Build lookup map keyed by (areaId, themeId, subthemeId) ─────────────────
    val existingDetailsMap: Map[(String, String, String), Map[String, List[Map[String, String]]]] =
      if (rows == null || rows.isEmpty) Map.empty
      else rows.asScala.flatMap { row =>
        val key = (
          row.getString("competency_area_id"),
          row.getString("competency_theme_id"),
          row.getString("competency_subtheme_id")
        )
        val detailsObj = row.get(config.competencyDetails, typeToken)
        val details =
          if (detailsObj == null) Map.empty[String, List[Map[String, String]]]
          else detailsObj.asScala.map { case (k, v) =>
            k -> v.asScala.toList.map(_.asScala.toMap)
          }.toMap
        Some(key -> details)
      }.toMap

    // ── Loop competencies in memory — no extra DB reads ──────────────────────────
    competencies.foreach { competency =>

      // Try "identifier" key first (course flows), fall back to "Id" key (achievement update flow)
      val areaId = competency
        .getOrElse(config.competencyAreaIdentifierKey,
          competency.getOrElse(config.competencyAreaId, "")).toString
      val themeId = competency
        .getOrElse(config.competencyThemeIdentifierKey,
          competency.getOrElse(config.competencyThemeId, "")).toString
      val subthemeId = competency
        .getOrElse(config.competencySubThemeIdentifierKey,
          competency.getOrElse(config.competencySubThemeId, "")).toString

      if (areaId.isEmpty || themeId.isEmpty || subthemeId.isEmpty) {
        logger.warn(s"upsertCompetencyWithFetch - skipping empty key userId=$userId" +
          s" area='$areaId' theme='$themeId' subtheme='$subthemeId'")
      } else {
        // ── In-memory lookup — NO extra DB read ───────────────────────────────────
        val existingDetails = existingDetailsMap.getOrElse(
          (areaId, themeId, subthemeId),
          Map.empty[String, List[Map[String, String]]]
        )

        // ── Merge: remove duplicate by acquiredContextId, then append newDetail ──
        val updatedList =
          existingDetails
            .getOrElse(contextType, List())
            .filterNot(_(config.acquiredContextIdKey) == newDetail(config.acquiredContextIdKey)) :+ newDetail

        upsertCompetency(userId, areaId, themeId, subthemeId,
          existingDetails + (contextType -> updatedList), metrics)
      }
    }
  }

  private def upsertCompetency(
                                userId: String,
                                areaId: String,
                                themeId: String,
                                subthemeId: String,
                                updatedDetails: Map[String, List[Map[String, String]]],
                                metrics: Metrics
                              ): Unit = {

    import scala.collection.JavaConverters._

    val detailsJavaMap: java.util.Map[
      String,
      java.util.List[java.util.Map[String, String]]
    ] =
      updatedDetails.map { case (k, v) =>
        k -> v.map(_.asJava).asJava
      }.asJava

    val insertQuery = QueryBuilder
      .insertInto(config.dbName, config.userCompetencyTable)
      .value("user_id", userId)
      .value("competency_area_id", areaId)
      .value("competency_theme_id", themeId)
      .value("competency_subtheme_id", subthemeId)
      .value("competency_details", detailsJavaMap)

    insertQuery.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    cassandraUtil.update(insertQuery)

    metrics.incCounter(config.dbUpdateCount)
  }

  private def fetchIssuedCertificates(
                                       userId: String,
                                       courseId: String,
                                       batchId: String
                                     ): List[java.util.Map[String, String]] = {

    val query = QueryBuilder
      .select(config.issuedCertificatesKey)
      .from(config.coursesdb, config.userEntityEnrolmentsTable)
      .where(QueryBuilder.eq("userid", userId))
      .and(QueryBuilder.eq("contextid", courseId))
      .and(QueryBuilder.eq("batchid", batchId))
      .toString

    // LOCAL_QUORUM: ensure we read the latest certificate data
    val row = cassandraUtil.findOneWithStatement(
      new SimpleStatement(query).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    )

    if (row == null) return List.empty

    val certsRaw = row.getList(
      config.issuedCertificatesKey,
      new TypeToken[java.util.Map[String, String]]() {}
    )

    if (certsRaw != null) certsRaw.asScala.toList else List.empty
  }

  private def extractCertificateDetails(issuedCertificates: List[java.util.Map[String, String]]): (String, String) = {
    val certMap = issuedCertificates.headOption.orNull
    val certificateId =
      Option(certMap).flatMap(c => Option(c.get(config.identifierKey))).getOrElse("")

    val issuedDate =
      Option(certMap).flatMap(c => Option(c.get(config.lastIssuedOnKey))).getOrElse("")
    (certificateId, issuedDate)
  }

  private def getExtCourseCompetencies(
                                        courseId: String,
                                        metrics: Metrics
                                      ): java.util.List[java.util.Map[String, AnyRef]] = {

    // in-memory cache
    val now = System.currentTimeMillis()
    val inMemoryExtContentInfo = extContentInfoCache.get(courseId)
    if (inMemoryExtContentInfo != null) {
      if (inMemoryExtContentInfo._2 > now) {
        logger.info(s"getExtCourseCompetencies - in-memory cache HIT for courseId=$courseId")
        val cachedCompetenciesData = inMemoryExtContentInfo._1.get(config.competenciesV6Key)
        return cachedCompetenciesData match {
          case null => new java.util.ArrayList[java.util.Map[String, AnyRef]]()
          case jl: java.util.List[_] => jl.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
          case _ => new java.util.ArrayList[java.util.Map[String, AnyRef]]()
        }
      }
    }
    // Redis DataCache
    val courseMetadata = cache.getWithRetry(courseId)

    val rawValue: AnyRef =
      if (courseMetadata != null && courseMetadata.contains(config.extContentResponseKey)) {
        logger.info(s"getExtCourseCompetencies -  Redis HIT for courseId=$courseId")
        val contentMap =
          courseMetadata(config.extContentResponseKey).asInstanceOf[java.util.Map[String, AnyRef]]
        val competenciesRaw = contentMap.get(config.competenciesV6Key)
        val cachedCompetencyData = new java.util.HashMap[String, AnyRef]()
        cachedCompetencyData.put(config.competenciesV6Key, competenciesRaw)
        extContentInfoCache.put(courseId, (cachedCompetencyData, now + config.contentCacheExpiry))
        competenciesRaw
      } else {
        logger.info(s"getExtCourseCompetencies  calling Ext Content API for courseId=$courseId")
        val response =
          getExtContentAPICall(config.extContentUrl + courseId)(config, httpUtil, metrics)
        val competenciesRaw = response.get(config.competenciesV6Key)
        val cachedCompetencyData = new java.util.HashMap[String, AnyRef]()
        cachedCompetencyData.put(config.competenciesV6Key, competenciesRaw)
        extContentInfoCache.put(courseId, (cachedCompetencyData, now + config.contentCacheExpiry))
        competenciesRaw
      }

    rawValue match {
      case null => new java.util.ArrayList[java.util.Map[String, AnyRef]]()
      case jl: java.util.List[_] =>
        jl.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
      case s: Seq[_] =>
        s.map {
          case jm: java.util.Map[_, _] => jm.asInstanceOf[java.util.Map[String, AnyRef]]
          case sm: Map[_, _]           => sm.asJava.asInstanceOf[java.util.Map[String, AnyRef]]
          case other                   => other.asInstanceOf[java.util.Map[String, AnyRef]]
        }.toList.asJava
      case _ =>
        new java.util.ArrayList[java.util.Map[String, AnyRef]]()
    }
  }

  private def getExtCourseCertificate(
                                       userId: String,
                                       courseId: String
                                     ): (String, String) = {

    val certsKey = config.extContentUserExternalEnrolmentsIssuedCertificatesKey

    val query = QueryBuilder
      .select(certsKey)
      .from(config.extContentUserExternalEnrolmentsDb, config.extContentUserExternalEnrolmentsTable)
      .where(QueryBuilder.eq("userid", userId))
      .and(QueryBuilder.eq("courseid", courseId))
      .toString

    // LOCAL_QUORUM: ensure latest certificate is visible
    val row = cassandraUtil.findOneWithStatement(
      new SimpleStatement(query).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    )

    val issuedCertificates =
      if (row != null) {
        val certsRaw = row.getList(
          certsKey,
          new TypeToken[java.util.Map[String, String]]() {}
        )
        if (certsRaw != null) certsRaw.asScala.toList else List.empty
      } else List.empty

    val certMapOpt =
      issuedCertificates
        .find(c => Option(c.get(config.enrolmentsCertificateVersionKey))
          .exists(_.equalsIgnoreCase(config.certificateVersion2Value)))
        .orElse(issuedCertificates.headOption)

    val certificateId =
      certMapOpt
        .flatMap(c => Option(c.get(config.certificateIdKey)).orElse(Option(c.get(config.identifierKey))))
        .getOrElse("")

    val issuedDate =
      certMapOpt.flatMap(c => Option(c.get(config.lastIssuedOnKey))).getOrElse("")

    (certificateId, issuedDate)
  }

  private def getIGOTCourseCertificate(
                                        userId: String,
                                        courseId: String,
                                        batchId: String,
                                        metrics: Metrics
                                      ): (String, String) = {

    val query = QueryBuilder
      .select(config.issuedCertificatesKey)
      .from(config.coursesdb, config.enrolmentTable)
      .where(QueryBuilder.eq("userid", userId))
      .and(QueryBuilder.eq("courseid", courseId))
      .and(QueryBuilder.eq("batchid", batchId))
      .toString

    // LOCAL_QUORUM: must read the latest enrolment/certificate row
    val row = cassandraUtil.findOneWithStatement(
      new SimpleStatement(query).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    )

    if (row == null) {
      metrics.incCounter(config.failedEventCount)
      logger.error(s"No enrolment found for userId=$userId courseId=$courseId")
      return ("", "")
    }

    val certsRaw = row.getList(
      config.issuedCertificatesKey,
      new TypeToken[java.util.Map[String, String]]() {}
    )

    val issuedCertificates =
      if (certsRaw != null) certsRaw.asScala.toList else List.empty

    if (issuedCertificates.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      return ("", "")
    }

    val certMapOpt =
      issuedCertificates
        .find(c => Option(c.get("version")).exists(_.equalsIgnoreCase("v2")))
        .orElse(issuedCertificates.headOption)

    val certMap = certMapOpt.get

    val certificateId =
      Option(certMap.get("certificateId"))
        .orElse(Option(certMap.get("identifier")))
        .getOrElse("")

    val issuedDate =
      Option(certMap.get("lastIssuedOn")).getOrElse("")

    if (certificateId.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      return ("", "")
    }

    (certificateId, issuedDate)
  }

  private def fetchAchievementContext(
                                       userId: String,
                                       contentId: String
                                     ): Map[String, AnyRef] = {

    val query = QueryBuilder
      .select(config.contextData)
      .from(config.dbName, config.learnerAchievementTable)
      .where(QueryBuilder.eq("userid", userId))
      .and(QueryBuilder.eq("id", contentId))
      .and(QueryBuilder.eq("contexttype", config.achievements))
      .toString

    // LOCAL_QUORUM: read the latest achievement context before building competency details
    Option(cassandraUtil.findOneWithStatement(
      new SimpleStatement(query).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    ))
      .map(row => ScalaJsonUtil.deserialize[Map[String, AnyRef]](row.getString(config.contextData)))
      .getOrElse(Map.empty)
  }

  private def buildAchievementDetailsMap(
                                          contextData: Map[String, AnyRef],
                                          event: Event
                                        ): Map[String, String] = {

    val uploadedDocUrl = contextData.getOrElse(config.uploadedDocumentUrl, "").toString

    val externallyUploaded =
      if (uploadedDocUrl.nonEmpty) config.trueValue else config.falseValue

    val certificateId =
      if (uploadedDocUrl.nonEmpty) uploadedDocUrl
      else contextData.getOrElse(config.url, "").toString

    Map(
      config.acquiredContextIdKey ->
        contextData.getOrElse(config.acquiredContextIdKey, event.contentId).toString,
      config.certificateIdKey   -> certificateId,
      config.acquiredAt         -> contextData.getOrElse(config.issuedOn, "").toString,
      config.externallyUploaded -> externallyUploaded
    )
  }

  private def extractCompetencies(
                                   contextData: Map[String, AnyRef]
                                 ): List[Map[String, AnyRef]] = {

    contextData.get(config.competenciesV6Key) match {
      case Some(list: java.util.List[_]) =>
        list.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
          .asScala.toList.map(_.asScala.toMap)
      case Some(list: List[_]) =>
        list.asInstanceOf[List[Map[String, AnyRef]]]
      case _ => List.empty
    }
  }
}
