package org.sunbird.job.postpublish.helpers

import org.apache.flink.configuration.Configuration
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.exception.APIException
import org.sunbird.job.postpublish.domain.Event
import org.sunbird.job.postpublish.task.PostPublishProcessorConfig
import org.sunbird.job.util._
import scala.collection.JavaConverters._

/** @author
  *   mahesh.vakkund
  */
trait PostPublishRelationUpdater {

  private[this] val logger =
    LoggerFactory.getLogger(classOf[PostPublishRelationUpdater])

  def verifyPrimaryCategory(identifier: String)(
      metrics: Metrics,
      config: PostPublishProcessorConfig,
      httpUtil: HttpUtil,
      cache: DataCache
  ): Boolean = {
    logger.info(
      "Verify Program post-publish required for content: " + identifier
    )
    // Get the primary Categories for the courses here
    var isValidProgram = false
    val contentObj: java.util.Map[String, AnyRef] =
      getCourseInfo(identifier)(metrics, config, cache, httpUtil)
    if (!contentObj.isEmpty) {
      val primaryCategory = contentObj.get("primaryCategory")
      if (primaryCategory != null && 
         (primaryCategory == "Program" 
            || primaryCategory == "Curated Program" 
            || primaryCategory == "Blended Program")) {
        isValidProgram = true
      }
      logger.info("PrimaryCategory value is :" + primaryCategory + ", for Id: " + identifier)
    } else {
      logger.error("Failed to read content details for Id: " + identifier)
    }
    isValidProgram
  }

  def getCourseInfo(courseId: String)(
      metrics: Metrics,
      config: PostPublishProcessorConfig,
      cache: DataCache,
      httpUtil: HttpUtil
  ): java.util.Map[String, AnyRef] = {
    val courseMetadata = cache.getWithRetry(courseId)
    if (null == courseMetadata || courseMetadata.isEmpty) {
      val url =
        config.contentReadURL + "/" + courseId + "?fields=identifier,name,versionKey,parentCollections,primaryCategory,languageMapV1,courseCategory,status,previousVersionCourseId,contentVersion,milestones_v1,learningPathIds"
      val response = getAPICall(url, "content")(config, httpUtil, metrics)
      logger.info("Content read response" + JSONUtil.serialize(response))
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
        .getOrElse("parentCollections", List.empty[String]).asInstanceOf[List[String]]
      val languageMapV1: Map[String, Map[String, AnyRef]] =
        response.get("languageMapV1") match {
          case Some(map: Map[_, _]) =>
            map.asInstanceOf[Map[String, Map[String, AnyRef]]]
          case _ =>
            Map.empty[String, Map[String, AnyRef]]
        }
      val courseCategory = StringContext
        .processEscapes(
          response.getOrElse("courseCategory", "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val status = response
        .getOrElse("status", "").asInstanceOf[String]
      val previousVersionCourseId = StringContext
        .processEscapes(
          response.getOrElse(config.previousVersionCourseId, "").asInstanceOf[String]
        )
        .filter(_ >= ' ')
      val contentVersion = StringContext
        .processEscapes(
          response.getOrElse(config.contentVersion, "").asInstanceOf[String]
        )
        .filter(_ >= ' ')

      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("courseName", courseName)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", primaryCategory)
      courseInfoMap.put(config.versionKey, versionKey)
      courseInfoMap.put("languageMapV1", languageMapV1)
      courseInfoMap.put("courseCategory", courseCategory)
      courseInfoMap.put("status", status)
      courseInfoMap.put(config.previousVersionCourseId, previousVersionCourseId)
      courseInfoMap.put(config.contentVersion, contentVersion)
      val milestonesV1 =
        response
          .getOrElse("milestones_v1", List.empty[Map[String, AnyRef]])
          .asInstanceOf[List[Map[String, AnyRef]]]
      courseInfoMap.put("milestones_v1", milestonesV1.asInstanceOf[AnyRef])
      val learningPathIds = response
        .getOrElse("learningPathIds", List.empty[String]).asInstanceOf[List[String]]
      courseInfoMap.put("learningPathIds", learningPathIds)
      courseInfoMap
    } else {
      val name = courseMetadata.getOrElse(config.name, "").asInstanceOf[String]
      val category = courseMetadata.getOrElse("primarycategory", "").asInstanceOf[String]
      val version = courseMetadata.getOrElse("versionkey", "").asInstanceOf[String]
      val parentCollections = courseMetadata
        .getOrElse("parentcollections", new java.util.ArrayList())
        .asInstanceOf[java.util.ArrayList[String]]
      val languageMapV1 = courseMetadata
        .getOrElse("languagemapv1", new java.util.HashMap[String, AnyRef]())
        .asInstanceOf[java.util.Map[String, java.util.Map[String, AnyRef]]]
      val courseCategory = courseMetadata.getOrElse("coursecategory", "").asInstanceOf[String]
      val status = courseMetadata
        .getOrElse("status", "").asInstanceOf[String]
      val previousVersionId = courseMetadata.getOrElse("previousversioncourseid", "").asInstanceOf[String]
      val contentVersion = courseMetadata.getOrElse("contentversion", "").asInstanceOf[String]
      val courseInfoMap: java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]()
      courseInfoMap.put("courseId", courseId)
      courseInfoMap.put("courseName", name)
      courseInfoMap.put("parentCollections", parentCollections)
      courseInfoMap.put("primaryCategory", category)
      courseInfoMap.put(config.versionKey, version)
      courseInfoMap.put("languageMapV1", languageMapV1)
      courseInfoMap.put("courseCategory", courseCategory)
      courseInfoMap.put("status", status)
      courseInfoMap.put(config.previousVersionCourseId, previousVersionId)
      courseInfoMap.put(config.contentVersion, contentVersion)
      val milestonesV1 =
        courseMetadata
          .getOrElse("milestonesv1", new java.util.ArrayList[java.util.Map[String, AnyRef]]())
          .asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
          .asScala
          .map(_.asScala.toMap)
          .toList
      courseInfoMap.put("milestones_v1", milestonesV1.asInstanceOf[AnyRef])
      val learningPathIds = courseMetadata
        .getOrElse("learningpathids", new java.util.ArrayList())
      courseInfoMap.put("learningPathIds", learningPathIds)
      courseInfoMap
    }

  }

  def getAPICall(url: String, responseParam: String)(
      config: PostPublishProcessorConfig,
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

  def verifyCourseCategory(identifier: String)(
    metrics: Metrics,
    config: PostPublishProcessorConfig,
    httpUtil: HttpUtil,
    cache: DataCache
  ): Boolean = {
    logger.info(
      "Verify Program post-publish required for content: " + identifier
    )
    // Get the primary Categories for the courses here
    var isValidMultiLingualCourse = false
    val contentObj: java.util.Map[String, AnyRef] =
      getCourseInfo(identifier)(metrics, config, cache, httpUtil)
    if (!contentObj.isEmpty) {
      val courseCategory = contentObj.get("courseCategory")
      if (courseCategory != null &&
        (courseCategory == "Multilingual Course")) {
        isValidMultiLingualCourse = true
      }
      logger.info("CourseCategory value is :" + courseCategory + ", for Id: " + identifier)
    } else {
      logger.error("Failed to read content details for Id: " + identifier)
    }
    isValidMultiLingualCourse
  }

}
