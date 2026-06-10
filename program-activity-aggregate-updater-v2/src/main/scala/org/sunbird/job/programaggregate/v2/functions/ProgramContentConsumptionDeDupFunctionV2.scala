package org.sunbird.job.programaggregate.v2.functions

import com.twitter.storehaus.cache.TTLCache
import com.twitter.util.Duration
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.{BaseProcessFunction, Metrics}
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.dedup.DeDupEngine
import org.sunbird.job.programaggregate.v2.common.{ContentHelperV2, DeDupHelperV2}
import org.sunbird.job.programaggregate.v2.domain.ProgramEvent
import org.sunbird.job.programaggregate.v2.task.ProgramActivityAggregateUpdaterConfigV2
import org.sunbird.job.util.{CassandraUtil, HttpUtil}

import java.util
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.mutable

/**
 * V2 DeDup function.
 *
 * V2 improvements over V1:
 *  1. discardDuplicates() is ACTUALLY CALLED (V1 defined it but never called it).
 *     2. Typed ProgramEvent — no raw .asInstanceOf[] casts on eData fields.
 *     3. getEnrolmentsForPrograms() (IN query) instead of getAllEnrolments() (full scan).
 *     4. EnrolmentData typed access instead of raw Map[String, AnyRef].
 *     5. CassandraUtil constructed with explicit timeout config.
 *     6. All fields are @transient — safe for Flink checkpoint serialization.
 */
