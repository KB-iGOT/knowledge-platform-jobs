package org.sunbird.job.programaggregate.v2.domain

import java.util
import java.util.{Date, UUID}
import scala.collection.JavaConverters._

case class ActorObject(id: String, `type`: String = "User")

case class EventContext(channel: String = "in.sunbird",
                        env: String = "Course",
                        sid: String = UUID.randomUUID().toString,
                        did: String = UUID.randomUUID().toString,
                        pdata: util.Map[String, String] = Map(
                          "ver" -> "3.0",
                          "id"  -> "org.sunbird.learning.platform",
                          "pid" -> "course-progress-updater"
                        ).asJava,
                        cdata: Array[util.Map[String, String]])

case class EventData(props: Array[String], `type`: String)

case class EventObject(id: String, `type`: String, rollup: util.Map[String, String])

case class TelemetryEvent(actor: ActorObject,
                          eid: String = "AUDIT",
                          edata: EventData,
                          ver: String = "3.0",
                          syncts: Long = System.currentTimeMillis(),
                          ets: Long = System.currentTimeMillis(),
                          context: EventContext = EventContext(
                            cdata = Array[util.Map[String, String]]()
                          ),
                          mid: String = s"LP.AUDIT.${UUID.randomUUID().toString}",
                          `object`: EventObject,
                          tags: util.List[AnyRef] = new util.ArrayList[AnyRef]())

case class ContentStatus(contentId: String,
                         status: Int = 0,
                         completedCount: Int = 0,
                         viewCount: Int = 1,
                         fromInput: Boolean = true,
                         eventsFor: List[String] = List())

case class UserContentConsumption(userId: String,
                                  batchId: String,
                                  courseId: String,
                                  contents: Map[String, ContentStatus])

case class UserActivityAgg(activity_type: String,
                           user_id: String,
                           activity_id: String,
                           context_id: String,
                           aggregates: Map[String, Double],
                           agg_last_updated: Map[String, Long])

case class CollectionProgress(userId: String,
                               batchId: String,
                               courseId: String,
                               progress: Int,
                               completedOn: Date,
                               contentStatus: Map[String, Int],
                               inputContents: List[String],
                               completed: Boolean = false)


case class UserEnrolmentAgg(activityAgg: UserActivityAgg,
                            collectionProgress: Option[CollectionProgress] = None)

// Prevents ClassCastException on malformed events.
case class ProgramContent(contentId: String, status: Int)

// Returns safe defaults if any field is missing.
case class ProgramEvent(userId: String,
                        courseId: String,
                        batchId: String,
                        action: String,
                        contents: List[ProgramContent],
                        iteration: Int) {

  /** Returns only completed contents */
  def completedContents: List[ProgramContent] = contents.filter(_.status == 2)

  /** True when this is a batch-enrolment-update event. */
  def isBatchEnrolmentUpdate: Boolean = "batch-enrolment-update".equalsIgnoreCase(action)

  /** True when all mandatory identity fields are present. */
  def isValid: Boolean = userId.nonEmpty && courseId.nonEmpty && batchId.nonEmpty
}

object ProgramEvent {

  /** Safely builds a ProgramEvent from a raw Kafka Map.
   *  Returns None if the event is missing mandatory identity fields.
   */
  def apply(event: java.util.Map[String, AnyRef]): Option[ProgramEvent] = {

    // Extract edata map safely
    val edata: Map[String, AnyRef] = Option(event.get("edata"))
      .collect { case m: java.util.Map[_, _] =>
        m.asInstanceOf[java.util.Map[String, AnyRef]].asScala.toMap
      }
      .getOrElse(Map.empty)

    // Extract each scalar field safely with default fallback
    val userId   = edata.getOrElse("userId",   "").asInstanceOf[String]
    val courseId = edata.getOrElse("courseId", "").asInstanceOf[String]
    val batchId  = edata.getOrElse("batchId",  "").asInstanceOf[String]
    val action   = edata.getOrElse("action",   "").asInstanceOf[String]

    val iteration = edata.getOrElse("iteration", 1) match {
      case n: Number => n.intValue()
      case _         => 1
    }

    // Extract contents list safely — skip entries with empty contentId
    val contents: List[ProgramContent] =
      Option(edata.get("contents"))
        .collect { case list: java.util.List[_] =>
          list.asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]].asScala.toList
        }
        .getOrElse(List.empty)
        .flatMap { c =>
          val contentId = Option(c.get("contentId")).map(_.asInstanceOf[String]).getOrElse("")
          val status = Option(c.get("status")) match {
            case Some(n: Number) => n.intValue()
            case _               => 0
          }
          if (contentId.nonEmpty) Some(ProgramContent(contentId, status)) else None
        }

    val programEvent = ProgramEvent(userId, courseId, batchId, action, contents, iteration)
    if (programEvent.isValid) Some(programEvent) else None
  }
}

case class EnrolmentData(userId: String,
                         courseId: String,
                         batchId: String,
                         active: Boolean,
                         status: Int,
                         contentStatus: Map[String, Int],
                         progress: Int) {

  /** True when the enrolment row is still active. */
  def isActive: Boolean = active

  /** True when the learner has completed the course (status == 2). */
  def isCompleted: Boolean = status == 2

  /** True when the learner is in progress (status == 1). */
  def isInProgress: Boolean = status == 1
}

object EnrolmentData {

  /** Builds an EnrolmentData from a Cassandra Row.
   *  Uses safe getOrElse for contentstatus map; primitives use driver defaults.
   */
  def apply(row: com.datastax.driver.core.Row): EnrolmentData = {

    val contentStatus: Map[String, Int] =
      Option(row.getMap("contentstatus", classOf[String], classOf[Integer]))
        .map(_.asScala.map { case (k, v) => k -> v.intValue() }.toMap)
        .getOrElse(Map.empty)

    EnrolmentData(
      userId        = row.getString("userid"),
      courseId      = row.getString("courseid"),
      batchId       = row.getString("batchid"),
      active        = row.getBool("active"),
      status        = row.getInt("status"),
      contentStatus = contentStatus,
      progress      = row.getInt("progress")
    )
  }
}
