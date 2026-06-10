package org.sunbird.job.programaggregate.v2.functions

import java.util.{Date, UUID}

import com.datastax.driver.core.{ConsistencyLevel, SimpleStatement}
import com.datastax.driver.core.querybuilder.QueryBuilder
import com.google.gson.Gson
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.{BaseProcessFunction, Metrics}
import org.sunbird.job.cache.RedisConnect
import org.sunbird.job.dedup.DeDupEngine
import org.sunbird.job.programaggregate.v2.common.{ContentHelperV2, DeDupHelperV2}
import org.sunbird.job.programaggregate.v2.domain.{ActorObject, CollectionProgress, EventContext, EventData, EventObject, TelemetryEvent}
import org.sunbird.job.programaggregate.v2.task.ProgramActivityAggregateUpdaterConfigV2
import org.sunbird.job.util.CassandraUtil

import scala.collection.JavaConverters._

/**
 * V2 Progress Complete function.
 *
 * V2 improvements over V1:
 *  1. updateEnrolment() from ContentHelperV2 — individual LOCAL_QUORUM write,
 *     replaces V1's getEnrolmentCompleteQuery() + updateDB() QueryBuilder.batch() anti-pattern.
 *  2. getEnrolmentStatusBatch() — 1 Cassandra query per unique userId instead of N queries.
 *  3. Mixes in ContentHelperV2 for shared, tested Cassandra helpers.
 *  4. CassandraUtil initialised with explicit timeouts from config.
 *  5. All @transient fields — safe for Flink checkpoint serialization.
 *  6. Null-check guard on all resources in close().
 *  7. Uses DeDupHelperV2 for checksum generation.
 *
 * V2 BUG FIX (StreamTask): audit sink from enrolmentCompleteStream was commented out in V1
 * StreamTask. generateAuditEvent() was correct in V1 — it DID emit. The bug was only
 * in StreamTask wiring. V2 StreamTask now wires the sink correctly.
 * This function continues to emit audit events normally (same as V1).
 *
 * Business logic IDENTICAL to V1:
 *  - createIssueCertEvent() — copied exactly, JSON unchanged
 *  - generateAuditEvent()   — copied exactly, JSON unchanged
 */
