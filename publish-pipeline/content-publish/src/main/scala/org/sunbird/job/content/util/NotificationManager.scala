package org.sunbird.job.content.util

import org.slf4j.LoggerFactory
import org.sunbird.job.util.{HttpUtil, ScalaJsonUtil}

class NotificationManager(notificationUrl: String, httpUtil: HttpUtil) {

  private[this] val logger = LoggerFactory.getLogger(classOf[NotificationManager])

  def sendNotification(subCategory: String, subType: String, userIds: List[String], title: String, data: collection.Map[String, Any]): Unit = {

    logger.info("Notification construction started")

    val bodyMap = Map(
      "subCategory" -> subCategory,
      "subType" -> subType,
      "userIds" -> userIds,
      "message" -> Map("placeholders" -> Map("title" -> title), "data" -> data)
    )

    val body = ScalaJsonUtil.serialize(bodyMap)

    logger.info("Started sending notification with body {}", body)
    val response = httpUtil.post(notificationUrl, body)

    logger.info("Successfully sent notification status: {}, body: {}", response.status, response.body)
  }

}
