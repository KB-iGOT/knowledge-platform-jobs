package org.sunbird.job.postpublish.helpers

import org.apache.http.client.methods.HttpPatch
import org.apache.http.entity.{ContentType, StringEntity}
import org.apache.http.impl.client.HttpClients
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.cache.DataCache
import org.sunbird.job.postpublish.task.PostPublishProcessorConfig
import org.sunbird.job.util.{HttpUtil, JSONUtil, ScalaJsonUtil}

trait SamuhikCharchaEventLinkCourse {

  private[this] val logger = LoggerFactory.getLogger(classOf[SamuhikCharchaEventLinkCourse])

  def linkEventDetailsToCourse(eventId: String)(
    metrics: Metrics,
    config: PostPublishProcessorConfig,
    cache: DataCache,
    httpUtil: HttpUtil
  ): Unit = {
    logger.info(s"Starting linkEventDetailsToCourse for eventId: $eventId")
    
    val courseMetadata = cache.getWithRetry(eventId)
    logger.info(s"Cache lookup for eventId: $eventId, found: $courseMetadata")
    
    val eventDetails =
      if (courseMetadata == null || courseMetadata.isEmpty) {
        val url =
          config.eventReadURL + "/" + eventId + config.eventReadFields
        logger.info(s"Fetching event details from API: $url")
        processAPICall(url, "event")(config, httpUtil, metrics)
      } else {
        logger.info(s"Using cached event metadata for eventId: $eventId")
        courseMetadata
      }
    
    val courseLinked = StringContext
      .processEscapes(
        eventDetails.getOrElse("courseLinked", "").asInstanceOf[String]
      )
      .filter(_ >= ' ')
    logger.info(s"Extracted courseLinked: $courseLinked for eventId: $eventId")
    
    if (courseLinked.isEmpty) {
      logger.warn(s"No course linked to the event: $eventId")
      return
    }
    
    // Always fetch fresh data from API to get the latest eventLinked list
    val contentUrl =
      config.contentReadURL + courseLinked + "?fields=versionKey,eventLinked"
    logger.info(s"Fetching course content details from API: $contentUrl")
    val contentDetails = processAPICall(contentUrl, "content")(config, httpUtil, metrics)
    
    val existingEventLinked = contentDetails.get("eventLinked") match {
      case Some(list: List[String]) => list
      case Some(str: String) if str.trim.nonEmpty => List(str)
      case _ => List.empty[String]
    }
    logger.info(s"Existing eventLinked for course $courseLinked: $existingEventLinked")
    
    val updatedEventLinked = (existingEventLinked :+ eventId).distinct
    logger.info(s"Updated eventLinked for course $courseLinked: $updatedEventLinked")
    
    val updateReq = Map(
      "request" -> Map(
        "content" -> Map(
          "versionKey" -> contentDetails.getOrElse("versionKey", ""),
          "identifier" -> courseLinked,
          "eventLinked" -> updatedEventLinked
        )
      )
    )
    logger.info(s"Sending PATCH request to update course: $courseLinked with eventLinked: $updatedEventLinked")
    
    val patchReq = new HttpPatch(config.contentSystemUpdateURL + courseLinked)
    patchReq.setEntity(new StringEntity(JSONUtil.serialize(updateReq), ContentType.APPLICATION_JSON))
    val response = HttpClients.createDefault().execute(patchReq)
    
    if (response.getStatusLine.getStatusCode == 200) {
      logger.info(s"Successfully updated event linking for course: $courseLinked with events: $updatedEventLinked")
    } else {
      logger.error(s"Failed to update event linking for $courseLinked: ${response.getStatusLine}")
    }
  }

  def processAPICall(url: String, responseParam: String)(
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
        s"Error while fetching the details for ${url}: " + response.status + " :: " + response.body
      )
      Map[String, AnyRef]()
    } else {
      throw new Exception(
        s"Error from get API : ${url}, with response: ${response}"
      )
    }
  }
}