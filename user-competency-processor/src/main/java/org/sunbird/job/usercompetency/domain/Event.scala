package org.sunbird.job.usercompetency.domain

import org.sunbird.job.usercompetency.domain.JobRequest
import org.sunbird.job.usercompetency.task.UserCompetencyPreProcessorConfig

class Event(eventMap: java.util.Map[String, Any], partition: Int, offset: Long)  extends JobRequest(eventMap, partition, offset) {
    
    def action:String = readOrDefault[String]("edata.action", "")

    def batchId: String = readOrDefault[String]("edata.batchId", "")

    def courseId: String = readOrDefault[String]("edata.courseId", "")

    def userId: String = readOrDefault[String]("edata.userId", "")

}
