package org.sunbird.job.useractivity.domain

import org.sunbird.job.domain.reader.JobRequest
import org.sunbird.job.useractivity.task.UserActivityAnalysisUpdaterConfig

class Event(eventMap: java.util.Map[String, Any], partition: Int, offset: Long)  extends JobRequest(eventMap, partition, offset) {
    
    def action:String = readOrDefault[String]("edata.action", "")

    def batchId: String = readOrDefault[String]("edata.batchId", "")

    def eventType: String = readOrDefault[String]("edata.type", "")

    def typeId: String = readOrDefault[String]("edata.typeId", "")

    def userId: String = readOrDefault[String]("edata.userId", "")

    def status: String = readOrDefault[String]("edata.status", "")

    def isValid()(config: UserActivityAnalysisUpdaterConfig): Boolean = {
        config.programCertPreProcess.equalsIgnoreCase(action) && !batchId.isEmpty && !typeId.isEmpty &&
          !userId.isEmpty && !eventType.isEmpty && !status.isEmpty
    }
}
