package org.sunbird.job.programaggregate.common

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.cache.DataCache
import org.sunbird.job.programaggregate.task.ProgramActivityAggregateUpdaterConfig
import org.sunbird.job.util.{HttpUtil, ScalaJsonUtil}

import scala.collection.JavaConverters._

trait ContentHelper {

  private[this] val logger = LoggerFactory.getLogger(classOf[ContentHelper])
  @transient lazy val objectMapper: ObjectMapper =
    new ObjectMapper()
      .registerModule(DefaultScalaModule)
      .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)

  val courseInfoCache = new java.util.concurrent.ConcurrentHashMap[String, (java.util.Map[String, AnyRef], Long)]()
  def getCourseInfo(courseId: String)(
    metrics: Metrics,
    config: ProgramActivityAggregateUpdaterConfig,
    contentCache: DataCache,
    httpUtil: HttpUtil
  ): java.util.Map[String, AnyRef] = {

    val currentTime = System.currentTimeMillis()
    val cacheEntry = courseInfoCache.get(courseId)
    if (cacheEntry != null && cacheEntry._2 > currentTime) {
      logger.info(
        s"Fetching course details from in memory cache for Id: ${courseId}"
      )
      return cacheEntry._1
    }

    logger.info(
      s"Fetching course details from Redis for Id: ${courseId}, Configured Index: " + contentCache.getDBConfigIndex() + ", Current Index: " + contentCache.getDBIndex()
    )
    val courseMetadata = Option(contentCache).flatMap(c => Option(c.getWithRetry(courseId))).getOrElse(null)
    val finalCourseInfoMap = if (null == courseMetadata || courseMetadata.isEmpty) {
      logger.info(
        s"Fetching course details from Content Service for Id: ${courseId}"
      )
      //TODO: FETCH LANGUAGE ALSO.
      val url =
        config.contentReadURL + "/" + courseId + "?fields=" + config.contentReadFields
      val response = getAPICall(url, "content")(config, httpUtil, metrics)
      val courseName = StringContext
        .processEscapes(
          response.getOrElse(config.name, "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val primaryCategory = StringContext
        .processEscapes(
          response.getOrElse(config.primaryCategory, "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val versionKey = StringContext
        .processEscapes(
          response.getOrElse(config.versionKey, "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val parentCollections = response
        .getOrElse("parentCollections", List.empty[String])
        .asInstanceOf[List[String]]
      val courseCateogry = StringContext
        .processEscapes(response.getOrElse("courseCategory", "").asInstanceOf[String]).filter(_ >= ' ')
      val leafNodes = response
        .getOrElse("leafNodes", List.empty[String])
        .asInstanceOf[List[String]]
      val language = response
        .getOrElse("language", List.empty[String])
        .asInstanceOf[List[String]]
      val contextCategory = StringContext
        .processEscapes(response.getOrElse(config.contextCategory, "").asInstanceOf[String]).filter(_ >= ' ')
      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("courseName", courseName)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", primaryCategory)
      courseInfoMap.put("versionKey", versionKey)
      courseInfoMap.put("courseCategory", courseCateogry)
      courseInfoMap.put(config.contextCategory, contextCategory)
      val languageMapV1 = response.getOrElse("languageMapV1", Map.empty[String, AnyRef])
      courseInfoMap.put("languageMapV1", languageMapV1.asInstanceOf[AnyRef])
      courseInfoMap.put("leafNodes", leafNodes)
      courseInfoMap.put("language", language)
      val preliminaryAssessment = StringContext
        .processEscapes(
          response.getOrElse(config.preliminaryAssessment, "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      courseInfoMap.put(config.preliminaryAssessment, preliminaryAssessment)
      val milestonesV1 =
        response
          .getOrElse("milestonesv1", List.empty[Map[String, AnyRef]])
          .asInstanceOf[List[Map[String, AnyRef]]]
      courseInfoMap.put("milestonesv1", milestonesV1.asInstanceOf[AnyRef])
      val courseInfoMapString = objectMapper.writeValueAsString(courseInfoMap)
      contentCache.set(courseId, courseInfoMapString, config.courseCacheExpiry)
      courseInfoMap
    } else {
      val courseName = StringContext
        .processEscapes(
          courseMetadata.getOrElse(config.name, "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val primaryCategory = StringContext
        .processEscapes(
          courseMetadata
            .getOrElse("primarycategory", "")
            .asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val versionKey = StringContext
        .processEscapes(
          courseMetadata.getOrElse("versionkey", "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val parentCollections = courseMetadata
        .getOrElse("parentcollections", new java.util.ArrayList())
        .asInstanceOf[java.util.ArrayList[String]]
      val courseCateogry = StringContext
        .processEscapes(courseMetadata.getOrElse(config.coursecategory, "").asInstanceOf[String]).filter(_ >= ' ')
      val language = courseMetadata
        .getOrElse("language", new java.util.ArrayList())
        .asInstanceOf[java.util.ArrayList[String]]
      val contextCategory = StringContext
        .processEscapes(courseMetadata.getOrElse(config.contextCategory.toLowerCase, "").asInstanceOf[String]).filter(_ >= ' ')
      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      val preliminaryAssessment = StringContext
        .processEscapes(
          courseMetadata.getOrElse(config.preliminary_Assessment_Key, "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("courseName", courseName)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", primaryCategory)
      courseInfoMap.put("versionKey", versionKey)
      courseInfoMap.put("courseCategory", courseCateogry)
      courseInfoMap.put(config.contextCategory, contextCategory)
      courseInfoMap.put(config.preliminaryAssessment, preliminaryAssessment)
      val languageMapV1: Map[String, Map[String, AnyRef]] =
        toScalaNestedMap(courseMetadata.getOrElse("languagemapv1", new java.util.HashMap[String, Object]()))
      courseInfoMap.put("languageMapV1", languageMapV1.asInstanceOf[AnyRef])
      val leafNodes = courseMetadata
        .getOrElse("leafnodes", new java.util.ArrayList())
        .asInstanceOf[java.util.ArrayList[String]]
      courseInfoMap.put("leafNodes", leafNodes)
      courseInfoMap.put("language", language)
      val milestonesV1 =
        courseMetadata
          .getOrElse("milestonesv1", new java.util.ArrayList[java.util.Map[String, AnyRef]]())
          .asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
          .asScala
          .map(_.asScala.toMap)
          .toList
      courseInfoMap.put("milestonesv1", milestonesV1.asInstanceOf[AnyRef])
      courseInfoMap
    }
    courseInfoCache.put(courseId, (finalCourseInfoMap, currentTime + config.courseInMemoryCacheExpiry))
    finalCourseInfoMap
  }

  def getAPICall(url: String, responseParam: String)(
    config: ProgramActivityAggregateUpdaterConfig,
    httpUtil: HttpUtil,
    metrics: Metrics
  ): Map[String, AnyRef] = {
    val response = httpUtil.get(url, config.defaultHeaders)
    if (200 == response.status) {
      ScalaJsonUtil
        .deserialize[Map[String, AnyRef]](response.body)
        .getOrElse("result", Map[String, AnyRef]())
        .asInstanceOf[Map[String, AnyRef]]
        .getOrElse(responseParam, Map[String, AnyRef]())
        .asInstanceOf[Map[String, AnyRef]]
    } else if (
      400 == response.status && response.body.contains(
        config.userAccBlockedErrCode
      )
    ) {
      metrics.incCounter(config.skippedEventCount)
      logger.error(
        s"Error while fetching user details for ${url}: " + response.status + " :: " + response.body
      )
      Map[String, AnyRef]()
    } else {
      throw new Exception(
        s"Error from get API : ${url}, with response: ${response}"
      )
    }
  }

  val excludedContextCategoryCache = new java.util.concurrent.ConcurrentHashMap[String, (Set[String], Long)]()

  /**
   * Returns the identifiers of every leaf node of the course whose OWN contextCategory is
   * "Optional Pre Assessment" - these must be excluded from the leafNodes set used for
   * completedCount/completion-percentage/certificate-eligibility comparisons, since completing
   * them is not required.
   *
   * content/v3/read/{courseId} does NOT return a nested "children" tree (confirmed against a real
   * response - it only carries "leafNodes"/"childNodes" as flat id lists), so contextCategory can't
   * be read from the course-level payload. Instead, do a content/v3/read/{leafId} for each id in
   * the course's own leafNodes list and read contextCategory off that leaf's own top-level
   * response. Cached per courseId (same TTL as course info) since the leaf set only changes on
   * republish.
   */
  def getExcludedOptionalAssessmentIds(courseId: String)(
    metrics: Metrics,
    config: ProgramActivityAggregateUpdaterConfig,
    contentCache: DataCache,
    httpUtil: HttpUtil
  ): Set[String] = {
    val currentTime = System.currentTimeMillis()
    val cacheEntry = excludedContextCategoryCache.get(courseId)
    if (cacheEntry != null && cacheEntry._2 > currentTime) {
      return cacheEntry._1
    }
    // Reuse the shared, Redis + in-memory cached getCourseInfo for both the course-level and
    // per-leaf lookups, instead of raw/uncached HTTP calls, so contextCategory benefits from the
    // same caching as every other field this job reads.
    val courseContent = getCourseInfo(courseId)(metrics, config, contentCache, httpUtil)
    val leafIds = courseContent.getOrDefault("leafNodes", new java.util.ArrayList[String]()) match {
      case jl: java.util.List[_] => jl.asScala.toList.map(_.toString)
      case sl: Seq[_] => sl.toList.map(_.toString)
      case _ => List.empty[String]
    }
    logger.info(s"getExcludedOptionalAssessmentIds: courseId=$courseId leafNodes from course content=${leafIds.mkString(", ")} (count=${leafIds.size})")

    val excludedIds = scala.collection.mutable.Set.empty[String]
    leafIds.foreach { leafId =>
      try {
        val leafContent = getCourseInfo(leafId)(metrics, config, contentCache, httpUtil)
        val contextCategory = leafContent.getOrDefault(config.contextCategory, "").asInstanceOf[String]
        logger.info(s"getExcludedOptionalAssessmentIds: courseId=$courseId leafId=$leafId contextCategory=$contextCategory")
        if (config.optionalPreAssessment.equalsIgnoreCase(contextCategory)) {
          logger.info(s"getExcludedOptionalAssessmentIds: courseId=$courseId MATCHED Optional Pre Assessment, excluding leafId=$leafId")
          excludedIds += leafId
        }
      } catch {
        case ex: Exception =>
          logger.error(s"getExcludedOptionalAssessmentIds: failed to read leaf content for courseId=$courseId leafId=$leafId", ex)
      }
    }
    val result = excludedIds.toSet
    logger.info(s"getExcludedOptionalAssessmentIds: courseId=$courseId excludedIds=${result.mkString(", ")} (count=${result.size})")
    excludedContextCategoryCache.put(courseId, (result, currentTime + config.courseInMemoryCacheExpiry))
    result
  }

  def toScalaNestedMap(obj: Any): Map[String, Map[String, AnyRef]] = obj match {
    case outer: java.util.Map[_, _] =>
      outer.asScala.collect {
        case (k, v: java.util.Map[_, _]) =>
          k.toString -> v.asScala.collect {
            case (ik, iv) => ik.toString -> iv.asInstanceOf[AnyRef]
          }.toMap
      }.toMap
    case _ => Map.empty[String, Map[String, AnyRef]]
  }

}
