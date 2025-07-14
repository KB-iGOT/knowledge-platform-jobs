error id: file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/domain/ContentState.scala:`<none>`.
file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/domain/ContentState.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 553
uri: file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/domain/ContentState.scala
text:
```scala
package org.sunbird.job.contentActivity.domain

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
  user_id: String,
  content_id: String,
  workflow_stage: String,
  stage_started_at: String,
  stage_completed_at: String,
  stage_status: String,
  role: String,
  content_version: Long,
  stage_version: Long,
  comments: Long,
  created_at @@Timestamp,
  updated_at Timestamp,
) {
  def this() = this("", "", "", "", "", "", "", 0L, 0L, 0L)
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.