error id: file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/domain/ContentState.scala:java/sql/Timestamp#
file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/domain/ContentState.scala
empty definition using pc, found symbol in pc: java/sql/Timestamp#
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -java/sql/Timestamp#
	 -Timestamp#
	 -scala/Predef.Timestamp#
offset: 610
uri: file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/domain/ContentState.scala
text:
```scala
package org.sunbird.job.contentActivity.domain

import java.sql.Timestamp

/**
  * 
  *
  * @param user_id
  * @param content_id
  * @param workflow_stage
  * @param typeIdentifier
  * @param userId
  * @param batchId
  * @param status
  * @param updatedDate
  * @param enrolledDate
  * @param createdDate
  */
case class ContentState(
  userId: String,
  contentId: String,
  workflowStage: String,
  stageStartedAt: Timestamp,
  stage_completed_at: String,
  stage_status: String,
  role: String,
  content_version: Long,
  stage_version: Long,
  comments: Long,
  created_at: Timestamp,
  updated_at: Timest@@amp,
) {
  def this() = this("", "", "", "", "", "", "", 0L, 0L, 0L)
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: java/sql/Timestamp#