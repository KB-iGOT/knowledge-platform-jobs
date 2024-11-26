package org.sunbird.job.aggregate.common

import org.slf4j.ILoggerFactory
import org.sunbird.job.cache.DataCache
import org.sunbird.job.aggregate.task.ActivityAggregateUpdaterConfig
import org.sunbird.job.util.{HttpUtil, ScalaJsonUtil}
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics

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
        val url =
            config.contentReadURL + "/" + courseId + "?fields=identifier,name,versionKey,parentCollections,primaryCategory,courseCategory"
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
        val courseInfoMap: java.util.Map[String, AnyRef] =
            new java.util.HashMap[String, AnyRef]()
        courseInfoMap.put("courseId", courseId)
        courseInfoMap.put("courseName", courseName)
        courseInfoMap.put("parentCollections", parentCollections)
        courseInfoMap.put("primaryCategory", primaryCategory)
        courseInfoMap.put("versionKey", versionKey)
        courseInfoMap.put(config.courseCategory, courseCateogry)
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
        val courseInfoMap: java.util.Map[String, AnyRef] =
            new java.util.HashMap[String, AnyRef]()
        courseInfoMap.put("courseId", courseId)
        courseInfoMap.put("courseName", courseName)
        courseInfoMap.put("parentCollections", parentCollections)
        courseInfoMap.put("primaryCategory", primaryCategory)
        courseInfoMap.put("versionKey", versionKey)
        courseInfoMap.put(config.courseCategory, courseCateogry)
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
}