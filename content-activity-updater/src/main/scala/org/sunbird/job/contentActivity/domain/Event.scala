package org.sunbird.job.contentActivity.domain

import org.sunbird.job.domain.reader.JobRequest

class Event(eventMap: java.util.Map[String, Any], partition: Int, offset: Long)  extends JobRequest(eventMap, partition, offset) {
    
    def eventId: String = readOrDefault[String]("mid", "")

    def operationType: String = readOrDefault[String]("operationType", "")

    def contentId: String = readOrDefault[String]("nodeUniqueId", "")

    def createdOn: String = readOrDefault[String]("transactionData.createdOn.nv", "")

    def lastUpdatedOn: String = readOrDefault[String]("transactionData.lastUpdatedOn.nv", "")

    def transactionData: Map[String, AnyRef] = readOrDefault[Map[String, AnyRef]]("transactionData", Map[String, AnyRef]())

    def lastStatusChangedOn: String = readOrDefault[String]("transactionData.lastStatusChangedOn.nv", "")

    def channel: String = readOrDefault[String]("transactionData.channel.nv", "")

    def status: String = readOrDefault[String]("transactionData.createdBy.nv", "")

    def userId: String = readOrDefault[String]("userId", "")

    def createdBy: String = readOrDefault[String]("transactionData.createdBy.nv", "")

}