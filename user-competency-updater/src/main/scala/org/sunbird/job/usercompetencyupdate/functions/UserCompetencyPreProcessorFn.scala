package org.sunbird.job.usercompetencyupdate.functions

import com.datastax.driver.core.{ConsistencyLevel, Row}
import com.datastax.driver.core.querybuilder.{Delete, QueryBuilder, Select}
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

import java.util.UUID
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

    // ─ Fetch issued_certificates ────────
    val enrolmentQuery = QueryBuilder
      .select(config.issuedCertificatesKey)
      .from(config.coursesdb, config.userEntityEnrolmentsTable)
      .where(QueryBuilder.eq("userid", userId))
      .and(QueryBuilder.eq("contextid", courseId))
      .and(QueryBuilder.eq("batchid", batchId))
      .toString

    val enrolmentRow = cassandraUtil.findOne(enrolmentQuery)
    if (enrolmentRow == null) {
      metrics.incCounter(config.failedEventCount)
      logger.error(s"No enrolment found for userId=$userId courseId=$courseId")
      return
    }

    //OPT: extract directly from single row — replaces var + Java for-loop
    val certsRaw = enrolmentRow.getList(
      config.issuedCertificatesKey,
      new TypeToken[java.util.Map[String, String]]() {}
    )
    val issuedCertificates: List[java.util.Map[String, String]] =
      if (certsRaw != null) certsRaw.asScala.toList else List.empty

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

    // ─── Step 3: Fetch course competencies ──────
    val courseMetadata = getCourseInfo(courseId)(metrics, config, cache, httpUtil)
    val competencies =
      courseMetadata
        .getOrDefault(config.competenciesV6Key,
          new java.util.ArrayList[java.util.Map[String, AnyRef]]())
        .asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
    if (competencies.isEmpty) {
      logger.warn(s"No competencies found for courseId=$courseId")
      return
    }

    // ─── Step 4: Accumulate + write competency updates ────────
    val newDetail: Map[String, String] = Map(
      config.acquiredContextIdKey -> courseId,
      config.certificateIdKey     -> certificateId,
      config.acquiredAt           -> issuedDate
    )

    val localCache =
      scala.collection.mutable.Map[
        (String, String, String),
        Map[String, List[Map[String, String]]]
      ]()

    competencies.asScala.foreach { comp =>
      val areaId     = comp.getOrDefault(config.competencyAreaIdentifierKey, "").toString
      val themeId    = comp.getOrDefault(config.competencyThemeIdentifierKey, "").toString
      val subthemeId = comp.getOrDefault(config.competencySubThemeIdentifierKey, "").toString
      val cacheKey   = (areaId, themeId, subthemeId)

      val existingDetails = localCache.getOrElseUpdate(
        cacheKey, fetchCompetencyFromDB(userId, areaId, themeId, subthemeId)
      )

      val updatedList =
        existingDetails
          .getOrElse(config.externalTraining, List())
          .filterNot(_(config.acquiredContextIdKey) == newDetail(config.acquiredContextIdKey)) :+ newDetail

      localCache.put(cacheKey, existingDetails + (config.externalTraining -> updatedList))
    }

    // write once per unique tuple — preserves original single metrics.incCounter per event
    localCache.foreach { case ((areaId, themeId, subthemeId), details) =>
      logger.debug(s"Upserting externalTraining competency courseId=$courseId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
      val detailsJavaMap: java.util.Map[String, java.util.List[java.util.Map[String, String]]] =
        details.map { case (k, v) => k -> v.map(_.asJava).asJava }.asJava
      // ✅ FIX: two-arg insertInto(keyspace, table)
      val insertQuery = QueryBuilder.insertInto(config.dbName, config.userCompetencyTable)
        .value("user_id", userId)
        .value("competency_area_id", areaId)
        .value("competency_theme_id", themeId)
        .value("competency_subtheme_id", subthemeId)
        .value("competency_details", detailsJavaMap)
      insertQuery.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)

      cassandraUtil.upsert(insertQuery.toString)
    }

    metrics.incCounter(config.dbUpdateCount)
  }

  private def processFirstTimeUser(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    logger.info(s"processFirstTimeUser - starting for userId=$userId")
    fetchUserEnrollments(userId, metrics)
    processUserExtCourses(userId, metrics)
  }

  /*private def fetchUserEnrollments(userId: String, metrics: Metrics): Unit = {
    val batchSize = config.firstTimeUserFetchLimit
    var lastCourseId: String = null
    var lastBatchId: String = null
    var hasMore = true
    logger.info(s"fetchUserEnrollments - userId=$userId batchSize=$batchSize")
    while (hasMore) {
      val query = if (lastCourseId == null)
        QueryBuilder.select("courseid", "batchid", "status", config.issuedCertificatesKey)
          .from(config.coursesdb, config.enrolmentTable)
          .where(QueryBuilder.eq("userid", userId))
          .limit(batchSize)
          .toString
      else
        QueryBuilder.select("courseid", "batchid", "status", config.issuedCertificatesKey)
          .from(config.coursesdb, config.enrolmentTable)
          .where(QueryBuilder.eq("userid", userId))
          .and(QueryBuilder.gt("courseid", lastCourseId))
          .limit(batchSize)
          .toString
      logger.debug(s"fetchUserEnrollments - generated query: $query")
      val rows = cassandraUtil.find(query)
      if (rows == null || rows.isEmpty) {
        //TODO change logger info to debug.
        logger.info(s"fetchUserEnrollments - no rows for userId=$userId lastCourseId=$lastCourseId lastBatchId=$lastBatchId")
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
        //TODO change logger info to debug.
        logger.info(s"fetchUserEnrollments - processed batch size=${rows.size()} lastCourseId=$lastCourseId lastBatchId=$lastBatchId")
        if (rows.size() < batchSize) hasMore = false
      }
    }
  }*/

  private def fetchUserEnrollments(userId: String, metrics: Metrics): Unit = {

    import scala.collection.JavaConverters._

    val cql = QueryBuilder
      .select(config.courseid, config.status, config.issuedCertificatesKey)
      .from(config.coursesdb, config.enrolmentTable)
      .where(QueryBuilder.eq("userid", userId))
      .toString

    logger.info(s"fetchUserEnrollments - querying for userId=$userId" +
      s" table=${config.coursesdb}.${config.enrolmentTable}")

    val rows = cassandraUtil.find(cql)

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

        // FIX 1 (cont.) — row.getList with TypeToken is null-safe: returns null
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

  /*private def processCourse(userId: String,enrolment: Map[String,AnyRef],metrics: Metrics): Unit = {
    import scala.collection.JavaConverters._
    val courseId = enrolment(config.courseid).toString
    logger.info(s"processCourse - userId=$userId courseId=$courseId")
    val courseInfo = getCourseInfo(courseId)(metrics,config,cache,httpUtil)
    logger.info(s"Fetched courseInfo for courseId=$courseId: ${ScalaJsonUtil.serialize(courseInfo)}")
    val competencies = courseInfo.get(config.competenciesV6Key).asInstanceOf[java.util.List[java.util.Map[String,AnyRef]]].asScala.toList.map(_.asScala.toMap)
    logger.info(s"Fetched courseInfo for courseId=$courseId: ${ScalaJsonUtil.serialize(competencies)}")
    var certificateId = ""
    //TODO remove the logger statements post testing.
    logger.info(s"CourseInfo - courseInfo=$courseInfo")
    var acquiredAt = ""
    val certs = enrolment.get(config.issuedCertificatesKey).map(_.asInstanceOf[java.util.List[java.util.Map[String,String]]])
    certs.foreach(l => if (!l.isEmpty) { val c=l.get(0); certificateId=c.getOrDefault(config.identifierKey,""); acquiredAt=c.getOrDefault(config.lastIssuedOnKey,"") })
    val detailsMap = Map(config.acquiredContextIdKey->courseId,config.certificateIdKey->certificateId,config.acquiredAt->acquiredAt)
    logger.debug(s"processCourse - competencies count=${competencies.size} for courseId=$courseId")
    competencies.foreach { comp =>
      val areaId = comp.getOrElse(config.competencyAreaIdentifierKey,"").toString
      val themeId = comp.getOrElse(config.competencyThemeIdentifierKey,"").toString
      val subthemeId = comp.getOrElse(config.competencySubThemeIdentifierKey,"").toString
      //TODO remove the logger statements post testing.
      logger.info(s"process competency - areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
      upsertUserCompetencyByContext(userId, areaId, themeId, subthemeId, detailsMap, config.iGOTCourses, metrics)
    }
  }*/

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

    // FIX 2 — enrolment.get(key) returns Some(null) when the key is present but
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
    val localCache =
      scala.collection.mutable.Map[
        (String, String, String),
        Map[String, List[Map[String, String]]]
      ]()

    competencies.foreach { comp =>

      val areaId =
        comp.getOrElse(config.competencyAreaIdentifierKey, "").toString
      val themeId =
        comp.getOrElse(config.competencyThemeIdentifierKey, "").toString
      val subthemeId =
        comp.getOrElse(config.competencySubThemeIdentifierKey, "").toString

      val cacheKey = (areaId, themeId, subthemeId)   // tuple — no encoding needed

      // FETCH ONCE PER KEY
      val existingDetails = localCache.getOrElseUpdate(
        cacheKey,
        fetchCompetencyFromDB(userId, areaId, themeId, subthemeId)
      )

      // UPDATE IN MEMORY
      val updatedDetails =
        existingDetails
          .getOrElse(config.iGOTCourses, List())
          .filterNot(_(config.acquiredContextIdKey) ==
            detailsMap(config.acquiredContextIdKey)) :+ detailsMap

      val finalDetails =
        existingDetails + (config.iGOTCourses -> updatedDetails)

      localCache.put(cacheKey, finalDetails)
    }

    //  WRITE AFTER LOOP — tuple destructuring, no split() needed
    localCache.foreach { case ((areaId, themeId, subthemeId), details) =>
      upsertCompetency(userId, areaId, themeId, subthemeId, details, metrics)
    }
  }



  private def upsertUserCompetencyByContext(
                                             userId: String,
                                             areaId: String,
                                             themeId: String,
                                             subthemeId: String,
                                             detailsMap: Map[String, String],
                                             competencyKey: String,
                                             metrics: Metrics
                                           ): Unit = {
    logger.debug(s"upsertUserCompetencyByContext - reading competency_details for userId=$userId area=$areaId theme=$themeId subtheme=$subthemeId key=$competencyKey")
    val selectQuery = QueryBuilder
      .select(config.competencyDetails)
      .from(config.dbName, config.userCompetencyTable)
      .where(QueryBuilder.eq("user_id", userId))
      .and(QueryBuilder.eq("competency_area_id", areaId))
      .and(QueryBuilder.eq("competency_theme_id", themeId))
      .and(QueryBuilder.eq("competency_subtheme_id", subthemeId))
      .toString
    val existingRows = cassandraUtil.find(selectQuery)
    var competencyDetails: Map[String, List[Map[String, String]]] = Map()
    if (existingRows != null && !existingRows.isEmpty) {
      val typeToken =
        new TypeToken[java.util.Map[String, java.util.List[java.util.Map[String, String]]]]() {}
      val detailsObj = existingRows.get(0).get(config.competencyDetails, typeToken)
      if (detailsObj != null) {
        competencyDetails = detailsObj.asScala.map { case (k, v) =>
          k -> v.asScala.toList.map(_.asScala.toMap)
        }.toMap
      }
    }
    val updatedList =
      competencyDetails
        .getOrElse(competencyKey, List())
        .filterNot(_(config.acquiredContextIdKey) == detailsMap(config.acquiredContextIdKey)) :+ detailsMap
    val updatedDetails = competencyDetails + (competencyKey -> updatedList)
    val detailsJavaMap: java.util.Map[String, java.util.List[java.util.Map[String, String]]] =
      updatedDetails.map { case (k, v) => k -> v.map(_.asJava).asJava }.asJava
    val insertQuery = QueryBuilder.insertInto(config.dbName, config.userCompetencyTable)
      .value("user_id", userId)
      .value("competency_area_id", areaId)
      .value("competency_theme_id", themeId)
      .value("competency_subtheme_id", subthemeId)
      .value("competency_details", detailsJavaMap)
    insertQuery.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    logger.debug(s"upsertUserCompetencyByContext - upserting competency for userId=$userId area=$areaId theme=$themeId subtheme=$subthemeId key=$competencyKey")
    cassandraUtil.upsert(insertQuery.toString)
    metrics.incCounter(config.dbUpdateCount)
  }

  private def rowToMap(row: Row): Map[String, AnyRef] = {
    row.getColumnDefinitions.asList().asScala.map(c => c.getName -> row.getObject(c.getName)).toMap
  }

  private def processUserExtCourses(userId: String, metrics: Metrics): Unit = {
    logger.debug(s"processUserExtCourses - starting for userId=$userId")

    // OPT: assign once — avoids repeated config property lookup per row
    val certsKey = config.extContentUserExternalEnrolmentsIssuedCertificatesKey
    // OPT: TypeToken created once per method call, not a new anonymous-class instance per row
    val certsTypeToken = new TypeToken[java.util.Map[String, String]]() {}

    val query = QueryBuilder
      .select(config.courseid, config.status, certsKey)
      .from(config.extContentUserExternalEnrolmentsDb, config.extContentUserExternalEnrolmentsTable)
      .where(QueryBuilder.eq("userid", userId))
      .toString
    val rows = cassandraUtil.find(query)
    if (rows == null || rows.isEmpty) return

    // OPT: cross-course shared cache — one SELECT per unique (area, theme, subtheme) tuple
    //    across ALL ext courses for this user.
    //    Previously each course had its own localCache; if two courses map to the same
    //    competency tuple that tuple was SELECT-ed and INSERT-ed once per course.
    //    Now: U unique tuples = U SELECTs + U INSERTs regardless of how many courses.
    val sharedCache =
      scala.collection.mutable.Map[
        (String, String, String),
        Map[String, List[Map[String, String]]]
      ]()

    rows.asScala
      .filter(_.getInt(config.status) == 2)
      .foreach { row =>
        val courseId = row.getString(config.courseid)
        //  OPT: Option() + fold — null-safe, no intermediate val needed
        val issuedCertificates =
          Option(row.getList(certsKey, certsTypeToken))
            .fold(List.empty[java.util.Map[String, String]])(_.asScala.toList)
        // OPT: per-course error isolation — one bad course doesn't abort the rest
        try {
          // only accumulates into sharedCache — no DB writes yet
          accumulateExtCourseIntoCache(userId, courseId, issuedCertificates, sharedCache, metrics)
        } catch {
          case ex: Exception =>
            metrics.incCounter(config.failedEventCount)
            logger.error(s"Error processing ext course userId=$userId courseId=$courseId", ex)
        }
      }

    // OPT: write-after-all-courses — single upsert per unique competency tuple,
    //    accumulating updates from every ext course before touching Cassandra
    sharedCache.foreach { case ((areaId, themeId, subthemeId), details) =>
      try {
        upsertCompetency(
          userId, areaId, themeId, subthemeId,
          details,
          metrics
        )
      } catch {
        case ex: Exception =>
          metrics.incCounter(config.failedEventCount)
          logger.error(s"Error writing competency userId=$userId area=$areaId theme=$themeId subtheme=$subthemeId", ex)
      }
    }
  }


  private def accumulateExtCourseIntoCache(
                                            userId: String,
                                            courseId: String,
                                            issuedCertificates: List[java.util.Map[String, String]],
                                            sharedCache: scala.collection.mutable.Map[
                                              (String, String, String),
                                              Map[String, List[Map[String, String]]]],
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

    // prefer v2 certificate, then fall back to headOption (consistent with processExtCourses)
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

    competencies.foreach { comp =>
      val areaId     = comp.getOrDefault(config.competencyAreaIdentifierKey, "").toString
      val themeId    = comp.getOrDefault(config.competencyThemeIdentifierKey, "").toString
      val subthemeId = comp.getOrDefault(config.competencySubThemeIdentifierKey, "").toString
      val cacheKey   = (areaId, themeId, subthemeId)

      // fetchCompetencyFromDB called at most once per unique tuple across ALL courses
      val existingDetails = sharedCache.getOrElseUpdate(
        cacheKey,
        fetchCompetencyFromDB(userId, areaId, themeId, subthemeId)
      )

      val updatedList =
        existingDetails
          .getOrElse(config.extCoursesContextType, List())
          .filterNot(_(config.acquiredContextIdKey) == newDetail(config.acquiredContextIdKey)) :+ newDetail

      sharedCache.put(cacheKey, existingDetails + (config.extCoursesContextType -> updatedList))
    }
  }

  // placeholder — replaced by accumulateExtCourseIntoCache + processUserExtCourses write phase
  // kept temporarily to avoid compilation break if referenced elsewhere; safe to delete
  private def processExtCourseForFirstTimeUser(
                                                userId: String,
                                                courseId: String,
                                                issuedCertificates: List[java.util.Map[String, String]],
                                                metrics: Metrics
                                              ): Unit = {
    val sharedCache =
      scala.collection.mutable.Map[
        (String, String, String),
        Map[String, List[Map[String, String]]]
      ]()
    accumulateExtCourseIntoCache(userId, courseId, issuedCertificates, sharedCache, metrics)
    sharedCache.foreach { case ((areaId, themeId, subthemeId), details) =>
      upsertCompetency(
        userId, areaId, themeId, subthemeId,
        details,
        metrics
      )
    }
  }

  // New function for processing user-competency-mapping-event
  private def processAchievementEvent(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    logger.debug(s"processAchievementEvent - userId=$userId achievementId=${event.contentId} action=${event.action}")

    // ✅ OPT: extract action flags once — avoids repeated null + equalsIgnoreCase checks below
    val isCreateAction = event.action == null || event.action.isEmpty
    val isUpdateAction = !isCreateAction && event.action.equalsIgnoreCase(config.update)
    val isDeleteAction = !isCreateAction && event.action.equalsIgnoreCase(config.delete)

    // ✅ OPT: fetch from Cassandra ONLY when contextData is actually needed.
    //    delete action never reads contextData, so skip the SELECT entirely.
    // ✅ OPT: val instead of var + reassignment — single expression, no mutation
    val contextData: Map[String, AnyRef] =
      if (isCreateAction || isUpdateAction) {
        val query = QueryBuilder
          .select(config.contextData)
          .from(config.dbName, config.learnerAchievementTable)
          .where(QueryBuilder.eq("userid", userId))
          .and(QueryBuilder.eq("id", event.contentId))
          .and(QueryBuilder.eq("contexttype", config.achievements))
          .toString
        Option(cassandraUtil.findOne(query))
          .fold(Map.empty[String, AnyRef]) { row =>
            ScalaJsonUtil.deserialize[Map[String, AnyRef]](row.getString(config.contextData))
          }
      } else Map.empty[String, AnyRef]

    // ✅ OPT: detailsMap is only used by create / update branches — skip for delete
    val detailsMap: Map[String, String] =
      if (isCreateAction || isUpdateAction) {
        val uploadedDocUrl  = contextData.getOrElse(config.uploadedDocumentUrl, "").toString
        val externallyUploaded = if (uploadedDocUrl.nonEmpty) config.trueValue else config.falseValue
        val certificateId   =
          if (uploadedDocUrl.nonEmpty) uploadedDocUrl
          else contextData.getOrElse(config.url, "").toString
        Map(
          config.acquiredContextIdKey ->
            contextData.getOrElse(config.acquiredContextIdKey, event.contentId).toString,
          config.certificateIdKey     -> certificateId,
          config.acquiredAt           -> contextData.getOrElse(config.issuedOn, "").toString,
          config.externallyUploaded   -> externallyUploaded
        )
      } else Map.empty[String, String]

    if (isCreateAction) {
      // ─── CREATE (action null / empty) ──────────────────────────────────────
      val competencies = contextData.get(config.competenciesV6Key) match {
        case Some(list: java.util.List[_]) =>
          list.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
            .asScala.toList.map(_.asScala.toMap)
        case Some(list: List[_]) =>
          list.asInstanceOf[List[Map[String, AnyRef]]]
        case _ => List.empty[Map[String, AnyRef]]
      }

      // OPT: localCache — one SELECT + one INSERT per unique (area, theme, subtheme) tuple.
      //    Replaces upsertUserCompetency which did one SELECT + one INSERT per competency.
      val localCache =
        scala.collection.mutable.Map[(String, String, String), Map[String, List[Map[String, String]]]]()

      competencies.foreach { comp =>
        val areaId     = comp.getOrElse(config.competencyAreaIdentifierKey, "").toString
        val themeId    = comp.getOrElse(config.competencyThemeIdentifierKey, "").toString
        val subthemeId = comp.getOrElse(config.competencySubThemeIdentifierKey, "").toString
        val cacheKey   = (areaId, themeId, subthemeId)

        val existingDetails = localCache.getOrElseUpdate(
          cacheKey, fetchCompetencyFromDB(userId, areaId, themeId, subthemeId)
        )
        val updatedList =
          existingDetails
            .getOrElse(config.selfAchievement, List())
            .filterNot(_(config.acquiredContextIdKey) == detailsMap(config.acquiredContextIdKey)) :+ detailsMap
        localCache.put(cacheKey, existingDetails + (config.selfAchievement -> updatedList))
      }

      // write once per unique tuple — consistent with processCourse / accumulateExtCourseIntoCache
      localCache.foreach { case ((areaId, themeId, subthemeId), details) =>
        upsertCompetency(userId, areaId, themeId, subthemeId, details, metrics)
      }

    } else if (isUpdateAction) {
      // ─── UPDATE ────────────────────────────────────────────────────────────
      val competencyIds = event.competencyIds.asInstanceOf[List[Map[String, AnyRef]]]
      val addCache =
        scala.collection.mutable.Map[(String, String, String), Map[String, List[Map[String, String]]]]()

      competencyIds.foreach { comp =>
        val areaId     = comp.getOrElse(config.competencyAreaId, "").toString
        val themeId    = comp.getOrElse(config.competencyThemeId, "").toString
        val subthemeId = comp.getOrElse(config.competencySubThemeId, "").toString
        val action     = comp.getOrElse(config.action, "").toString.trim.toLowerCase

        if (action == config.removed) {
          // flush any pending add for this tuple so the remove sees the latest state
          addCache.remove((areaId, themeId, subthemeId)).foreach { details =>
            upsertCompetency(userId, areaId, themeId, subthemeId, details, metrics)
          }
          removeAchievementFromCompetency(userId, areaId, themeId, subthemeId, event.contentId)
        } else if (action == config.added) {
          val cacheKey = (areaId, themeId, subthemeId)
          val existingDetails = addCache.getOrElseUpdate(
            cacheKey, fetchCompetencyFromDB(userId, areaId, themeId, subthemeId)
          )
          val updatedList =
            existingDetails
              .getOrElse(config.selfAchievement, List())
              .filterNot(_(config.acquiredContextIdKey) == detailsMap(config.acquiredContextIdKey)) :+ detailsMap
          addCache.put(cacheKey, existingDetails + (config.selfAchievement -> updatedList))
        }
      }

      // write all deferred adds
      addCache.foreach { case ((areaId, themeId, subthemeId), details) =>
        upsertCompetency(userId, areaId, themeId, subthemeId, details, metrics)
      }

    } else if (isDeleteAction) {
      // ─── DELETE ────────────────────────────────────────────────────────────
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

    private def upsertUserCompetency(
                                      userId: String,
                                      areaId: String,
                                      themeId: String,
                                      subthemeId: String,
                                      detailsMap: Map[String, String],
                                      metrics: Metrics
                                    ): Unit = {
      logger.debug(s"upsertUserCompetency - selecting competency_details for userId=$userId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
      // ✅ FIX: two-arg from(keyspace, table)
      val selectQuery = QueryBuilder
        .select(config.competencyDetails)
        .from(config.dbName, config.userCompetencyTable)
        .where(QueryBuilder.eq("user_id", userId))
        .and(QueryBuilder.eq("competency_area_id", areaId))
        .and(QueryBuilder.eq("competency_theme_id", themeId))
        .and(QueryBuilder.eq("competency_subtheme_id", subthemeId))
        .toString
      val existingRows = cassandraUtil.find(selectQuery)
      var competencyDetails: Map[String, List[Map[String, String]]] = Map()
      if (existingRows != null && !existingRows.isEmpty) {
        val typeToken =
          new TypeToken[java.util.Map[String, java.util.List[java.util.Map[String, String]]]]() {}
        val detailsObj = existingRows.get(0).get(config.competencyDetails, typeToken)
        if (detailsObj != null) {
          competencyDetails = detailsObj.asScala.map { case (k, v) =>
            k -> v.asScala.toList.map(_.asScala.toMap)
          }.toMap
        }
      }
      val updatedList =
        competencyDetails
          .getOrElse(config.selfAchievement, List())
          .filterNot(_(config.acquiredContextIdKey) == detailsMap(config.acquiredContextIdKey)) :+ detailsMap
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
      logger.info(s"upsertUserCompetency - upserting selfAchievement for userId=$userId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
      cassandraUtil.upsert(insertQuery.toString)
      metrics.incCounter(config.dbUpdateCount)
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
      // FIX: two-arg from(keyspace, table)
      val selectQuery = QueryBuilder
        .select(config.competencyDetails)
        .from(config.dbName, config.userCompetencyTable)
        .where(QueryBuilder.eq("user_id", userId))
        .and(QueryBuilder.eq("competency_area_id", areaId))
        .and(QueryBuilder.eq("competency_theme_id", themeId))
        .and(QueryBuilder.eq("competency_subtheme_id", subthemeId))
        .toString
      val existingRow = cassandraUtil.findOne(selectQuery)
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
        // FIX: two-arg delete().from(keyspace, table)
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
        // FIX: two-arg insertInto(keyspace, table)
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

    val userId   = event.userId
    val courseId = event.contentId
    val batchId  = event.batchId

    // ─── Step 1: Fetch issued_certificates ──────────────────────────────────
    val enrolmentQuery = QueryBuilder
      .select(config.issuedCertificatesKey)
      .from(config.coursesdb, config.enrolmentTable)
      .where(QueryBuilder.eq("userid", userId))
      .and(QueryBuilder.eq("courseid", courseId))
      .and(QueryBuilder.eq("batchid", batchId))
      .toString

    // OPT: findOne — (userid, courseid, batchid) is the full PK → at most 1 row;
    //    avoids allocating java.util.List[Row] just to call get(0)
    val enrolmentRow = cassandraUtil.findOne(enrolmentQuery)
    if (enrolmentRow == null) {
      metrics.incCounter(config.failedEventCount)
      logger.error(s"No enrolment found for userId=$userId courseId=$courseId")
      return
    }

    // OPT: extract directly from the single row — replaces var + Java for-loop
    val certsRaw = enrolmentRow.getList(
      config.issuedCertificatesKey,
      new TypeToken[java.util.Map[String, String]]() {}
    )
    val issuedCertificates: List[java.util.Map[String, String]] =
      if (certsRaw != null) certsRaw.asScala.toList else List.empty

    if (issuedCertificates.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      logger.error(s"No issued_certificates found for userId=$userId")
      return
    }

    // ─── Step 2: Select best certificate (prefer v2) ────────────────────────
    val certMapOpt =
      issuedCertificates
        .find(c => Option(c.get("version")).exists(_.equalsIgnoreCase("v2")))
        .orElse(issuedCertificates.headOption)

    // OPT: certMapOpt.isEmpty guard removed — headOption is always Some here
    //    because issuedCertificates.isEmpty is already guarded above
    val certMap       = certMapOpt.get
    val certificateId =
      Option(certMap.get("certificateId"))
        .orElse(Option(certMap.get("identifier")))
        .map(_.toString)
        .getOrElse("")
    val issuedDate = Option(certMap.get("lastIssuedOn")).map(_.toString).getOrElse("")

    if (certificateId.isEmpty) {
      metrics.incCounter(config.failedEventCount)
      return
    }

    // ─── Step 3: Fetch course competencies ──────────────────────────────────
    val courseMetadata = getCourseInfo(courseId)(metrics, config, cache, httpUtil)
    // FIX: config.competenciesV6Key instead of raw "competencies_v6"
    val competencies =
      courseMetadata
        .getOrDefault(config.competenciesV6Key, new java.util.ArrayList[java.util.Map[String, AnyRef]]())
        .asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]

    if (competencies.isEmpty) {
      logger.warn(s"No competencies found for courseId=$courseId")
      return
    }

    val userCompetencyTableFull =
      if (config.userCompetencyTable.contains(".")) config.userCompetencyTable
      else s"${config.dbName}.${config.userCompetencyTable}"

    val newDetail: Map[String, String] = Map(
      config.acquiredContextIdKey -> courseId,
      config.certificateIdKey     -> certificateId,
      config.acquiredAt           -> issuedDate
    )

    // ─── Step 4: Accumulate + write competency updates ──────────────────────
    // OPT: localCache — one SELECT per unique (area, theme, subtheme) tuple;
    //    write-after-loop — one INSERT per unique tuple.
    //    Replaces per-competency find+var+upsert (N SELECTs+INSERTs → U SELECTs+INSERTs).
    //    Consistent with processCourse / accumulateExtCourseIntoCache.
    val localCache =
      scala.collection.mutable.Map[
        (String, String, String),
        Map[String, List[Map[String, String]]]
      ]()

    competencies.asScala.foreach { comp =>
      val areaId = comp.getOrDefault(config.competencyAreaIdentifierKey, "").toString
      val themeId = comp.getOrDefault(config.competencyThemeIdentifierKey, "").toString
      val subthemeId = comp.getOrDefault(config.competencySubThemeIdentifierKey, "").toString
      val cacheKey   = (areaId, themeId, subthemeId)

      val existingDetails = localCache.getOrElseUpdate(
        cacheKey, fetchCompetencyFromDB(userId, areaId, themeId, subthemeId)
      )

      // FIX: config.iGOTCourses instead of raw "iGOTCourses"
      // FIX: config.acquiredContextIdKey instead of raw "acquiredContextId"
      val updatedList =
        existingDetails
          .getOrElse(config.iGOTCourses, List())
          .filterNot(_(config.acquiredContextIdKey) == newDetail(config.acquiredContextIdKey)) :+ newDetail

      localCache.put(cacheKey, existingDetails + (config.iGOTCourses -> updatedList))
    }

    // write once per unique tuple — preserves original single metrics.incCounter per event
    localCache.foreach { case ((areaId, themeId, subthemeId), details) =>
      logger.debug(s"Upserting competency courseId=$courseId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
      val detailsJavaMap: java.util.Map[String, java.util.List[java.util.Map[String, String]]] =
        details.map { case (k, v) => k -> v.map(_.asJava).asJava }.asJava
      // FIX: two-arg insertInto(keyspace, table)
      val insertQuery = QueryBuilder.insertInto(config.dbName, config.userCompetencyTable)
        .value("user_id", userId)
        .value("competency_area_id", areaId)
        .value("competency_theme_id", themeId)
        .value("competency_subtheme_id", subthemeId)
        .value("competency_details", detailsJavaMap)
      insertQuery.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)

      cassandraUtil.upsert(insertQuery.toString)
      logger.info(s"Processing competency: areaId=$areaId, themeId=$themeId, subthemeId=$subthemeId")
    }
    metrics.incCounter(config.dbUpdateCount)
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
    val userId   = event.userId
    val courseId = event.contentId

    // ─── Step 1: Resolve course competencies ────────────────────────────────
    // OPT: removed extContentReadUrl + contentUrl aliases — inlined below
    val courseMetadata = cache.getWithRetry(courseId)

    // OPT: collapsed two-stage match (raw → competencies) into one pass;
    //    the original first-match normalised cache values, then the second-match
    //    normalised again — a single match on the raw AnyRef does the same job
    val rawValue: AnyRef =
      if (courseMetadata != null && courseMetadata.contains(config.extContentResponseKey)) {
        val contentMap =
          courseMetadata(config.extContentResponseKey).asInstanceOf[java.util.Map[String, AnyRef]]
        val v = contentMap.get(config.competenciesV6Key)
        if (v == null)
          logger.warn(s"Key '${config.competenciesV6Key}' found but value is null for courseId=$courseId")
        v
      } else {
        logger.warn(
          s"Key '${config.extContentResponseKey}' not found in courseMetadata for courseId=$courseId. Falling back to API call."
        )
        // OPT: removed redundant extContentReadUrl + contentUrl vals — inlined
        val response = getExtContentAPICall(config.extContentUrl + courseId)(config, httpUtil, metrics)
        response.get(config.competenciesV6Key)
      }
    val competencies: java.util.List[java.util.Map[String, AnyRef]] = rawValue match {
      case null => new java.util.ArrayList[java.util.Map[String, AnyRef]]()
      case jl: java.util.List[_] =>
        jl.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
      case s: Seq[_] =>
        s.map {
          case jm: java.util.Map[_, _] => jm.asInstanceOf[java.util.Map[String, AnyRef]]
          case sm: Map[_, _]           => sm.asJava.asInstanceOf[java.util.Map[String, AnyRef]]
          case other                   => other.asInstanceOf[java.util.Map[String, AnyRef]]
        }.toList.asJava
      case other =>
        logger.warn(
          s"Key '${config.competenciesV6Key}' has unexpected type ${other.getClass.getName} for courseId=$courseId"
        )
        new java.util.ArrayList[java.util.Map[String, AnyRef]]()
    }

    if (competencies.isEmpty) {
      logger.warn(s"No competencies found for extCourseId=$courseId")
      return
    }

    // ─── Step 2: Fetch issued_certificates from user_external_enrolments ────
    val certsKey = config.extContentUserExternalEnrolmentsIssuedCertificatesKey
    val externalEnrolQuery = QueryBuilder
      .select(certsKey)
      .from(config.extContentUserExternalEnrolmentsDb, config.extContentUserExternalEnrolmentsTable)
      .where(QueryBuilder.eq("userid", userId))
      .and(QueryBuilder.eq("courseid", courseId))
      .toString

    //  OPT: findOne — (userid, courseid) identifies a single row; avoids List allocation
    val enrolmentRow = cassandraUtil.findOne(externalEnrolQuery)

    //  OPT: getList + TypeToken — type-safe; replaces unsafe getObject + asInstanceOf cast
    // OPT: val instead of var + conditional reassignment
    val issuedCertificates: List[java.util.Map[String, String]] =
      if (enrolmentRow != null) {
        val certsRaw = enrolmentRow.getList(
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
      certMapOpt.flatMap(c => Option(c.get(config.certificateIdKey)).orElse(Option(c.get(config.identifierKey)))).getOrElse("")
    val issuedDate =
      certMapOpt.flatMap(c => Option(c.get(config.lastIssuedOnKey))).getOrElse("")

    // ─── Step 3: Accumulate + write competency updates ──────────────────────
    //  OPT: moved outside loop — both were recomputed on every iteration
    val userCompetencyTableFull =
      if (config.userCompetencyTable.contains(".")) config.userCompetencyTable
      else s"${config.dbName}.${config.userCompetencyTable}"

    //  OPT: newDetail is constant for all competencies — moved outside loop
    val newDetail: Map[String, String] = Map(
      config.acquiredContextIdKey -> courseId,
      config.certificateIdKey     -> certificateId,
      config.acquiredAt           -> issuedDate
    )

    // OPT: localCache — one SELECT per unique (area, theme, subtheme) tuple;
    //    write-after-loop — one INSERT per unique tuple.
    //    Replaces per-competency find+var+upsert (N SELECTs+INSERTs → U SELECTs+INSERTs).
    //    Consistent with processIGOTCourses / accumulateExtCourseIntoCache.
    val localCache =
      scala.collection.mutable.Map[
        (String, String, String),
        Map[String, List[Map[String, String]]]
      ]()

    competencies.asScala.foreach { comp =>
      val areaId     = comp.getOrDefault(config.competencyAreaIdentifierKey, "").toString
      val themeId    = comp.getOrDefault(config.competencyThemeIdentifierKey, "").toString
      val subthemeId = comp.getOrDefault(config.competencySubThemeIdentifierKey, "").toString
      val cacheKey   = (areaId, themeId, subthemeId)

      val existingDetails = localCache.getOrElseUpdate(
        cacheKey, fetchCompetencyFromDB(userId, areaId, themeId, subthemeId)
      )

      val updatedList =
        existingDetails
          .getOrElse(config.extCoursesContextType, List())
          .filterNot(_(config.acquiredContextIdKey) == newDetail(config.acquiredContextIdKey)) :+ newDetail

      localCache.put(cacheKey, existingDetails + (config.extCoursesContextType -> updatedList))
    }

    // write once per unique tuple
    localCache.foreach { case ((areaId, themeId, subthemeId), details) =>
      logger.debug(s"Upserting extCourse competency courseId=$courseId areaId=$areaId themeId=$themeId subthemeId=$subthemeId")
      val detailsJavaMap: java.util.Map[String, java.util.List[java.util.Map[String, String]]] =
        details.map { case (k, v) => k -> v.map(_.asJava).asJava }.asJava
      // ✅ FIX: two-arg insertInto(keyspace, table)
      val insertQuery = QueryBuilder.insertInto(config.dbName, config.userCompetencyTable)
        .value("user_id", userId)
        .value("competency_area_id", areaId)
        .value("competency_theme_id", themeId)
        .value("competency_subtheme_id", subthemeId)
        .value("competency_details", detailsJavaMap)
      insertQuery.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
      cassandraUtil.upsert(insertQuery.toString)
      metrics.incCounter(config.dbUpdateCount)
    }
  }

  def generateFailedEvent(userId: String, batchId: String, contentId: String): String = {
    val ets = System.currentTimeMillis
    val mid = s"LP.${ets}.${UUID.randomUUID}"
    val eventString = s"""{"eid": "BE_JOB_REQUEST", "ets": $ets, "mid": "$mid", "actor": {"id": "Program Certificate Pre Processor Generator", "type": "System"}, "context": {"pdata": {"ver": "1.0", "id": "org.sunbird.platform"}}, "object": {"id": "${batchId}_${contentId}", "type": "ProgramCertificatePreProcessorGeneration"}, "edata": {"userId": "$userId", "action": "program-issue-certificate", "iteration": 1, "trigger": "auto-issue", "batchId": "$batchId", "parentCollections": ["$contentId"], "courseId": "$contentId"}}"""
    eventString
  }

  private def fetchCompetencyFromDB(
                                     userId: String,
                                     areaId: String,
                                     themeId: String,
                                     subthemeId: String
                                   ): Map[String, List[Map[String, String]]] = {
    import scala.collection.JavaConverters._   // ✅ OPT: moved to method top — was buried inside nested if block

    val selectQuery = QueryBuilder
      .select(config.competencyDetails)
      .from(config.dbName, config.userCompetencyTable)
      .where(QueryBuilder.eq("user_id", userId))
      .and(QueryBuilder.eq("competency_area_id", areaId))
      .and(QueryBuilder.eq("competency_theme_id", themeId))
      .and(QueryBuilder.eq("competency_subtheme_id", subthemeId))
      .toString

    // OPT: findOne — (user_id, area_id, theme_id, subtheme_id) is the full PK → at most 1 row;
    //    avoids allocating java.util.List[Row] just to call get(0)
    val existingRow = cassandraUtil.findOne(selectQuery)
    // OPT: two early returns flatten two-level nesting to zero nesting
    if (existingRow == null) return Map.empty

    val typeToken =
      new TypeToken[java.util.Map[String, java.util.List[java.util.Map[String, String]]]]() {}

    val detailsObj = existingRow.get(config.competencyDetails, typeToken)
    if (detailsObj == null) return Map.empty

    // OPT: no explicit return — last expression is the natural method result
    detailsObj.asScala.map { case (k, v) =>
      k -> v.asScala.toList.map(_.asScala.toMap)
    }.toMap
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
}
