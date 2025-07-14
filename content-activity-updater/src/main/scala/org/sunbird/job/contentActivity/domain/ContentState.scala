package org.sunbird.job.contentActivity.domain

import java.sql.Timestamp

/**
  * Represents the state of a content activity for a user.
  *
  * @param userId
  * @param contentId
  * @param workflowStage
  * @param stageStartedAt
  * @param stageCompletedat
  * @param stageStatus
  * @param role
  * @param contentVersion
  * @param stageVersion
  * @param comments
  * @param created_at
  * @param updated_at
  */
case class ContentState (
  userId: String,
  contentId: String,
  workflowStage: String,
  stageStartedAt: Timestamp,
  stageCompletedat: Timestamp,
  stageStatus: String,
  role: String,
  contentVersion: Int,
  stageVersion: Int,
  comments: String,
  created_at: Timestamp,
  updated_at: Timestamp,
) 
extends Serializable {

  def isSubmitted: Boolean = stageStatus.equalsIgnoreCase("submitted")

  def isInProgress: Boolean = stageStatus.equalsIgnoreCase("in_progress")

  def isRejected: Boolean = stageStatus.equalsIgnoreCase("rejected")

  def isApproved: Boolean = stageStatus.equalsIgnoreCase("approved")
}