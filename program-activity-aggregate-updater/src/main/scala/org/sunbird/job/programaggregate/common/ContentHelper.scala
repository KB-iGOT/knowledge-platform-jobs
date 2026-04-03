package org.sunbird.job.programaggregate.common

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.cache.DataCache
import org.sunbird.job.programaggregate.task.ProgramActivityAggregateUpdaterConfig
import org.sunbird.job.util.{HttpUtil, ScalaJsonUtil}

import scala.collection.JavaConverters._

trait ContentHelper {

  private[this] val logger = LoggerFactory.getLogger(classOf[ContentHelper])

  def getCourseInfo(courseId: String)(
    metrics: Metrics,
    config: ProgramActivityAggregateUpdaterConfig,
    contentCache: DataCache,
    httpUtil: HttpUtil
  ): java.util.Map[String, AnyRef] = {
    val objectMapper = new ObjectMapper()

    logger.info(
      s"Fetching course details from Redis for Id: ${courseId}, Configured Index: " + contentCache.getDBConfigIndex() + ", Current Index: " + contentCache.getDBIndex()
    )
    val courseMetadata = Option(contentCache).flatMap(c => Option(c.getWithRetry(courseId))).getOrElse(null)
    val finalCourseInfoMap = if (null == courseMetadata || courseMetadata.isEmpty) {
      logger.error(
        s"Fetching course details from Content Service for Id: ${courseId}"
      )
      //TODO: FETCH LANGUAGE ALSO.
      val url =
        config.contentReadURL + "/" + courseId + "?fields=identifier,name,primaryCategory,parentCollections,courseCategory,leafNodes"
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
      val parentCollections = response
        .getOrElse("parentCollections", List.empty[String])
        .asInstanceOf[List[String]]
        val leafNodes = response
        .getOrElse("leafNodes", List.empty[String])
        .asInstanceOf[List[String]]
      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("courseName", courseName)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", primaryCategory)
      courseInfoMap.put("leafNodes", leafNodes)
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
      val parentCollections = courseMetadata
        .getOrElse("parentcollections", new java.util.ArrayList())
        .asInstanceOf[java.util.ArrayList[String]]
      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("courseName", courseName)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", primaryCategory)
      val leafNodes = courseMetadata
        .getOrElse("leafnodes", new java.util.ArrayList())
        .asInstanceOf[java.util.ArrayList[String]]
      courseInfoMap.put("leafNodes", leafNodes)
      courseInfoMap
    }

    //courseInfoCache.put(courseId, (finalCourseInfoMap, currentTime + config.courseCacheExpiry))
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
