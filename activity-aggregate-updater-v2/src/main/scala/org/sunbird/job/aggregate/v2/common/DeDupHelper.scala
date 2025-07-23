package org.sunbird.job.aggregate.v2.common

import java.security.MessageDigest

object DeDupHelper {


  def getMessageId(collectionId: String, batchId: String, userId: String, contentId: String, status: Int, language: String): String = {
    val key = Array(collectionId, batchId, userId, contentId, status, language).mkString("|")
    MessageDigest.getInstance("MD5").digest(key.getBytes).map("%02X".format(_)).mkString;
  }
}
