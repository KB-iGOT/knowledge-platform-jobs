error id: file://<WORKSPACE>/user-activity-analysis-updater/src/main/scala/org/sunbird/job/useractivity/domain/Event.scala:`<none>`.
file://<WORKSPACE>/user-activity-analysis-updater/src/main/scala/org/sunbird/job/useractivity/domain/Event.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -org/sunbird/job/domain/reader/JobRequest.
	 -org/sunbird/job/domain/reader/JobRequest#
	 -org/sunbird/job/domain/reader/JobRequest().
	 -JobRequest.
	 -JobRequest#
	 -JobRequest().
	 -scala/Predef.JobRequest.
	 -scala/Predef.JobRequest#
	 -scala/Predef.JobRequest().
offset: 88
uri: file://<WORKSPACE>/user-activity-analysis-updater/src/main/scala/org/sunbird/job/useractivity/domain/Event.scala
text:
```scala
package org.sunbird.job.useractivity.domain

import org.sunbird.job.domain.reader.JobReq@@uest
import org.sunbird.job.useractivity.task.UserActivityAnalysisUpdaterConfig

class Event(eventMap: java.util.Map[String, Any], partition: Int, offset: Long)  extends JobRequest(eventMap, partition, offset) {
    def action: String = readOrDefault[String]("edata.action", "")

    def eData: Map[String, AnyRef] = readOrDefault[Map[String, AnyRef]]("edata", Map[String, AnyRef]())

    def batchId: String = readOrDefault[String]("edata.batchId", "")

    def eventType: String = readOrDefault[String]("edata.type", "")

    def typeId: String = readOrDefault[String]("edata.typeId", "")

    def userId: String = readOrDefault[String]("edata.userId", "")

    def status: String = readOrDefault[String]("edata.status", "")

}

```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.