class ProgramContentConsumptionDeDupFunctionV2(
                                                config: ProgramActivityAggregateUpdaterConfigV2,
                                                httpUtil: HttpUtil
                                              )(implicit val stringTypeInfo: TypeInformation[String])
  extends BaseProcessFunction[util.Map[String, AnyRef], String](config)
    with ContentHelperV2 {

  private[this] val logger =
    LoggerFactory.getLogger(classOf[ProgramContentConsumptionDeDupFunctionV2])

  // @transient — all mutable resources must not be Flink-serialized
  @transient private var cassandraUtil: CassandraUtil = _
  @transient private var contentCache: DataCache = _
  @transient private var deDupEngine: DeDupEngine = _
  @transient private var collectionStatusCache: TTLCache[String, String] = _


  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(
      config.dbHost,
      config.dbPort,
      config.cassandraReadTimeoutMs,
      config.cassandraConnectTimeoutMs,
      config.cassandraMaxRetries
    )
    contentCache = new DataCache(
      config,
      new RedisConnect(config, Option(config.deDupRedisHost), Option(config.deDupRedisPort)),
      config.contentStoreIndex,
      List()
    )
    contentCache.init()
    collectionStatusCache = TTLCache[String, String](
      Duration.apply(config.statusCacheExpirySec, TimeUnit.SECONDS)
    )
    deDupEngine = new DeDupEngine(
      config,
      new RedisConnect(config, Option(config.deDupRedisHost), Option(config.deDupRedisPort)),
      config.deDupStore,
      config.deDupExpirySec
    )
    deDupEngine.init()
    logger.info("ProgramContentConsumptionDeDupFunctionV2: open() completed")
  }

  override def close(): Unit = {
    // all resources guarded with null check before close
    if (cassandraUtil != null) cassandraUtil.close()
    if (contentCache != null) contentCache.close()
    if (deDupEngine != null) deDupEngine.close()
    super.close()
    logger.info("ProgramContentConsumptionDeDupFunctionV2: close() completed")
  }

  override def metricsList(): List[String] = {
    List(
      config.totalEventCount,
      config.skipEventsCount,
      config.batchEnrolmentUpdateEventCount,
      config.dbReadCount
    )
  }

  override def processElement(
                               event: util.Map[String, AnyRef],
                               context: ProcessFunction[util.Map[String, AnyRef], String]#Context,
                               metrics: Metrics
                             ): Unit = {
    metrics.incCounter(config.totalEventCount)

    //  discardDuplicate checking the duplicate event
    if (config.dedupEnabled && !discardDuplicates(event, metrics)) {
      logger.info("processElement: duplicate event detected — skipping")
      metrics.incCounter(config.skipEventsCount)
      return
    }

    val programEventOpt = ProgramEvent(event)
    if (programEventOpt.isEmpty) {
      logger.warn("processElement: could not build ProgramEvent (missing userId/courseId/batchId) — skipping")
      metrics.incCounter(config.skipEventsCount)
      return
    }
    val programEvent = programEventOpt.get

    if (!programEvent.isBatchEnrolmentUpdate) {
      metrics.incCounter(config.skipEventsCount)
      return
    }

    val completedContents = programEvent.completedContents
    if (completedContents.isEmpty) {
      logger.info(
        s"processElement: no completed contents for" +
          s" courseId=${programEvent.courseId} userId=${programEvent.userId} — skipping"
      )
      metrics.incCounter(config.skipEventsCount)
      return
    }

    logger.info(
      s"processElement: processing userId=${programEvent.userId}" +
        s" courseId=${programEvent.courseId} completedContents=${completedContents.size}"
    )

    val eventInfoList = getProgramEvent(programEvent, metrics)
    eventInfoList.foreach { eventMap =>
      context.output(config.uniqueConsumptionOutput, eventMap)
    }
    logger.info(
      s"processElement: emitted ${eventInfoList.size} event(s) to uniqueConsumptionOutput" +
        s" for userId=${programEvent.userId} courseId=${programEvent.courseId}"
    )
  }

  private def discardDuplicates(event: util.Map[String, AnyRef], metrics: Metrics): Boolean = {
    val edata: Map[String, AnyRef] = Option(event.get(config.eData))
      .collect { case m: util.Map[_, _] =>
        m.asInstanceOf[util.Map[String, AnyRef]].asScala.toMap
      }
      .getOrElse(Map.empty)

    val userId = edata.getOrElse(config.userId, "").asInstanceOf[String]
    val courseId = edata.getOrElse(config.courseId, "").asInstanceOf[String]
    val batchId = edata.getOrElse(config.batchId, "").asInstanceOf[String]

    val contents: List[util.Map[String, AnyRef]] =
      edata.getOrElse(config.contents, new util.ArrayList[util.Map[String, AnyRef]]())
        .asInstanceOf[util.List[util.Map[String, AnyRef]]].asScala.toList

    if (contents.nonEmpty) {
      val head = contents.head
      val contentId = Option(head.get("contentId")).map(_.asInstanceOf[String]).getOrElse("")
      val status = Option(head.get("status")) match {
        case Some(n: Number) => n.intValue()
        case _ => 0
      }
      val checksum = DeDupHelperV2.getMessageId(courseId, batchId, userId, contentId, status)
      logger.info(s"discardDuplicates: checksum=$checksum userId=$userId courseId=$courseId")
      deDupEngine.isUniqueEvent(checksum)
    } else {
      // No contents — not a meaningful event; treat as non-unique so it gets skipped
      logger.info("discardDuplicates: empty contents list — not emitting")
      false
    }
  }

  /**
   *
   * V2 improvements:
   *  - Typed ProgramEvent input instead of raw Map[String, AnyRef]
   *  - getEnrolmentsForPrograms() (IN query) instead of getAllEnrolments() (full scan)
   *  - EnrolmentData typed access instead of raw Map row
   */
  private def getProgramEvent(
                               programEvent: ProgramEvent,
                               metrics: Metrics
                             ): List[Map[String, AnyRef]] = {

    logger.info(
      s"getProgramEvent: entry userId=${programEvent.userId}" +
        s" courseId=${programEvent.courseId} batchId=${programEvent.batchId}"
    )
    val eventInfoMap = mutable.ListBuffer.empty[Map[String, AnyRef]]
    val contentObj = getCourseInfo(programEvent.courseId)(metrics, config, contentCache, httpUtil)

    val primaryCategory: String = Option(contentObj.get(config.primaryCategory))
      .map(_.asInstanceOf[String])
      .getOrElse("")

    val parentCollections: List[String] = Option(contentObj.get(config.parentCollections))
      .collect {
        case list: java.util.List[_] =>
          list.asInstanceOf[java.util.List[String]].asScala.toList
        case scalaList: scala.collection.immutable.List[_] =>
          scalaList.asInstanceOf[List[String]]
      }
      .getOrElse(List.empty)

    logger.info(
      s"getProgramEvent: courseId=${programEvent.courseId}" +
        s" primaryCategory=$primaryCategory parentCollections=$parentCollections"
    )

    if (config.validProgramPrimaryCategory.contains(primaryCategory)) {

      val contentConsumption: List[Map[String, AnyRef]] = programEvent.contents.map(c =>
        Map[String, AnyRef]("contentId" -> c.contentId, "status" -> c.status.asInstanceOf[AnyRef])
      )
      val mergedMap = Map[String, AnyRef](
        config.userId -> programEvent.userId,
        config.courseId -> programEvent.courseId,
        config.batchId -> programEvent.batchId,
        config.contents -> contentConsumption,
        config.action -> programEvent.action
      )
      eventInfoMap += mergedMap
      logger.info(
        s"getProgramEvent: Branch 1 (Program category) — emitting event as-is" +
          s" courseId=${programEvent.courseId} primaryCategory=$primaryCategory"
      )

    } else if (
      ("Course".equalsIgnoreCase(primaryCategory) || "Standalone Assessment".equalsIgnoreCase(primaryCategory)) &&
        parentCollections.nonEmpty
    ) {
      val userEnrolments: Map[String, Map[String, AnyRef]] = getEnrolmentsForPrograms(
        programEvent.userId, parentCollections
      )(metrics, config, cassandraUtil)

      logger.info(
        s"getProgramEvent: Branch 2 (Course/SA) —" +
          s" fetched ${userEnrolments.size} enrolments for userId=${programEvent.userId}"
      )

      for (parentId <- parentCollections) {

        val row = userEnrolments.getOrElse(parentId, null)
        logger.info(s"getProgramEvent: Enrollment for parentId=$parentId : $row")

        if (row != null) {
          val filteredContents = programEvent.completedContents
          if (filteredContents.nonEmpty) {
            val batchId: String = row.get("batchId") match {
              case Some(value: String) => value
              case _ => null
            }
            if (batchId != null) {
              val eventInfoProgram = Map[String, AnyRef](
                config.contents -> filteredContents.map(c =>
                  Map[String, AnyRef]("contentId" -> c.contentId, "status" -> c.status.asInstanceOf[AnyRef])
                ),
                config.userId -> programEvent.userId,
                config.action -> "batch-enrolment-update",
                "iteration" -> 1.asInstanceOf[Integer],
                config.batchId -> batchId,
                config.courseId -> parentId
              )
              eventInfoMap += eventInfoProgram
              logger.info(
                s"getProgramEvent: built program event parentId=$parentId batchId=$batchId"
              )
            } else {
              logger.error(
                s"getProgramEvent: batchId is null for userId=${programEvent.userId} parentId=$parentId"
              )
            }
          }
        } else {
          logger.info(
            s"getProgramEvent: userId=${programEvent.userId} not enrolled in parentId=$parentId — skipping"
          )
        }
      }
    } else {
      logger.error(
        s"getProgramEvent: Branch 3 — invalid/unknown primaryCategory=$primaryCategory" +
          s" parentCollections=$parentCollections userId=${programEvent.userId}"
      )
      metrics.incCounter(config.skipEventsCount)
    }

    logger.info(
      s"getProgramEvent: exit userId=${programEvent.userId}" +
        s" courseId=${programEvent.courseId} eventsBuilt=${eventInfoMap.size}"
    )
    eventInfoMap.toList
  }

}
