package org.sunbird.job.usercompetencyupdate.domain

import org.sunbird.job.domain.reader.JobRequest

class Event(eventMap: java.util.Map[String, Any], partition: Int, offset: Long)
  extends JobRequest(eventMap, partition, offset) {
  def eventType: String = readOrDefault[String]("edata.eventType", "")
  def userId: String = readOrDefault[String]("edata.userId", "")
  def contentId: String = readOrDefault[String]("edata.contentId", "")
  def batchId: String = readOrDefault[String]("edata.batchId", "")
  def contextType: String = readOrDefault[String]("edata.contextType", "")
  def payload: java.util.Map[String, Any] = eventMap
}
