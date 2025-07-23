package org.sunbird.job.aggregate.v2.domain

import java.util.{Map => JMap}
import scala.collection.JavaConverters._

case class Event(eventMap: Map[String, AnyRef]) {

  private val edata: Map[String, AnyRef] =
    eventMap.getOrElse("edata", Map.empty[String, AnyRef])
      .asInstanceOf[JMap[String, AnyRef]].asScala.toMap

  def userId: String = edata.getOrElse("userId", "").asInstanceOf[String]

  def courseId: String = edata.getOrElse("courseId", "").asInstanceOf[String]

  def batchId: String = edata.getOrElse("batchId", "").asInstanceOf[String]

  def language: String = edata.getOrElse("language", "").asInstanceOf[String]


  def contents: List[ContentDetail] = {
    val rawContentsJava = edata.getOrElse("contents", new java.util.ArrayList[java.util.Map[String, AnyRef]]())
      .asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]

    rawContentsJava.asScala.toList.map { item =>
      val scalaMap = item.asScala
      val cid = scalaMap.getOrElse("contentId", "").asInstanceOf[String]
      val status = scalaMap.getOrElse("status", 0).asInstanceOf[Number].intValue()
      ContentDetail(cid, status)
    }
  }


  def status: Int = {
    val contents = edata.getOrElse("contents", List.empty[Map[String, AnyRef]])
      .asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
      .asScala.toList.map(_.asScala.toMap)

    contents.headOption.flatMap(_.get("status")).map(_.asInstanceOf[Int]).getOrElse(0)
  }

  def ts: Long = {
    eventMap.get("ets") match {
      case Some(n: java.lang.Number) => n.longValue()
      case Some(s: String) =>
        try {
          s.toLong
        } catch {
          case _: NumberFormatException => System.currentTimeMillis()
        }
      case _ => System.currentTimeMillis()
    }
  }
}
