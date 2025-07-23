package org.sunbird.job.aggregate.v2.common

import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.aggregate.v2.task.ActivityAggregateUpdaterConfig
import org.sunbird.job.cache.DataCache
import org.sunbird.job.util.{HttpUtil, ScalaJsonUtil}
import scala.collection.JavaConverters._

trait ContentHelper {

  private[this] val logger = LoggerFactory.getLogger(classOf[ContentHelper])
  def getCourseInfo(courseId: String)(
    metrics: Metrics,
    config: ActivityAggregateUpdaterConfig,
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
      //TODO: FETCH LANGUAGE ALSO.
      val url =
        config.contentReadURL + "/" + courseId + "?fields=identifier,name,versionKey,parentCollections,primaryCategory,courseCategory,languageMapV1,leafNodes,language"
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
        .processEscapes(response.getOrElse(config.courseCategory, "").asInstanceOf[String]).filter(_ >= ' ')
      val leafNodes = response
        .getOrElse("leafNodes", List.empty[String])
        .asInstanceOf[List[String]]
      val language = response
        .getOrElse("language", List.empty[String])
        .asInstanceOf[List[String]]
      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("courseName", courseName)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", primaryCategory)
      courseInfoMap.put("versionKey", versionKey)
      courseInfoMap.put(config.courseCategory, courseCateogry)
      val languageMapV1 = response.getOrElse("languageMapV1", Map.empty[String, AnyRef])
      courseInfoMap.put("languageMapV1", languageMapV1.asInstanceOf[AnyRef])
      courseInfoMap.put("leafNodes", leafNodes)
      courseInfoMap.put("language", language)
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
      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("courseName", courseName)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", primaryCategory)
      courseInfoMap.put("versionKey", versionKey)
      courseInfoMap.put(config.courseCategory, courseCateogry)
      val languageMapV1: Map[String, Map[String, AnyRef]] =
        toScalaNestedMap(courseMetadata.getOrElse("languagemapv1", new java.util.HashMap[String, Object]()))
      courseInfoMap.put("languageMapV1", languageMapV1.asInstanceOf[AnyRef])
      val leafNodes = courseMetadata
        .getOrElse("leafnodes", new java.util.ArrayList())
        .asInstanceOf[java.util.ArrayList[String]]
      courseInfoMap.put("leafNodes", leafNodes)
      courseInfoMap.put("language", language)
      courseInfoMap
    }

  }

  def getAPICall(url: String, responseParam: String)(
    config: ActivityAggregateUpdaterConfig,
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