class ProgramProgressCompleteFunctionV2(config: ProgramActivityAggregateUpdaterConfigV2
)(implicit val enrolmentCompleteTypeInfo: TypeInformation[List[CollectionProgress]],
  val stringTypeInfo: TypeInformation[String],
  @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessFunction[List[CollectionProgress], String](config)
  with ContentHelperV2 {

  private[this] val logger = LoggerFactory.getLogger(classOf[ProgramProgressCompleteFunctionV2])

  @transient private lazy val gson = new Gson()
  @transient private var deDupEngine: DeDupEngine = _


  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    if (cassandraUtil == null)
      cassandraUtil = new CassandraUtil(
        config.dbHost,
        config.dbPort,
        config.cassandraReadTimeoutMs,
        config.cassandraConnectTimeoutMs,
        config.cassandraMaxRetries
      )
    deDupEngine = new DeDupEngine(
      config,
      new RedisConnect(config, Option(config.deDupRedisHost), Option(config.deDupRedisPort)),
      config.deDupStore,
      config.deDupExpirySec
    )
    deDupEngine.init()
    logger.info("ProgramProgressCompleteFunctionV2: open() completed")
  }

  override def close(): Unit = {
    if (cassandraUtil != null) cassandraUtil.close()
    if (deDupEngine   != null) deDupEngine.close()
    super.close()
    logger.info("ProgramProgressCompleteFunctionV2: close() completed")
  }

  override def metricsList(): List[String] = {
    List(
      config.dbReadCount,
      config.dbUpdateCount,
      config.totalEventCount,
      config.failedEventCount,
      config.enrolmentCompleteCount,
      config.certIssueEventsCount
    )
  }


  override def processElement(
    events: List[CollectionProgress],
    context: ProcessFunction[List[CollectionProgress], String]#Context,
    metrics: Metrics
  ): Unit = {
    logger.info(s"processElement: events count=${events.size}")

    // Skip enrolments already at status=2 in DB — avoid double completion
    val alreadyCompleted: Set[(String, String, String)] =
      if (config.filterCompletedEnrolments) {
        if (config.enrolmentBatchReadEnabled) {
          // 1 query per unique userId instead of N individual queries
          getEnrolmentStatusBatch(events)(metrics)
        } else {
          events.filter { p =>
            val rowOpt = getEnrolment(p.userId, p.courseId, p.batchId)(metrics, config, cassandraUtil)
            rowOpt.exists(e => e.status != 2)
          }.map(p => (p.userId, p.courseId, p.batchId)).toSet
        }
      } else Set.empty
    val pendingEnrolments = events.filterNot { p =>
      alreadyCompleted.contains((p.userId, p.courseId, p.batchId))
    }

    logger.info(
      s"processElement: total=${events.size}" +
      s" alreadyCompleted=${alreadyCompleted.size}" +
      s" pending=${pendingEnrolments.size}"
    )

    pendingEnrolments.foreach { enrolment =>
      logger.info(
        s"processElement: marking complete" +
        s" userId=${enrolment.userId}" +
        s" courseId=${enrolment.courseId}" +
        s" batchId=${enrolment.batchId}"
      )
      updateEnrolment(
        progress    = enrolment,
        status      = 2,
        completedOn = Some(new Date())
      )(metrics, config, cassandraUtil)

      metrics.incCounter(config.enrolmentCompleteCount)
      createIssueCertEvent(enrolment, context)(metrics)
      generateAuditEvent(enrolment, context)(metrics)
    }

    logger.info(s"processElement: posting events completed for ${pendingEnrolments.size} enrolments")
    // Prevents reprocessing of the same content consumption events
    if (config.dedupEnabled) {
      events
        .flatMap { cp =>
          cp.inputContents.map { c =>
            DeDupHelperV2.getMessageId(cp.courseId, cp.batchId, cp.userId, c, 2)
          }
        }
        .foreach(checksum => deDupEngine.storeChecksum(checksum))
    }
  }

  /**
   * Generates a Telemetry AUDIT event for enrolment completion.
   * JSON structure IDENTICAL to V1 — zero changes.
   */
  def generateAuditEvent(
    data: CollectionProgress,
    context: ProcessFunction[List[CollectionProgress], String]#Context
  )(implicit metrics: Metrics): Unit = {
    val auditEvent = TelemetryEvent(
      actor   = ActorObject(id = data.userId),
      edata   = EventData(
        props  = Array("status", "completedon"),
        `type` = "enrol-complete"
      ),
      context = EventContext(
        cdata = Array(
          Map("type" -> config.courseBatch, "id" -> data.batchId).asJava,
          Map("type" -> "Course",           "id" -> data.courseId).asJava
        )
      ),
      `object` = EventObject(
        id     = data.userId,
        `type` = "User",
        rollup = Map[String, String]("l1" -> data.courseId).asJava
      )
    )
    logger.info(s"generateAuditEvent: ${gson.toJson(auditEvent)}")
    context.output(config.auditEventOutputTag, gson.toJson(auditEvent))
  }

  def createIssueCertEvent(
    enrolment: CollectionProgress,
    context: ProcessFunction[List[CollectionProgress], String]#Context
  )(implicit metrics: Metrics): Unit = {
    val ets = System.currentTimeMillis
    val mid = s"""LP.${ets}.${UUID.randomUUID}"""
    val event =
      s"""{"eid": "BE_JOB_REQUEST","ets": ${ets},"mid": "${mid}","actor": {"id": "Course Certificate Generator","type": "System"},"context": {"pdata": {"ver": "1.0","id": "org.sunbird.platform"}},"object": {"id": "${enrolment.batchId}_${enrolment.courseId}","type": "CourseCertificateGeneration"},"edata": {"userIds": ["${enrolment.userId}"],"action": "issue-certificate","iteration": 1, "trigger": "auto-issue","batchId": "${enrolment.batchId}","reIssue": false,"courseId": "${enrolment.courseId}"}}"""
    logger.info(s"createIssueCertEvent: o/p event: $event")
    context.output(config.certIssueOutputTag, event)
    metrics.incCounter(config.certIssueEventsCount)
  }

  /**
   * V2 OPT: Instead of N individual Cassandra reads (one per CollectionProgress),
   * groups events by userId so each query hits a single partition key.
   *
   * Returns Set[(userId, courseId, batchId)] where status == 2 (already completed).
   * A safety filter ensures only exact (userId, courseId, batchId) combos we
   * requested are included — guards against IN-query cross-product rows.
   *
   * Copied EXACTLY from ProgramProgressUpdateFunctionV2.getEnrolmentStatusBatch() —
   * zero changes to logic or structure.
   */
  private def getEnrolmentStatusBatch(
    events: List[CollectionProgress]
  )(implicit metrics: Metrics): Set[(String, String, String)] = {

    if (events.isEmpty) {
      logger.info("getEnrolmentStatusBatch: events list is empty — returning early")
      return Set.empty
    }

    // Exact combos we care about — used as safety filter after DB response
    val requestedCombos: Set[(String, String, String)] =
      events.map(p => (p.userId, p.courseId, p.batchId)).toSet

    // Group by userId → eq on partition key = 1 partition hop per query
    val eventsByUser = events.groupBy(_.userId)
    logger.info(
      s"getEnrolmentStatusBatch: events=${events.size} uniqueUsers=${eventsByUser.size}"
    )

    val allRows = eventsByUser.flatMap { case (userId, userEvents) =>
      val courseIds = userEvents.map(_.courseId).distinct.asJava
      val batchIds  = userEvents.map(_.batchId).distinct.asJava

      val selectWhere = QueryBuilder
        .select(config.dbUserId, config.dbCourseId, config.dbBatchId, config.dbStatus)
        .from(config.dbKeyspace, config.dbUserEnrolmentsTable)
        .where(QueryBuilder.eq(config.dbUserId, userId))
        .and(QueryBuilder.in(config.dbCourseId, courseIds))
        .and(QueryBuilder.in(config.dbBatchId,  batchIds))

      // prevents stale reads on replica lag
      val stmt = new SimpleStatement(selectWhere.toString)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)

      metrics.incCounter(config.dbReadCount)

      val rows = cassandraUtil.findAllWithStatement(stmt)
      if (rows == null) {
        logger.warn(s"getEnrolmentStatusBatch: null response from Cassandra for userId=$userId")
        List.empty
      } else {
        rows.asScala.toList
      }
    }.toList

    val result = allRows
      .filter { row =>
        val combo = (
          row.getString(config.dbUserId),
          row.getString(config.dbCourseId),
          row.getString(config.dbBatchId)
        )
        requestedCombos.contains(combo) && row.getInt(config.dbStatus) == 2
      }
      .map(row => (
        row.getString(config.dbUserId),
        row.getString(config.dbCourseId),
        row.getString(config.dbBatchId)
      ))
      .toSet

    logger.info(
      s"getEnrolmentStatusBatch: alreadyCompleted=${result.size}" +
      s" pending=${events.size - result.size}"
    )

    result
  }
}
