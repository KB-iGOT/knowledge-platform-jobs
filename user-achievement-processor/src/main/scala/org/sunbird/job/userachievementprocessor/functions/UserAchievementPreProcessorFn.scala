package org.sunbird.job.userbadgeawarding.functions


import com.google.common.reflect.TypeToken
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.exception.InvalidEventException
import org.sunbird.job.userbadgeawarding.domain.Event
import org.sunbird.job.userbadgeawarding.task.UserBadgeAwardingConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil, JSONUtil, ScalaJsonUtil}
import org.sunbird.job.{BaseProcessKeyedFunction, Metrics}

import scala.collection.JavaConverters._

import scala.collection.convert.ImplicitConversions.`map AsScala`

class UserAchievementPreProcessorFn(config: UserBadgeAwardingConfig, httpUtil: HttpUtil)
                                   (implicit val stringTypeInfo: TypeInformation[String],
                                    @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessKeyedFunction[String, Event, String](config) {

  private[this] val logger = LoggerFactory.getLogger(classOf[UserAchievementPreProcessorFn])
  private var cache: DataCache = _
  private var dataCache: DataCache = _
  private var contentCache: DataCache = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
    val redisConnect = new RedisConnect(config)
    cache = new DataCache(config, redisConnect, config.badgeCountCacheStore, List())
    cache.init()
    dataCache = new DataCache(config, redisConnect, config.badgeCacheStore, List())
    dataCache.init()
    contentCache = new DataCache(config, redisConnect, config.collectionCacheStore, List())
    cache.init()
  }

  override def close(): Unit = {
    cassandraUtil.close()
    super.close()
  }

  override def metricsList(): List[String] = {
    List(config.totalEventsCount, config.dbUpdateCount, config.failedEventCount, config.skippedEventCount, config.successEventCount)
  }

  /**
   * Push recent badge activity to Redis
   * Maintains a list of max 10 recent badge awards
   * This is a non-critical operation - failures should not stop badge awarding
   */
  private def pushRecentBadgeActivity(userId: String, badgeId: String, badgeTitle: String): Unit = {
    try {
      // Fetch user name from Cassandra
      val userName = getUserName(userId)

      val badgeActivity = Map(
        "userId" -> userId,
        "userName" -> userName,
        "badgeId" -> badgeId,
        "badgeTitle" -> badgeTitle
      )

      val badgeActivityJson = ScalaJsonUtil.serialize(badgeActivity)
      val redisKey = config.recentBadgeActivityKey

      dataCache.lpush(redisKey, badgeActivityJson)
      dataCache.ltrim(redisKey, 0, config.recentBadgeActivityMaxSize - 1)

      logger.info(s"Pushed recent badge activity to Redis (index 12): userName=$userName, badgeTitle=$badgeTitle")
    } catch {
      case ex: Exception =>
        logger.error(s"Error pushing recent badge activity to Redis for userId=$userId, badgeTitle=$badgeTitle", ex)
        // Don't fail the entire badge awarding process if Redis update fails (non-critical operation)
    }
  }

  /**
   * Parse badge earning date time from various types
   * Handles Double (including scientific notation), Float, Integer, Long, and String
   */
  private def parseBadgeEarningDateTime(value: Any): Long = {
    try {
      value match {
        case d: java.lang.Double => d.toLong
        case f: java.lang.Float => f.toLong
        case i: java.lang.Integer => i.toLong
        case l: java.lang.Long => l.longValue()
        case s: String =>
          try {
            // Try parsing as double first (handles scientific notation like 1.774656E12)
            s.toDouble.toLong
          } catch {
            case _: NumberFormatException => s.toLong
          }
        case _ => value.toString.toDouble.toLong
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to parse badgeEarningDateTime: $value", ex)
        throw new InvalidEventException(ex.getMessage, Map("badgeEarningDateTimeValue" -> value.toString), ex)
    }
  }

  /**
   * Parse ISO date string to milliseconds timestamp
   */
  private def parseIsoDateToMillis(dateString: String): Long = {
    try {
      import java.text.SimpleDateFormat
      import java.util.TimeZone

      val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
      dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
      val date = dateFormat.parse(dateString)
      date.getTime
    } catch {
      case ex: Exception =>
        logger.error(s"Error parsing date string: $dateString", ex)
        throw new InvalidEventException(ex.getMessage, Map("dateString" -> dateString), ex)
    }
  }

  /**
   * Fetch user name directly from Cassandra
   */
  private def getUserName(userId: String): String = {
    try {
      val userQuery = s"SELECT firstname FROM ${config.dbName}.${config.dbTable} WHERE id='$userId';"
      val userRows = cassandraUtil.find(userQuery)

      if (userRows != null && !userRows.isEmpty) {
        val row = userRows.get(0)
        val firstName = row.getString("firstname")
        if (firstName != null && firstName.nonEmpty) {
          logger.info(s"Found user firstName in Cassandra for userId=$userId")
          return firstName
        }
      }

      logger.warn(s"Failed to fetch user details for userId=$userId from Cassandra")
      userId
    } catch {
      case ex: Exception =>
        logger.error(s"Error fetching user name for userId=$userId", ex)
        throw new InvalidEventException(ex.getMessage, Map("userId" -> userId), ex)
    }
  }


  override def processElement(event: Event,
                              context: KeyedProcessFunction[String, Event, String]#Context,
                              metrics: Metrics): Unit = {
    metrics.incCounter(config.totalEventsCount)
    try {
      val contextType = event.contextType
      if (contextType != null && contextType.equalsIgnoreCase(config.iGOTCoursesContextType)) {
        processIGOTCourses(event, metrics)
      } else if (contextType != null && contextType.equalsIgnoreCase(config.extCoursesContextType)) {
        processExtCourses(event, metrics)
      } else if (contextType != null && contextType.equalsIgnoreCase(config.curatedProgramContextType)) {
        processProgramEnrolment(event, metrics)
      }
      metrics.incCounter(config.successEventCount)
    } catch {
      case ex: Exception =>
        metrics.incCounter(config.failedEventCount)
        throw new InvalidEventException(ex.getMessage, Map("partition" -> event.partition, "offset" -> event.offset), ex)
    }
  }

  // Helper for API call, returns the required response map or throws on error
  private def getAPICall(url: String, responseParam: String)(config: UserBadgeAwardingConfig, httpUtil: HttpUtil, metrics: Metrics): java.util.Map[String, AnyRef] = {
    val response = httpUtil.get(url, config.defaultHeaders)
    if (200 == response.status) {
      val resultMap = JSONUtil.deserialize[Map[String, AnyRef]](response.body)
      val result = resultMap.getOrElse("result", Map[String, AnyRef]())
      if (result.isInstanceOf[Map[_, _]]) {
        val resultTyped = result.asInstanceOf[Map[String, AnyRef]]
        if (resultTyped.contains(responseParam)) {
          val responseValue = resultTyped(responseParam)
          if (responseValue.isInstanceOf[Map[_, _]]) {
            val scalaMap = responseValue.asInstanceOf[Map[String, AnyRef]]
            new java.util.HashMap[String, AnyRef](scalaMap.asJava)
          } else {
            new java.util.HashMap[String, AnyRef]()
          }
        } else {
          new java.util.HashMap[String, AnyRef]()
        }
      } else {
        new java.util.HashMap[String, AnyRef]()
      }
    } else {
      throw new Exception(s"Error from get API : ${url}, with response: ${response}")
    }
  }

  // Helper for API call for extcontent, returns the required response map or throws on error
  private def getExtContentAPICall(url: String)(config: UserBadgeAwardingConfig, httpUtil: HttpUtil, metrics: Metrics): java.util.Map[String, AnyRef] = {
    val response = httpUtil.get(url, config.defaultHeaders)
    if (200 == response.status) {
      val result = JSONUtil.deserialize[Map[String, AnyRef]](response.body)
      if (result.contains(config.extContentResponseKey)) {
        val responseValue = result(config.extContentResponseKey)
        if (responseValue.isInstanceOf[Map[_, _]]) {
          val scalaMap = responseValue.asInstanceOf[Map[String, AnyRef]]
          new java.util.HashMap[String, AnyRef](scalaMap.asJava)
        } else {
          new java.util.HashMap[String, AnyRef]()
        }
      } else {
        new java.util.HashMap[String, AnyRef]()
      }
    } else {
      throw new Exception(s"Error from extcontent get API : ${url}, with response: ${response}")
    }
  }

  private def processIGOTCourses(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    val courseId = event.contentId
    val batchId = event.batchId

    val courseMetadata: java.util.Map[String, AnyRef] = getCourseInfo(courseId)(metrics, config, contentCache, httpUtil)

    // Process badge awarding for iGOTCourses
    processBadgeAwardingForIGOTCourses(userId, courseId, batchId, courseMetadata, metrics)
  }

  def getCourseInfo(courseId: String)(
    metrics: Metrics,
    config: UserBadgeAwardingConfig,
    contentCache: DataCache,
    httpUtil: HttpUtil
  ): java.util.Map[String, AnyRef] = {
    logger.info(
      s"Fetching course details from Redis for Id: ${courseId}, Configured Index: " + contentCache.getDBConfigIndex() + ", Current Index: " + contentCache.getDBIndex()
    )
    val courseMetadata = Option(contentCache).flatMap(c => Option(c.getWithRetry(courseId))).getOrElse(null)
    if (null == courseMetadata || courseMetadata.isEmpty) {
      logger.error(
        s"Fetching course details from Content Service for Id: ${courseId}"
      )
      val url =
        config.contentReadURL + "/" + courseId + "?fields=identifier,name,parentCollections,primaryCategory,childNodes,badgeDetails_v1"
      val response = getAPICall(url, "content")(config, httpUtil, metrics)
      val primaryCategory = StringContext
        .processEscapes(
          response.getOrElse("primaryCategory", "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val parentCollections = response
        .getOrElse("parentCollections", List.empty[String])
        .asInstanceOf[List[String]]
      val childNodes = response
        .getOrElse("childNodes", List.empty[String])
        .asInstanceOf[List[String]]
      val badgeDetails_v1 = response
        .getOrElse("badgeDetails_v1", List.empty[String])
        .asInstanceOf[List[String]]
      val courseName = response
        .getOrElse("name", "").asInstanceOf[String]
      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", primaryCategory)
      courseInfoMap.put("childNodes", childNodes)
      courseInfoMap.put("badgeDetails_v1", badgeDetails_v1)
      courseInfoMap.put("name", courseName)
      courseInfoMap
    } else {
      val primaryCategory = StringContext
        .processEscapes(
          courseMetadata
            .getOrElse("primarycategory", "")
            .asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val parentCollections = courseMetadata
        .getOrElse("parentcollections", new java.util.ArrayList())
        .asInstanceOf[java.util.ArrayList[String]]
      val courseName = courseMetadata
        .getOrElse("name", "")
        .asInstanceOf[String]
      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", primaryCategory)
      val childNodes = courseMetadata
        .getOrElse("childnodes", new java.util.ArrayList())
        .asInstanceOf[java.util.ArrayList[String]]
      courseInfoMap.put("childNodes", childNodes)
      val badgeDetails_v1 = courseMetadata
          .getOrElse("badgedetailsv1", new java.util.ArrayList())
          .asInstanceOf[java.util.ArrayList[String]]
      courseInfoMap.put("badgeDetails_v1", badgeDetails_v1)
      courseInfoMap.put("name", courseName)
      courseInfoMap
    }
  }

  /**
   * Fetch program hierarchy with children using hierarchy API
   * This extracts all children identifiers from the program hierarchy
   */
  def getProgramHierarchy(programId: String)(
    metrics: Metrics,
    config: UserBadgeAwardingConfig,
    httpUtil: HttpUtil
  ): java.util.Map[String, AnyRef] = {
    try {
      // Step 1: Fetch badgeDetails_v1 from content read API
      val readUrl = config.contentReadURL + "/" + programId + "?fields=identifier,name,badgeDetails_v1,primaryCategory"
      logger.info(s"Fetching program badgeDetails from content read API: $readUrl")

      val readResponse = httpUtil.get(readUrl, config.defaultHeaders)

      if (readResponse.status != 200) {
        logger.error(s"Error fetching program content for programId=$programId: ${readResponse.status} - ${readResponse.body}")
        throw new Exception(s"Error fetching program content for programId=$programId: ${readResponse.status} - ${readResponse.body}")
      }

      val readResultMap = JSONUtil.deserialize[Map[String, AnyRef]](readResponse.body)
      val readResult = readResultMap.getOrElse("result", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
      val readContent = readResult.getOrElse("content", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]

      val programName = readContent.getOrElse("name", "").asInstanceOf[String]
      val badgeDetails_v1 = readContent.getOrElse("badgeDetails_v1", new java.util.ArrayList())
      val primaryCategory = readContent.getOrElse("primaryCategory", "").asInstanceOf[String]

      // Check if badgeDetails_v1 is empty - if so, don't call hierarchy API
      val isBadgeDetailsEmpty = badgeDetails_v1 match {
        case jl: java.util.List[_] => jl.isEmpty
        case al: java.util.ArrayList[_] => al.isEmpty
        case l: List[_] => l.isEmpty
        case _ => true
      }

      if (isBadgeDetailsEmpty) {
        logger.info(s"badgeDetails_v1 is empty for programId=$programId, skipping hierarchy API call")
        val emptyProgramInfoMap: java.util.Map[String, AnyRef] = new java.util.HashMap[String, AnyRef]()
        emptyProgramInfoMap.put("courseId", programId)
        emptyProgramInfoMap.put("name", programName)
        emptyProgramInfoMap.put("badgeDetails_v1", badgeDetails_v1)
        emptyProgramInfoMap.put("primaryCategory", primaryCategory)
        emptyProgramInfoMap.put("childNodes", new java.util.ArrayList[String]())
        emptyProgramInfoMap.put("parentCollections", new java.util.ArrayList[String]())
        return emptyProgramInfoMap
      }
      logger.info(s"Fetched badgeDetails_v1 from content read API for programId=$programId")
      // Step 2: Fetch children from hierarchy API
      val hierarchyUrl = s"${config.contentHierarchyURL}${programId}?edit=mode"
      logger.info(s"Fetching program hierarchy from: $hierarchyUrl")

      val hierarchyResponse = httpUtil.get(hierarchyUrl, config.defaultHeaders)

      if (hierarchyResponse.status != 200) {
        logger.error(s"Error fetching program hierarchy for programId=$programId: ${hierarchyResponse.status} - ${hierarchyResponse.body}")
        throw new Exception(s"Error fetching program hierarchy for programId=$programId: ${hierarchyResponse.status} - ${hierarchyResponse.body}")
      }

      val hierarchyResultMap = JSONUtil.deserialize[Map[String, AnyRef]](hierarchyResponse.body)
      val hierarchyResult = hierarchyResultMap.getOrElse("result", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
      val hierarchyContent = hierarchyResult.getOrElse("content", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]

      // Extract all child identifiers from the hierarchy
      val childNodes = extractLeafNodesFromHierarchy(hierarchyContent)

      logger.info(s"Extracted ${childNodes.size()} child identifiers from program hierarchy for programId=$programId")

      // Step 3: Combine results from both APIs
      val programInfoMap: java.util.Map[String, AnyRef] = new java.util.HashMap[String, AnyRef]()
      programInfoMap.put("courseId", programId)
      programInfoMap.put("name", programName)
      programInfoMap.put("badgeDetails_v1", badgeDetails_v1)
      programInfoMap.put("primaryCategory", primaryCategory)
      programInfoMap.put("childNodes", childNodes)
      programInfoMap.put("parentCollections", new java.util.ArrayList[String]())

      programInfoMap
    } catch {
      case ex: Exception =>
        logger.error(s"Error fetching program data for programId=$programId", ex)
        throw new Exception(s"Error fetching program data for programId=$programId: ${ex.getMessage}", ex)
    }
  }

  /**
   * Extract identifiers from the immediate children array of the program
   * Only fetches identifiers from the first level children, not nested children
   */
  private def extractLeafNodesFromHierarchy(content: Map[String, AnyRef]): java.util.List[String] = {
    import scala.collection.JavaConverters._
    val leafNodes = new java.util.ArrayList[String]()

    // Get the children array from the content
    val children = content.get("children") match {
      case Some(c: java.util.List[_]) => c.asScala.toList
      case Some(c: List[_]) => c
      case _ => List.empty
    }

    // Extract identifier from each child in the children array
    children.foreach {
      case childMap: Map[_, _] =>
        val child = childMap.asInstanceOf[Map[String, AnyRef]]
        val identifier = child.getOrElse("identifier", "").asInstanceOf[String]
        if (identifier.nonEmpty) {
          leafNodes.add(identifier)
          logger.debug(s"Found child identifier: $identifier")
        }
      case childJavaMap: java.util.Map[_, _] =>
        val child = childJavaMap.asInstanceOf[java.util.Map[String, AnyRef]].asScala.toMap
        val identifier = child.getOrElse("identifier", "").asInstanceOf[String]
        if (identifier.nonEmpty) {
          leafNodes.add(identifier)
          logger.debug(s"Found child identifier: $identifier")
        }
      case _ => // Skip unexpected types
    }

    logger.info(s"Extracted ${leafNodes.size()} identifiers from children array: ${leafNodes.asScala.mkString(", ")}")
    leafNodes
  }

  // Add new method for extCourses
  private def processExtCourses(event: Event, metrics: Metrics): Unit = {
    val userId = event.userId
    val courseId = event.contentId

    val badgeCheckQuery =
      s"""
           SELECT badgeid
           FROM ${config.coursesdb}.${config.badgeLookUpTable}
           WHERE userid='$userId'
           AND courseid='$courseId';
         """

    val existingBadgeRows = cassandraUtil.find(badgeCheckQuery)
    if (existingBadgeRows != null && !existingBadgeRows.isEmpty) {
      logger.debug(s"Badge already awarded for userId=$userId, programId=$courseId (found in lookup table). Skipping badge processing.")
      metrics.incCounter(config.skippedEventCount)
      return
    }

    val extContentReadUrl = config.extContentUrl
    val contentUrl = s"$extContentReadUrl$courseId"
    val cachedMetadata = contentCache.getWithRetry(courseId)

    // Build courseMetadata map to pass to badge awarding function
    val courseMetadataMap = new java.util.HashMap[String, AnyRef]()

    if (cachedMetadata != null && cachedMetadata.contains(config.extContentResponseKey)) {
      val contentMap = cachedMetadata(config.extContentResponseKey).asInstanceOf[java.util.Map[String, AnyRef]]

      // Extract badgeDetails_v1 if present
      val badgeDetailsV1 = contentMap.get(config.badgeDetailsV1Key)
      if (badgeDetailsV1 != null) {
        courseMetadataMap.put(config.badgeDetailsV1Key, badgeDetailsV1)
      }

      // Extract name if present
      val courseName = contentMap.get("name")
      if (courseName != null) {
        courseMetadataMap.put("name", courseName)
      }
    } else {
      logger.warn(
        s"Key '${config.extContentResponseKey}' not found in courseMetadata for courseId=$courseId. Falling back to API call."
      )
      val response = getExtContentAPICall(contentUrl)(config, httpUtil, metrics)

      // Extract badgeDetails_v1 if present
      val badgeDetailsV1 = response.get(config.badgeDetailsV1Key)
      if (badgeDetailsV1 != null) {
        courseMetadataMap.put(config.badgeDetailsV1Key, badgeDetailsV1)
      }

      // Extract name if present
      val courseName = response.get("name")
      if (courseName != null) {
        courseMetadataMap.put("name", courseName)
      }
    }

    // Process badge awarding for extCourses
    processBadgeAwardingForExtCourses(userId, courseId, courseMetadataMap, metrics)
  }

  /**
   * Process badge awarding for iGOTCourses
   * Handles BOTH course-level badge awarding AND program-level badge awarding
   */
  private def processBadgeAwardingForIGOTCourses(
                                                  userId: String,
                                                  courseId: String,
                                                  batchId: String,
                                                  courseMetadata: java.util.Map[String, AnyRef],
                                                  metrics: Metrics
                                                ): Unit = {
    try {
      import scala.collection.JavaConverters._

      // Extract courseName from courseMetadata
      val courseName = Option(courseMetadata.get("name")).map(_.toString).getOrElse(courseId)

      // EXISTING LOGIC: Process course-level badge awarding
      processCourseLevelBadgeAwarding(userId, courseId, batchId, courseMetadata, courseName, metrics)

      // NEW LOGIC: Process program-level badge awarding for curated programs
      val primaryCategory = Option(courseMetadata.get("primaryCategory")).map(_.toString).getOrElse("")

      // Check if primaryCategory is "Course"
      if (primaryCategory.equalsIgnoreCase("Course")) {
        logger.info(s"Processing Course category for courseId=$courseId")

        // Get parentCollections
        val parentCollectionsRaw = courseMetadata.get("parentCollections")
        if (parentCollectionsRaw != null) {
          val parentCollections = parentCollectionsRaw match {
            case jl: java.util.List[_] => jl.asScala.toList.map(_.toString)
            case sl: Seq[_] => sl.toList.map(_.toString)
            case _ =>
              logger.warn(s"parentCollections is not a list for courseId=$courseId")
              List.empty[String]
          }

          if (parentCollections.nonEmpty) {
            // Loop through each parent collection (program)
            parentCollections.foreach { programId =>
              logger.info(s"Processing program: $programId for courseId=$courseId")
              processProgramBadgeAwarding(userId, programId, batchId, metrics)
            }
          } else {
            logger.info(s"parentCollections is empty for courseId=$courseId")
          }
        } else {
          logger.info(s"No parentCollections found for courseId=$courseId")
        }
      } else {
        logger.info(s"primaryCategory is not 'Course' for courseId=$courseId, skipping program badge processing")
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing badge awarding for iGOTCourses userId=$userId, courseId=$courseId, batchId=$batchId", ex)
        throw new InvalidEventException(ex.getMessage, Map("userId" -> userId, "courseId" -> courseId, "batchId" -> batchId), ex)
    }
  }

  /**
   * EXISTING LOGIC: Process course-level badge awarding
   * Checks if content has badgeDetails_v1, then processes badge awarding based on badgeEarningDateEnabled
   */
  private def processCourseLevelBadgeAwarding(
                                               userId: String,
                                               courseId: String,
                                               batchId: String,
                                               courseMetadata: java.util.Map[String, AnyRef],
                                               courseName: String,
                                               metrics: Metrics
                                             ): Unit = {
    try {
      import scala.collection.JavaConverters._

      // Check if badgeDetails_v1 exists in course metadata
      val badgeDetailsV1Raw = courseMetadata.get(config.badgeDetailsV1Key)
      if (badgeDetailsV1Raw == null) {
        logger.info(s"No badgeDetails_v1 found for courseId=$courseId, skipping course-level badge awarding")
        return
      }

      // badgeDetails_v1 is an array/list of badge objects
      val badgeDetailsList = badgeDetailsV1Raw match {
        case jl: java.util.List[_] => jl.asScala.toList
        case sl: Seq[_] => sl.toList
        case _ =>
          logger.warn(s"badgeDetails_v1 is not a list for courseId=$courseId, skipping course-level badge awarding")
          return
      }

      if (badgeDetailsList.isEmpty) {
        logger.info(s"badgeDetails_v1 is empty for courseId=$courseId, skipping course-level badge awarding")
        return
      }

      // Convert Scala Map to Java Map
      val badgeDetailsObj = badgeDetailsList.head match {
        case jm: java.util.Map[_, _] => jm.asInstanceOf[java.util.Map[String, AnyRef]]
        case sm: Map[_, _] => new java.util.HashMap[String, AnyRef](sm.asInstanceOf[Map[String, AnyRef]].asJava)
        case _ =>
          logger.warn(s"Unexpected badge details type for courseId=$courseId, skipping course-level badge awarding")
          return
      }

      val criteria = Option(badgeDetailsObj.get(config.criteriaKey)).map(_.toString).getOrElse("")
      val badgeTemplate = Option(badgeDetailsObj.get(config.badgeTemplateKey)).map(_.toString).getOrElse("")
      val badgeId = Option(badgeDetailsObj.get(config.badgeIdKey)).map(_.toString).getOrElse("")
      val badgeTitle = Option(badgeDetailsObj.get("badgeTitle")).map(_.toString).getOrElse("")

      if (criteria.isEmpty || badgeTemplate.isEmpty || badgeId.isEmpty) {
        logger.warn(s"Incomplete badge details for courseId=$courseId, skipping course-level badge awarding")
        return
      }

      val badgeCheckQuery =
        s"""
           SELECT badgeid
           FROM ${config.coursesdb}.${config.badgeLookUpTable}
           WHERE userid='$userId'
           AND courseid='$courseId';
         """

      val existingBadgeRows = cassandraUtil.findOne(badgeCheckQuery)
      if (existingBadgeRows != null) {
        logger.debug(s"Badge already awarded for userId=$userId, programId=$courseId (found in lookup table). Skipping badge processing.")
        metrics.incCounter(config.skippedEventCount)
        return
      }

      // Check badgeEarningDateEnabled
      val badgeEarningDateEnabled = Option(badgeDetailsObj.get(config.badgeEarningDateEnabledKey))
        .map(_.toString.toBoolean)
        .getOrElse(false)

      val currentTime = System.currentTimeMillis()
      var shouldAwardBadge = false

      if (!badgeEarningDateEnabled) {
        // If badgeEarningDateEnabled is false, award badge immediately
        shouldAwardBadge = true
        logger.info(s"badgeEarningDateEnabled=false for courseId=$courseId, awarding badge immediately")
      } else {
        // If badgeEarningDateEnabled is true, check badgeEarningDateTime against lastIssuedOn
        val badgeEarningDateTime: Long = Option(badgeDetailsObj.get(config.badgeEarningDateTimeKey))
          .map(value => parseBadgeEarningDateTime(value))
          .getOrElse(0L)

        if (badgeEarningDateTime == 0L) {
          logger.warn(s"badgeEarningDateTime not found or invalid for courseId=$courseId, skipping course-level badge awarding")
          return
        }

        // Read user_enrolments_v2 to get issued_certificates and lastIssuedOn
        val enrolmentQuery =
          s"""
             SELECT issued_certificates
             FROM ${config.coursesdb}.${config.enrolmentTable}
             WHERE userid='$userId'
             AND courseid='$courseId'
             AND batchid='$batchId';
           """

        val enrolmentRows = cassandraUtil.find(enrolmentQuery)
        if (enrolmentRows != null && !enrolmentRows.isEmpty) {
          val row = enrolmentRows.get(0)
          val certsRaw = row.getObject(config.extContentUserExternalEnrolmentsIssuedCertificatesKey)
            .asInstanceOf[java.util.List[java.util.Map[String, String]]]

          if (certsRaw != null && !certsRaw.isEmpty) {
            import scala.collection.JavaConverters._
            val issuedCertificates = certsRaw.asScala.toList

            // Get the latest lastIssuedOn value - parse ISO date strings to timestamps
            val lastIssuedOnValues = issuedCertificates
              .flatMap(cert => Option(cert.get(config.lastIssuedOnKey)))
              .map { dateStr =>
                try {
                  // Try to parse as long first (in case it's already a timestamp)
                  dateStr.toLong
                } catch {
                  case _: NumberFormatException =>
                    // If it fails, parse as ISO 8601 date string
                    parseIsoDateToMillis(dateStr)
                }
              }
              .filter(_ > 0) // Filter out invalid dates
              .sorted

            if (lastIssuedOnValues.nonEmpty) {
              val latestLastIssuedOn = lastIssuedOnValues.last

              // Check if badgeEarningDateTime > lastIssuedOn
              if (badgeEarningDateTime > latestLastIssuedOn) {
                shouldAwardBadge = true
                logger.info(s"badgeEarningDateTime ($badgeEarningDateTime) > lastIssuedOn ($latestLastIssuedOn) for courseId=$courseId, awarding badge")
              } else {
                logger.info(s"badgeEarningDateTime ($badgeEarningDateTime) <= lastIssuedOn ($latestLastIssuedOn) for courseId=$courseId, skipping course-level badge awarding")
              }
            }
          }
        }
      }

      // Update issued_badges if shouldAwardBadge is true
      if (shouldAwardBadge) {
        import java.text.SimpleDateFormat
        import java.util.TimeZone

        val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        val formattedIssuedOn = dateFormat.format(new java.util.Date(currentTime))

        val badgeMap = new java.util.HashMap[String, String]()
        badgeMap.put(config.badgeIdKey, badgeId)
        badgeMap.put(config.criteriaKey, criteria)
        badgeMap.put(config.templateUrlKey, badgeTemplate)
        badgeMap.put(config.issuedOnKey, formattedIssuedOn)

        val badgeList = new java.util.ArrayList[java.util.Map[String, String]]()
        badgeList.add(badgeMap)

        // Update user_enrolments_v2 with issued_badges
        val updateQuery =
          s"""
             UPDATE ${config.coursesdb}.${config.enrolmentTable}
             SET issued_badges = ?
             WHERE userid='$userId'
             AND courseid='$courseId'
             AND batchid='$batchId';
           """

        val preparedStmt = cassandraUtil.session.prepare(updateQuery)
        val boundStmt = preparedStmt.bind(badgeList)
        cassandraUtil.session.execute(boundStmt)

        // Insert into badge lookup table
        val lookupInsertQuery =
          s"""
             INSERT INTO ${config.coursesdb}.${config.badgeLookUpTable}
             (userid, courseid, badgeid, criteria, templateurl, issuedon)
             VALUES (?, ?, ?, ?, ?, ?);
           """

        val lookupStmt = cassandraUtil.session.prepare(lookupInsertQuery)
        val lookupBoundStmt = lookupStmt.bind(
          userId,
          courseId,
          badgeId,
          criteria,
          badgeTemplate,
          new java.util.Date(currentTime)
        )
        cassandraUtil.session.execute(lookupBoundStmt)

        logger.info(s"Successfully awarded course-level badge for userId=$userId, courseId=$courseId, batchId=$batchId")
        logger.info(s"Inserted badge into lookup table: userId=$userId, courseId=$courseId, badgeId=$badgeId")

        // Delete badge count cache from Redis
        updateBadgeCountCache(userId)

        // Push recent badge activity to Redis
        if (badgeTitle.nonEmpty) {
          pushRecentBadgeActivity(userId, badgeId, badgeTitle)
        }
        // Send notification for badge award
        sendBadgeAwardNotification(userId, badgeTitle, courseName)

        metrics.incCounter(config.dbUpdateCount)
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing course-level badge awarding for userId=$userId, courseId=$courseId, batchId=$batchId", ex)
        throw new InvalidEventException(ex.getMessage, Map("userId" -> userId, "courseId" -> courseId, "batchId" -> batchId), ex)
    }
  }

  /**
   * Process badge awarding for a program
   */
  private def processProgramBadgeAwarding(userId: String, programId: String, batchId: String, metrics: Metrics): Unit = {
    try {
      import scala.collection.JavaConverters._

      val programMetadata: java.util.Map[String, AnyRef] = getProgramHierarchy(programId)(metrics, config, httpUtil)
      val badgeDetailsV1Raw = programMetadata.get(config.badgeDetailsV1Key)
      if (badgeDetailsV1Raw == null) {
        logger.info(s"No badgeDetails_v1 found for programId=$programId")
        return
      }

      // badgeDetails_v1 is an array/list of badge objects
      val badgeDetailsList = badgeDetailsV1Raw match {
        case jl: java.util.List[_] => jl.asScala.toList
        case sl: Seq[_] => sl.toList
        case _ =>
          logger.warn(s"badgeDetails_v1 is not a list for programId=$programId")
          return
      }

      if (badgeDetailsList.isEmpty) {
        logger.info(s"badgeDetails_v1 is empty for programId=$programId")
        return
      }

      // Convert to Java Map
      val badgeDetailsObj = badgeDetailsList.head match {
        case jm: java.util.Map[_, _] => jm.asInstanceOf[java.util.Map[String, AnyRef]]
        case sm: Map[_, _] => new java.util.HashMap[String, AnyRef](sm.asInstanceOf[Map[String, AnyRef]].asJava)
        case _ =>
          logger.warn(s"Unexpected badge details type for programId=$programId")
          return
      }

      val criteria = Option(badgeDetailsObj.get(config.criteriaKey)).map(_.toString).getOrElse("")

      // Check if criteria is "partialRandomCompletion"
      if (!criteria.equalsIgnoreCase("partialRandomCompletion")) {
        logger.info(s"Criteria is not 'partialRandomCompletion' for programId=$programId, skipping")
        return
      }

      val badgeTemplate = Option(badgeDetailsObj.get(config.badgeTemplateKey)).map(_.toString).getOrElse("")
      val badgeId = Option(badgeDetailsObj.get(config.badgeIdKey)).map(_.toString).getOrElse("")
      val badgeTitle = Option(badgeDetailsObj.get("badgeTitle")).map(_.toString).getOrElse("")

      // Handle requiredCourseCompletions as it can be Double (1.0) or Integer (1)
      val requiredCompletionCount = Option(badgeDetailsObj.get("requiredCourseCompletions"))
        .orElse(Option(badgeDetailsObj.get("requiredCompletionCount")))
        .map { value =>
          try {
            value match {
              case d: java.lang.Double => d.toInt
              case f: java.lang.Float => f.toInt
              case i: java.lang.Integer => i.intValue()
              case l: java.lang.Long => l.toInt
              case s: String => s.toDouble.toInt
              case _ => value.toString.toDouble.toInt
            }
          } catch {
            case ex: Exception =>
              logger.error(s"Failed to parse requiredCompletionCount: $value", ex)
              throw new Exception(s"Invalid requiredCompletionCount value: $value", ex)
          }
        }
        .getOrElse(0)

      if (badgeTemplate.isEmpty || badgeId.isEmpty || requiredCompletionCount == 0) {
        logger.warn(s"Incomplete badge details for programId=$programId")
        return
      }

      val badgeCheckQuery =
        s"""
           SELECT badgeid
           FROM ${config.coursesdb}.${config.badgeLookUpTable}
           WHERE userid='$userId'
           AND courseid='$programId';
         """

      val existingBadgeRows = cassandraUtil.findOne(badgeCheckQuery)
      if (existingBadgeRows != null) {
        logger.debug(s"Badge already awarded for userId=$userId, programId=$programId (found in lookup table). Skipping badge processing.")
        metrics.incCounter(config.skippedEventCount)
        return
      }

      val programName = Option(programMetadata.get("name")).map(_.toString).getOrElse(programId)

      // Check if user is enrolled in the program and get batchId
      val programEnrollmentQuery =
        s"""
           SELECT batchid
           FROM ${config.coursesdb}.${config.enrolmentTable}
           WHERE userid='$userId'
           AND courseid='$programId';
         """

      val programEnrollmentRows = cassandraUtil.findOne(programEnrollmentQuery)
      if (programEnrollmentRows == null) {
        logger.info(s"User not enrolled in program. userId=$userId, programId=$programId. Skipping badge award.")
        return
      }

      // Get the actual batchId from enrollment record
      val programBatchId = programEnrollmentRows.getString("batchid")
      logger.info(s"User enrolled in program verified. userId=$userId, programId=$programId, batchId=$programBatchId")

      // Check badgeEarningDateEnabled
      val badgeEarningDateEnabled = Option(badgeDetailsObj.get(config.badgeEarningDateEnabledKey))
        .map(_.toString.toBoolean)
        .getOrElse(false)

      val currentTime = System.currentTimeMillis()
      var isEligible = false

      if (!badgeEarningDateEnabled) {
        // If badgeEarningDateEnabled is false, user is eligible
        isEligible = true
        logger.info(s"badgeEarningDateEnabled=false for programId=$programId, user is eligible")
      } else {
        val badgeEarningDateTime: Long = Option(badgeDetailsObj.get(config.badgeEarningDateTimeKey))
          .map(value => parseBadgeEarningDateTime(value))
          .getOrElse(0L)

        if (badgeEarningDateTime == 0L) {
          logger.warn(s"badgeEarningDateTime not found or invalid for programId=$programId")
          return
        }

        if (badgeEarningDateTime > currentTime) {
          isEligible = true
          logger.info(s"badgeEarningDateTime ($badgeEarningDateTime) > currentTime ($currentTime) for programId=$programId, user is eligible")
        } else {
          logger.info(s"badgeEarningDateTime ($badgeEarningDateTime) <= currentTime ($currentTime) for programId=$programId, user is not eligible")
          return
        }
      }

      // If user is eligible, check course completion
      if (isEligible) {
        // Get leaf nodes for the program
        val childNodesRaw = programMetadata.get("childNodes")
        if (childNodesRaw == null) {
          logger.info(s"No childNodes found for programId=$programId")
          return
        }

        val childNodes = childNodesRaw match {
          case jl: java.util.List[_] => jl.asScala.toList.map(_.toString)
          case sl: Seq[_] => sl.toList.map(_.toString)
          case _ =>
            logger.warn(s"childNodes is not a list for programId=$programId")
            return
        }

        if (childNodes.isEmpty) {
          logger.info(s"childNodes is empty for programId=$programId")
          return
        }

        val courseIdsStr = childNodes.map(id => s"'$id'").mkString(",")
        val batchEnrolmentQuery =
          s"""
             SELECT courseid, status
             FROM ${config.coursesdb}.${config.enrolmentTable}
             WHERE userid='$userId'
             AND courseid IN ($courseIdsStr);
           """

        logger.info(s"Fetching completion status for ${childNodes.size} courses in single query for userId=$userId")
        val enrolmentRows = cassandraUtil.find(batchEnrolmentQuery)

        var completedCount = 0
        if (enrolmentRows != null && !enrolmentRows.isEmpty) {
          import scala.collection.JavaConverters._
          enrolmentRows.asScala.foreach { row =>
            val courseId = row.getString("courseid")
            val status = row.getInt("status")
            if (status == 2) {
              completedCount += 1
              logger.debug(s"Course $courseId completed for userId=$userId")
            }
          }
        }

        logger.info(s"User completed $completedCount out of ${childNodes.size} courses, required: $requiredCompletionCount for programId=$programId")

        // Check if user has completed required number of courses
        if (completedCount >= requiredCompletionCount) {
          awardProgramBadge(userId, programId, programBatchId, badgeId, criteria, badgeTemplate, badgeTitle, currentTime,programName, metrics)
        } else {
          logger.info(s"User has not completed required courses for programId=$programId")
        }
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing program badge awarding for userId=$userId, programId=$programId", ex)
    }
  }

  /**
   * Award badge for program
   */
  private def awardProgramBadge(userId: String, programId: String, batchId: String, badgeId: String,
                                 criteria: String, badgeTemplate: String, badgeTitle: String,
                                 currentTime: Long,programName : String, metrics: Metrics): Unit = {
    try {
      import java.text.SimpleDateFormat
      import java.util.TimeZone

      val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
      dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
      val formattedIssuedOn = dateFormat.format(new java.util.Date(currentTime))

      val badgeMap = new java.util.HashMap[String, String]()
      badgeMap.put(config.badgeIdKey, badgeId)
      badgeMap.put(config.criteriaKey, criteria)
      badgeMap.put(config.templateUrlKey, badgeTemplate)
      badgeMap.put(config.issuedOnKey, formattedIssuedOn)

      val badgeList = new java.util.ArrayList[java.util.Map[String, String]]()
      badgeList.add(badgeMap)

      // Insert/Update user_enrolments_v2 with issued_badges for program
      val updateEnrolmentQuery =
        s"""
           UPDATE ${config.coursesdb}.${config.enrolmentTable}
           SET issued_badges = ?
           WHERE userid='$userId'
           AND courseid='$programId'
           AND batchid='$batchId';
         """

      val preparedStmt = cassandraUtil.session.prepare(updateEnrolmentQuery)
      val boundStmt = preparedStmt.bind(badgeList)
      cassandraUtil.session.execute(boundStmt)

      logger.info(s"Updated issued_badges in user_enrolments_v2 for userId=$userId, programId=$programId, batchId=$batchId")

      // Insert into badge lookup table
      val lookupInsertQuery =
        s"""
           INSERT INTO ${config.coursesdb}.${config.badgeLookUpTable}
           (userid, courseid, badgeid, criteria, templateurl, issuedon)
           VALUES (?, ?, ?, ?, ?, ?);
         """

      val lookupStmt = cassandraUtil.session.prepare(lookupInsertQuery)
      val lookupBoundStmt = lookupStmt.bind(
        userId,
        programId,
        badgeId,
        criteria,
        badgeTemplate,
        new java.util.Date(currentTime)
      )
      cassandraUtil.session.execute(lookupBoundStmt)

      logger.info(s"Successfully awarded badge for userId=$userId, programId=$programId, badgeId=$badgeId")

      // Delete badge count cache from Redis
      updateBadgeCountCache(userId)

      // Push recent badge activity to Redis
      if (badgeTitle.nonEmpty) {
        pushRecentBadgeActivity(userId, badgeId, badgeTitle)
      }
      // Send notification for badge award
      sendBadgeAwardNotification(userId, badgeTitle, programName)

      metrics.incCounter(config.dbUpdateCount)
    } catch {
      case ex: Exception =>
        logger.error(s"Error awarding program badge for userId=$userId, programId=$programId", ex)
    }
  }

  /**
   * Send notification when badge is awarded
   */
  private def sendBadgeAwardNotification(userId: String, badgeTitle: String, courseName: String): Unit = {
    if (!config.notificationEnabled) {
      logger.info(s"Notification sending is disabled. Skipping notification for userId=$userId")
      return
    }

    try {
      val notificationPayload = Map(
        "subCategory" -> config.notificationBadgeSubCategory,
        "subType" -> config.notificationBadgeSubType,
        "userIds" -> List(userId),
        "message" -> Map(
          "placeholders" -> Map(
            "badgeTitle" -> badgeTitle,
            "courseName" -> courseName
          )
        )
      )

      val notificationJson = ScalaJsonUtil.serialize(notificationPayload)
      val response = httpUtil.post(config.notificationServiceUrl, notificationJson, config.defaultHeaders)

      if (response.status == 200) {
        logger.info(s"Notification sent successfully for userId=$userId, badgeTitle=$badgeTitle")
      } else {
        logger.warn(s"Failed to send notification for userId=$userId. Status: ${response.status}, Response: ${response.body}")
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error sending notification for userId=$userId, badgeTitle=$badgeTitle", ex)
        throw new Exception(s"Error sending notification for userId=$userId, badgeTitle=$badgeTitle: ${ex.getMessage}", ex)
    }
  }

  /**
   * Delete badge count cache from Redis
   * This is called after a badge is awarded to invalidate the cached badge count
   */
  private def updateBadgeCountCache(userId: String): Unit = {
    try {
      val redisKey = s"user:badgeCount_$userId"

      // Query badge count from user_badge_lookup table
      val badgeCountQuery =
        s"""
           SELECT COUNT(userid) as badge_count
           FROM ${config.coursesdb}.${config.badgeLookUpTable}
           WHERE userid='$userId';
         """

      val badgeCountRows = cassandraUtil.find(badgeCountQuery)

      if (badgeCountRows != null && !badgeCountRows.isEmpty) {
        val badgeCount = badgeCountRows.get(0).getLong("badge_count")
        cache.setWithRetryAndTTL(redisKey, badgeCount.toString)
        logger.info(s"Updated badge count cache in Redis (index 2) for userId=$userId, key=$redisKey, count=$badgeCount")
      } else {
        cache.set(redisKey, "0")
        logger.info(s"Updated badge count cache in Redis (index 2) for userId=$userId, key=$redisKey, count=0")
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error updating badge count cache from Redis for userId=$userId", ex)
    }
  }

  /**
   * Process badge awarding for extCourses
   * Checks if content has badgeDetails_v1, then processes badge awarding based on badgeEarningDateEnabled
   */
  private def processBadgeAwardingForExtCourses(
                                                 userId: String,
                                                 courseId: String,
                                                 courseMetadata: java.util.Map[String, AnyRef],
                                                 metrics: Metrics
                                               ): Unit = {
    try {
      import scala.collection.JavaConverters._

      // Extract courseName from courseMetadata
      val courseName = Option(courseMetadata.get("name")).map(_.toString).getOrElse(courseId)

      // Check if badgeDetails_v1 exists in course metadata
      val badgeDetailsV1Raw = courseMetadata.get(config.badgeDetailsV1Key)
      if (badgeDetailsV1Raw == null) {
        logger.info(s"No badgeDetails_v1 found for extCourseId=$courseId, skipping badge awarding")
        return
      }

      // badgeDetails_v1 is an array/list of badge objects
      val badgeDetailsList = badgeDetailsV1Raw match {
        case jl: java.util.List[_] => jl.asScala.toList
        case sl: Seq[_] => sl.toList
        case _ =>
          logger.warn(s"badgeDetails_v1 is not a list for extCourseId=$courseId, skipping badge awarding")
          return
      }

      if (badgeDetailsList.isEmpty) {
        logger.info(s"badgeDetails_v1 is empty for extCourseId=$courseId, skipping badge awarding")
        return
      }

      // Convert Scala Map to Java Map
      val badgeDetailsObj = badgeDetailsList.head match {
        case jm: java.util.Map[_, _] => jm.asInstanceOf[java.util.Map[String, AnyRef]]
        case sm: Map[_, _] => new java.util.HashMap[String, AnyRef](sm.asInstanceOf[Map[String, AnyRef]].asJava)
        case _ =>
          logger.warn(s"Unexpected badge details type for extCourseId=$courseId, skipping badge awarding")
          return
      }

      val criteria = Option(badgeDetailsObj.get(config.criteriaKey)).map(_.toString).getOrElse("")
      val badgeTemplate = Option(badgeDetailsObj.get(config.badgeTemplateKey)).map(_.toString).getOrElse("")
      val badgeId = Option(badgeDetailsObj.get(config.badgeIdKey)).map(_.toString).getOrElse("")
      val badgeTitle = Option(badgeDetailsObj.get("badgeTitle")).map(_.toString).getOrElse("")

      if (criteria.isEmpty || badgeTemplate.isEmpty || badgeId.isEmpty) {
        logger.warn(s"Incomplete badge details for extCourseId=$courseId, skipping badge awarding")
        return
      }

      val badgeCheckQuery =
        s"""
           SELECT badgeid
           FROM ${config.coursesdb}.${config.badgeLookUpTable}
           WHERE userid='$userId'
           AND courseid='$courseId';
         """

      val existingBadgeRows = cassandraUtil.findOne(badgeCheckQuery)
      if (existingBadgeRows != null) {
        logger.debug(s"Badge already awarded for userId=$userId, programId=$courseId (found in lookup table). Skipping badge processing.")
        metrics.incCounter(config.skippedEventCount)
        return
      }

      // Check badgeEarningDateEnabled
      val badgeEarningDateEnabled = Option(badgeDetailsObj.get(config.badgeEarningDateEnabledKey))
        .map(_.toString.toBoolean)
        .getOrElse(false)

      val currentTime = System.currentTimeMillis()
      var shouldAwardBadge = false

      if (!badgeEarningDateEnabled) {
        // If badgeEarningDateEnabled is false, award badge immediately
        shouldAwardBadge = true
        logger.info(s"badgeEarningDateEnabled=false for extCourseId=$courseId, awarding badge immediately")
      } else {
        // If badgeEarningDateEnabled is true, check badgeEarningDateTime against lastIssuedOn
        val badgeEarningDateTime: Long = Option(badgeDetailsObj.get(config.badgeEarningDateTimeKey))
          .map(value => parseBadgeEarningDateTime(value))
          .getOrElse(0L)

        if (badgeEarningDateTime == 0L) {
          logger.warn(s"badgeEarningDateTime not found or invalid for extCourseId=$courseId, skipping badge awarding")
          return
        }

        // Read user_external_enrolments to get issued_certificates and lastIssuedOn
        val externalEnrolQuery =
          s"""
             SELECT issued_certificates
             FROM ${config.coursesdb}.${config.externalEnrolmentTable}
             WHERE userid='$userId'
             AND courseid='$courseId';
           """

        val enrolmentRows = cassandraUtil.find(externalEnrolQuery)
        if (enrolmentRows != null && !enrolmentRows.isEmpty) {
          val row = enrolmentRows.get(0)
          val certsRaw = row.getObject(config.extContentUserExternalEnrolmentsIssuedCertificatesKey)
            .asInstanceOf[java.util.List[java.util.Map[String, String]]]

          if (certsRaw != null && !certsRaw.isEmpty) {
            import scala.collection.JavaConverters._
            val issuedCertificates = certsRaw.asScala.toList

            // Get the latest lastIssuedOn value - parse ISO date strings to timestamps
            val lastIssuedOnValues = issuedCertificates
              .flatMap(cert => Option(cert.get(config.lastIssuedOnKey)))
              .map { dateStr =>
                try {
                  // Try to parse as long first (in case it's already a timestamp)
                  dateStr.toLong
                } catch {
                  case _: NumberFormatException =>
                    // If it fails, parse as ISO 8601 date string
                    parseIsoDateToMillis(dateStr)
                }
              }
              .filter(_ > 0) // Filter out invalid dates
              .sorted

            if (lastIssuedOnValues.nonEmpty) {
              val latestLastIssuedOn = lastIssuedOnValues.last

              // Check if badgeEarningDateTime > lastIssuedOn
              if (badgeEarningDateTime > latestLastIssuedOn) {
                shouldAwardBadge = true
                logger.info(s"badgeEarningDateTime ($badgeEarningDateTime) > lastIssuedOn ($latestLastIssuedOn) for extCourseId=$courseId, awarding badge")
              } else {
                logger.info(s"badgeEarningDateTime ($badgeEarningDateTime) <= lastIssuedOn ($latestLastIssuedOn) for extCourseId=$courseId, skipping badge awarding")
              }
            }
          }
        }
      }

      // Update issued_badges if shouldAwardBadge is true
      if (shouldAwardBadge) {
        import java.text.SimpleDateFormat
        import java.util.TimeZone

        val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        val formattedIssuedOn = dateFormat.format(new java.util.Date(currentTime))

        val badgeMap = new java.util.HashMap[String, String]()
        badgeMap.put(config.badgeIdKey, badgeId)
        badgeMap.put(config.criteriaKey, criteria)
        badgeMap.put(config.templateUrlKey, badgeTemplate)
        badgeMap.put(config.issuedOnKey, formattedIssuedOn)

        val badgeList = new java.util.ArrayList[java.util.Map[String, String]]()
        badgeList.add(badgeMap)

        // Update user_external_enrolments with issued_badges
        val updateQuery =
          s"""
             UPDATE ${config.coursesdb}.${config.externalEnrolmentTable}
             SET issued_badges = ?
             WHERE userid='$userId'
             AND courseid='$courseId';
           """

        val preparedStmt = cassandraUtil.session.prepare(updateQuery)
        val boundStmt = preparedStmt.bind(badgeList)
        cassandraUtil.session.execute(boundStmt)

        // Insert into badge lookup table
        val lookupInsertQuery =
          s"""
             INSERT INTO ${config.coursesdb}.${config.badgeLookUpTable}
             (userid, courseid, badgeid, criteria, templateurl, issuedon)
             VALUES (?, ?, ?, ?, ?, ?);
           """

        val lookupStmt = cassandraUtil.session.prepare(lookupInsertQuery)
        val lookupBoundStmt = lookupStmt.bind(
          userId,
          courseId,
          badgeId,
          criteria,
          badgeTemplate,
          new java.util.Date(currentTime)
        )
        cassandraUtil.session.execute(lookupBoundStmt)

        logger.info(s"Successfully awarded badge for userId=$userId, extCourseId=$courseId")
        logger.info(s"Inserted badge into lookup table: userId=$userId, courseId=$courseId, badgeId=$badgeId")

        // Delete badge count cache from Redis
        updateBadgeCountCache(userId)

        // Push recent badge activity to Redis
        if (badgeTitle.nonEmpty) {
          pushRecentBadgeActivity(userId, badgeId, badgeTitle)
        }
        // Send notification for badge award
        sendBadgeAwardNotification(userId, badgeTitle, courseName)

        metrics.incCounter(config.dbUpdateCount)
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing badge awarding for extCourses userId=$userId, courseId=$courseId", ex)
    }
  }

  /**
   * Process program enrolment events for badge awarding
   * This method is called when contextType is "programEnrolment"
   */
  private def processProgramEnrolment(event: Event, metrics: Metrics): Unit = {
    try {
      val userId = event.userId
      val programId = event.contentId
      val batchId = event.batchId

      logger.info(s"Processing program enrolment event for userId=$userId, programId=$programId, batchId=$batchId")

      val badgeCheckQuery =
        s"""
           SELECT badgeid
           FROM ${config.coursesdb}.${config.badgeLookUpTable}
           WHERE userid='$userId'
           AND courseid='$programId';
         """

      val existingBadgeRows = cassandraUtil.find(badgeCheckQuery)
      if (existingBadgeRows != null && !existingBadgeRows.isEmpty) {
        logger.debug(s"Badge already awarded for userId=$userId, programId=$programId. Skipping badge processing (duplicate guard).")
        metrics.incCounter(config.skippedEventCount)
        return
      }
      logger.info(s"No existing badge found for userId=$userId, programId=$programId. Proceeding with badge processing.")
      // Fetch program metadata using hierarchy API
      val programMetadata: java.util.Map[String, AnyRef] = getCourseInfo(programId)(metrics, config, contentCache, httpUtil)
      // Extract program name from metadata
      val programName = Option(programMetadata.get("name")).map(_.toString).getOrElse(programId)
      // Check if badgeDetails_v1 exists
      val badgeDetailsV1Raw = programMetadata.get(config.badgeDetailsV1Key)
      if (badgeDetailsV1Raw == null) {
        logger.info(s"No badgeDetails_v1 found for programId=$programId. Skipping badge award.")
        metrics.incCounter(config.skippedEventCount)
        return
      }

      import scala.collection.JavaConverters._
      // badgeDetails_v1 is an array/list of badge objects
      val badgeDetailsList = badgeDetailsV1Raw match {
        case jl: java.util.List[_] => jl.asScala.toList
        case sl: Seq[_] => sl.toList
        case _ =>
          logger.warn(s"badgeDetails_v1 is not a list for programId=$programId")
          metrics.incCounter(config.skippedEventCount)
          return
      }

      if (badgeDetailsList.isEmpty) {
        logger.info(s"badgeDetails_v1 is empty for programId=$programId")
        metrics.incCounter(config.skippedEventCount)
        return
      }

      // Convert to Java Map
      val badgeDetailsObj = badgeDetailsList.head match {
        case jm: java.util.Map[_, _] => jm.asInstanceOf[java.util.Map[String, AnyRef]]
        case sm: Map[_, _] => new java.util.HashMap[String, AnyRef](sm.asInstanceOf[Map[String, AnyRef]].asJava)
        case _ =>
          logger.warn(s"Unexpected badge details type for programId=$programId")
          metrics.incCounter(config.skippedEventCount)
          return
      }

      // Validate criteria
      val criteria = Option(badgeDetailsObj.get(config.criteriaKey)).map(_.toString).getOrElse("")
      if (!criteria.equalsIgnoreCase("partialRandomCompletion")) {
        logger.info(s"Criteria is not 'partialRandomCompletion' for programId=$programId. Found: $criteria. Skipping.")
        metrics.incCounter(config.skippedEventCount)
        return
      }

      val badgeTemplate = Option(badgeDetailsObj.get(config.badgeTemplateKey)).map(_.toString).getOrElse("")
      val badgeId = Option(badgeDetailsObj.get(config.badgeIdKey)).map(_.toString).getOrElse("")
      val badgeTitle = Option(badgeDetailsObj.get("badgeTitle")).map(_.toString).getOrElse("")

      if (badgeTemplate.isEmpty || badgeId.isEmpty) {
        logger.warn(s"Incomplete badge details for programId=$programId. Skipping badge award.")
        metrics.incCounter(config.skippedEventCount)
        return
      }

      // Check if user is enrolled in the program
      val programEnrollmentQuery =
        s"""
           SELECT batchid
           FROM ${config.coursesdb}.${config.enrolmentTable}
           WHERE userid='$userId'
           AND courseid='$programId';
         """

      val programEnrollmentRows = cassandraUtil.find(programEnrollmentQuery)
      if (programEnrollmentRows == null || programEnrollmentRows.isEmpty) {
        logger.info(s"User not enrolled in program. userId=$userId, programId=$programId. Skipping badge award.")
        metrics.incCounter(config.skippedEventCount)
        return
      }

      // Get the actual batchId from enrollment record
      val programBatchId = programEnrollmentRows.get(0).getString("batchid")
      logger.info(s"User enrolled in program verified. userId=$userId, programId=$programId, batchId=$programBatchId")

      // Award the badge using the existing awardProgramBadge method
      val currentTime = System.currentTimeMillis()
      awardProgramBadge(userId, programId, programBatchId, badgeId, criteria, badgeTemplate, badgeTitle, currentTime, programName, metrics)

      logger.info(s"Successfully processed program enrolment badge award for userId=$userId, programId=$programId")
      metrics.incCounter(config.successEventCount)

    } catch {
      case ex: Exception =>
        logger.error(s"Error processing program enrolment event for userId=${event.userId}, programId=${event.contentId}", ex)
        metrics.incCounter(config.failedEventCount)
    }
  }
}
