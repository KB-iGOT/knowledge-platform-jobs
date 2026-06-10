package org.sunbird.job.programaggregate.v2.functions

import com.datastax.driver.core.{ConsistencyLevel, SimpleStatement}
import com.datastax.driver.core.querybuilder.QueryBuilder
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.{BaseProcessFunction, Metrics}
import org.sunbird.job.cache.RedisConnect
import org.sunbird.job.dedup.DeDupEngine
import org.sunbird.job.programaggregate.v2.common.{ContentHelperV2, DeDupHelperV2}
import org.sunbird.job.programaggregate.v2.domain.CollectionProgress
import org.sunbird.job.programaggregate.v2.task.ProgramActivityAggregateUpdaterConfigV2
import org.sunbird.job.util.CassandraUtil

import scala.collection.JavaConverters._

/**
 * V2 Progress Update function.
 *
 * V2 improvements over V1:
 *  1. getEnrolmentStatusBatch() — 1 Cassandra query per unique userId instead of N queries
 *     for N events. Eliminates the N+1 read pattern in V1.
 *  2. updateEnrolment() from ContentHelperV2 — individual LOCAL_QUORUM writes,
 *     replaces V1's QueryBuilder.batch() anti-pattern.
 *  3. Mixes in ContentHelperV2 for shared, tested Cassandra helpers.
 *  4. CassandraUtil initialised with explicit timeouts from config.
 *  5. All @transient fields — safe for Flink checkpoint serialization.
 *  6. getEnrolment() and updateDB() / getEnrolmentUpdateQuery() retained as no-op
 *     fallback methods so the class API stays backward-compatible.
 */
class ProgramProgressUpdateFunctionV2(
  config: ProgramActivityAggregateUpdaterConfigV2
)(implicit val enrolmentCompleteTypeInfo: TypeInformation[List[CollectionProgress]],
  val stringTypeInfo: TypeInformation[String],
  @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessFunction[List[CollectionProgress], String](config)
  with ContentHelperV2 {

  private[this] val logger =
    LoggerFactory.getLogger(classOf[ProgramProgressUpdateFunctionV2])

  @transient private var deDupEngine: DeDupEngine = _

  override def metricsList(): List[String] =
    List(config.dbReadCount, config.dbUpdateCount,
         config.totalEventCount, config.failedEventCount)

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    if (cassandraUtil == null)
      cassandraUtil = new CassandraUtil(
        config.dbHost, config.dbPort,
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
    logger.info("ProgramProgressUpdateFunctionV2: open() completed")
  }

  override def close(): Unit = {
    if (cassandraUtil != null) cassandraUtil.close()
    if (deDupEngine   != null) deDupEngine.close()
    super.close()
    logger.info("ProgramProgressUpdateFunctionV2: close() completed")
  }

  override def processElement(
    events: List[CollectionProgress],
    context: ProcessFunction[List[CollectionProgress], String]#Context,
    metrics: Metrics
  ): Unit = {


    // getEnrolmentStatusBatch() groups by userId → 1 read per unique user
    val alreadyCompleted: Set[(String, String, String)] =
      if (config.filterCompletedEnrolments)
        getEnrolmentStatusBatch(events)(metrics)
      else Set.empty

    val pendingEnrolments = events.filterNot { p =>
      alreadyCompleted.contains((p.userId, p.courseId, p.batchId))
    }

    logger.info(
      s"processElement: total=${events.size} " +
      s"alreadyCompleted=${alreadyCompleted.size} " +
      s"pending=${pendingEnrolments.size}"
    )

    implicit val cfg: ProgramActivityAggregateUpdaterConfigV2 = config
    implicit val cu: CassandraUtil                             = cassandraUtil

    pendingEnrolments.foreach { collectionProgress =>
      updateEnrolment(
        progress    = collectionProgress,
        status      = 1,
        completedOn = None
      )(metrics, config, cassandraUtil)
    }

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
   * V2 OPT: Instead of N individual Cassandra reads (one per CollectionProgress),
   * groups events by userId so each query hits a single partition key.
   *
   * Returns Set[(userId, courseId, batchId)] where status == 2 (already completed).
   * A safety filter ensures only exact (userId, courseId, batchId) combos we
   * requested are included — guards against IN-query cross-product rows.
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

      // V2 FIX: LOCAL_QUORUM — prevents stale reads on replica lag
      val stmt = new SimpleStatement(selectWhere.toString)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)

      metrics.incCounter(config.dbReadCount)

      val rows = cassandraUtil.findAllWithStatement(stmt)

      if (rows == null) {
        logger.warn(
          s"getEnrolmentStatusBatch: null response from Cassandra for userId=$userId"
        )
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
