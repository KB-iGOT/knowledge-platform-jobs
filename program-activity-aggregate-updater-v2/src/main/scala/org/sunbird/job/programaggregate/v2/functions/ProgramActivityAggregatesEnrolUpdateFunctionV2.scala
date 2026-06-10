package org.sunbird.job.programaggregate.v2.functions

import com.google.gson.Gson
import com.twitter.storehaus.cache.TTLCache
import com.twitter.util.Duration
import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor, ValueState, ValueStateDescriptor}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.{BaseProcessKeyedFunction, Metrics}
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.programaggregate.v2.common.ContentHelperV2
import org.sunbird.job.programaggregate.v2.domain.{CollectionProgress, ContentStatus, UserContentConsumption}
import org.sunbird.job.programaggregate.v2.task.ProgramActivityAggregateUpdaterConfigV2
import org.sunbird.job.util.{CassandraUtil, HttpUtil}

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
 * V2 Aggregates + Enrolment Update function.
 *
 * V2 improvements over V1:
 *  1. Extends BaseProcessKeyedFunction — supports Flink keyed state and timer service.
 *  2. Timer-based micro-batching: events accumulate in ListState; a processing-time
 *     timer fires after `config.processingTimerIntervalMs` ms and processes the batch.
 *     Replaces GlobalWindow + WindowBaseProcessFunction entirely.
 *  3. getEnrolment() returns Option[EnrolmentData] — no null checks.
 *  4. EnrolmentData.contentStatus correctly read from "contentstatus" column.
 *  5. batchId included in getEnrolment WHERE clause (V1 only queried userId+courseId).
 *  6. CassandraUtil constructed with explicit timeouts from config.
 *  7. All @transient fields — safe for Flink checkpoint serialization.
 */
class ProgramActivityAggregatesEnrolUpdateFunctionV2(
  config: ProgramActivityAggregateUpdaterConfigV2,
  httpUtil: HttpUtil,
  @transient var cassandraUtil: CassandraUtil = null
)(implicit val stringTypeInfo: TypeInformation[String])
  extends BaseProcessKeyedFunction[String, Map[String, AnyRef], String](config)
  with ContentHelperV2 {

  private[this] val logger =
    LoggerFactory.getLogger(classOf[ProgramActivityAggregatesEnrolUpdateFunctionV2])


  @transient private var contentCache: DataCache = _
  @transient private var collectionStatusCache: TTLCache[String, String] = _

  //Timer-based micro-batch state
  @transient private var pendingEvents: ListState[Map[String, AnyRef]] = _
  @transient private var timerSet: ValueState[Boolean] = _

  lazy private val gson = new Gson()

  override def metricsList(): List[String] = List(
    config.failedEventCount, config.dbUpdateCount, config.dbReadCount,
    config.cacheHitCount, config.cacheMissCount,
    config.processedEnrolmentCount, config.retiredCCEventsCount
  )

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    if (cassandraUtil == null)
      cassandraUtil = new CassandraUtil(
        config.dbHost, config.dbPort,
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

    //  Timer state initialisation
    pendingEvents = getRuntimeContext.getListState(
      new ListStateDescriptor[Map[String, AnyRef]](
        "pendingEvents",
        classOf[Map[String, AnyRef]]
      )
    )
    timerSet = getRuntimeContext.getState(
      new ValueStateDescriptor[Boolean](
        "timerSet",
        classOf[Boolean]
      )
    )

    logger.info("ProgramActivityAggregatesEnrolUpdateFunctionV2: open() completed")
  }

  override def close(): Unit = {
    if (cassandraUtil != null) cassandraUtil.close()
    if (contentCache  != null) contentCache.close()
    super.close()
    logger.info("ProgramActivityAggregatesEnrolUpdateFunctionV2: close() completed")
  }

  // ── processElement — accumulate into state; register timer once per key ────

  override def processElement(
    event: Map[String, AnyRef],
    ctx: KeyedProcessFunction[String, Map[String, AnyRef], String]#Context,
    metrics: Metrics
  ): Unit = {
    metrics.incCounter(config.totalEventCount)

    // Add this event to pending state
    pendingEvents.add(event)

    // Register a processing-time timer if one is not already set for this key
    if (!timerSet.value()) {
      val triggerTime =
        ctx.timerService().currentProcessingTime() + config.processingTimerIntervalMs
      ctx.timerService().registerProcessingTimeTimer(triggerTime)
      timerSet.update(true)
      logger.debug(
        s"processElement: timer registered at $triggerTime for key=${ctx.getCurrentKey}"
      )
    }
  }

  // ── onTimer — fires once per key per interval; drains pending state
  override def onTimer(
    timestamp: Long,
    ctx: KeyedProcessFunction[String, Map[String, AnyRef], String]#OnTimerContext,
    metrics: Metrics
  ): Unit = {
    val events = pendingEvents.get().asScala.toList
    timerSet.update(false)
    pendingEvents.clear()

    logger.info(
      s"onTimer: processing batch of ${events.size} events for key=${ctx.getCurrentKey}"
    )

    if (events.nonEmpty) {
      processEventBatch(events, ctx, metrics)
    }
  }

  private def processEventBatch(
    events: List[Map[String, AnyRef]],
    ctx: KeyedProcessFunction[String, Map[String, AnyRef], String]#OnTimerContext,
    metrics: Metrics
  ): Unit = {

    // group events by (courseId, batchId, userId) and build UserContentConsumption
    val inputUserConsumptionList: List[UserContentConsumption] = events
      .groupBy(e => (e.get(config.courseId), e.get(config.batchId), e.get(config.userId)))
      .values.map { value =>
        metrics.incCounter(config.processedEnrolmentCount)
        val batchId  = value.head(config.batchId).toString
        val userId   = value.head(config.userId).toString
        val courseId = value.head(config.courseId).toString
        logger.info(
          s"processEventBatch: building consumption userId=$userId courseId=$courseId batchId=$batchId"
        )
        val userConsumedContents =
          value.head(config.contents).asInstanceOf[List[Map[String, AnyRef]]]
        val enrichedContents = getContentStatusFromEvent(userConsumedContents)
        UserContentConsumption(
          userId   = userId,
          batchId  = batchId,
          courseId = courseId,
          contents = enrichedContents
        )
      }.toList

    logger.info(
      s"processEventBatch: inputUserConsumptionList size=${inputUserConsumptionList.size}"
    )
    if (inputUserConsumptionList.isEmpty) return

    val updateProgramEnrollments =
      updateProgramEnrollment(inputUserConsumptionList)(metrics)

    // split by completed flag and emit to respective side outputs
    val collectionProgressUpdateList   = updateProgramEnrollments.filter(!_.completed)
    val collectionProgressCompleteList = updateProgramEnrollments.filter(_.completed)

    logger.info(
      s"processEventBatch: collectionUpdateList size=${collectionProgressUpdateList.size}"
    )
    logger.info(
      s"processEventBatch: collectionCompleteList size=${collectionProgressCompleteList.size}"
    )

    if (collectionProgressUpdateList.nonEmpty)
      ctx.output(config.collectionUpdateOutputTag, collectionProgressUpdateList)

    if (collectionProgressCompleteList.nonEmpty)
      ctx.output(config.collectionCompleteOutputTag, collectionProgressCompleteList)
  }


  /**
   * Builds Map[contentId, ContentStatus] from raw event contents.
   *
   *   - status=2 → completedCount=1; otherwise completedCount=0
   *   - preserves viewCount
   *   - if same contentId appears twice, take MAX status and SUM viewCount and completedCount
   * Input:  List[Map[String, AnyRef]]  (raw contents from Kafka event)
   * Output: Map[String, ContentStatus] keyed by contentId
   */
  def getContentStatusFromEvent(
    contents: List[Map[String, AnyRef]]
  ): Map[String, ContentStatus] = {
    val enrichedContents = contents
      .map(c => (
        c.getOrElse(config.contentId, "").asInstanceOf[String],
        c.getOrElse(config.status, 0).asInstanceOf[Number]
      ))
      .filter(t => StringUtils.isNotBlank(t._1) && t._2.intValue() > 0)
      .map { case (id, statusNum) =>
        val completedCount = if (statusNum.intValue() == 2) 1 else 0
        ContentStatus(id, statusNum.intValue(), completedCount)
      }
      .groupBy(_.contentId)

    enrichedContents.map { case (contentId, consumedList) =>
      // take MAX status — never downgrade a content status
      val finalStatus = consumedList.map(_.status).max
      val views       = sumFunc(consumedList, _.viewCount)
      val completion  = sumFunc(consumedList, _.completedCount)
      contentId -> ContentStatus(contentId, finalStatus, completion, views)
    }
  }

  private def sumFunc(list: List[ContentStatus], valFunc: ContentStatus => Int): Int =
    list.map(valFunc).sum

  def updateProgramEnrollment(
    events: List[UserContentConsumption]
  )(implicit metrics: Metrics): List[CollectionProgress] = {
    events.flatMap { consumption =>
      updateEnrolContentConsumption(consumption)(metrics).flatMap { updated =>
        programEnrolConsumption(updated)(metrics)
      }
    }
  }

  /**
   * Reads leafNodes via ContentHelperV2.getCourseInfo.
   */
  def readFromCache(courseId: String, metrics: Metrics): List[String] = {
    val contentObj: java.util.Map[String, AnyRef] =
      getCourseInfo(courseId)(metrics, config, contentCache, httpUtil)
    if (!contentObj.isEmpty) {
      logger.info(s"readFromCache: course info found for courseId=$courseId")
      val raw = contentObj.get(config.leafNodesKey)
      val leafNodes = raw match {
        case l: java.util.List[_]       => l.asScala.toList.map(_.toString)
        case l: scala.collection.Seq[_] => l.toList.map(_.toString)
        case _                          => List.empty[String]
      }
      if (leafNodes.nonEmpty) return leafNodes
    }
    logger.info(s"readFromCache: no leafNodes found for courseId=$courseId")
    List.empty[String]
  }

  /**
   * returns Option[UserContentConsumption] — no null returns.
   * Uses ContentHelperV2.getEnrolment() → Option[EnrolmentData] with LOCAL_QUORUM.
   * batchId included in WHERE clause (only userId+courseId were queried).
   *   - key in DB && incoming==2 && DB!=2 → upgrade to 2
   *   - key not in DB                     → insert incoming
   *   - key in DB && incoming!=2           → keep existing (no downgrade)
   */
  def updateEnrolContentConsumption(
    userConsumption: UserContentConsumption
  )(implicit metrics: Metrics): Option[UserContentConsumption] = {

    implicit val cfg: ProgramActivityAggregateUpdaterConfigV2 = config
    implicit val cu: CassandraUtil                             = cassandraUtil

    getEnrolment(
      userConsumption.userId,
      userConsumption.courseId,
      userConsumption.batchId
    ) match {

      case None =>
        logger.info(
          s"updateEnrolContentConsumption: no enrolment found —" +
          s" userId=${userConsumption.userId} courseId=${userConsumption.courseId}" +
          s" batchId=${userConsumption.batchId}"
        )
        None

      case Some(enrolment) if enrolment.status == 2 =>
        // skip already-completed enrolment
        logger.info(
          s"updateEnrolContentConsumption: enrolment already completed (status=2) —" +
          s" userId=${userConsumption.userId} courseId=${userConsumption.courseId}"
        )
        None

      case Some(enrolment) =>
        logger.info(
          s"updateEnrolContentConsumption: enrolment found status=${enrolment.status}" +
          s" userId=${userConsumption.userId} courseId=${userConsumption.courseId}"
        )
        val programContentStatus =
          scala.collection.mutable.Map(enrolment.contentStatus.toSeq: _*)

        for ((key, value) <- userConsumption.contents) {
          if (programContentStatus.contains(key)) {
            if (value.status == 2 && programContentStatus.getOrElse(key, 0) != 2) {
              programContentStatus(key) = value.status
            }
          } else {
            programContentStatus(key) = value.status
          }
        }
        val programContentStatusList = ListBuffer[Map[String, AnyRef]]()
        for ((k, v) <- programContentStatus) {
          programContentStatusList += Map(
            config.contentId -> k.asInstanceOf[AnyRef],
            config.status    -> v.asInstanceOf[AnyRef]
          )
        }

        val updatedContent     = getContentStatusFromEvent(programContentStatusList.toList)
        val updatedConsumption = userConsumption.copy(contents = updatedContent)
        logger.info(
          s"updateEnrolContentConsumption: merged contentStatus size=${updatedContent.size}" +
          s" userId=${userConsumption.userId} courseId=${userConsumption.courseId}"
        )
        Some(updatedConsumption)
    }
  }

  /**
   *  progress calculation and CollectionProgress construction.
   *
   * Completion: completedCount (leafNodes ∩ completed contents) >= leafNodes.size
   *   → completed=true,  completedOn=now  → collectionCompleteOutputTag
   *   → completed=false, completedOn=null → collectionUpdateOutputTag
   */
  def programEnrolConsumption(
    userConsumption: UserContentConsumption
  )(implicit metrics: Metrics): Option[CollectionProgress] = {

    val courseId = userConsumption.courseId
    val userId   = userConsumption.userId

    val leafNodes = readFromCache(courseId, metrics).distinct

    if (leafNodes.isEmpty) {
      logger.error(
        s"programEnrolConsumption: leafNodes empty for courseId=$courseId — checking status"
      )
      val status = getCollectionStatus(courseId)
      if (StringUtils.equals("Retired", status)) {
        metrics.incCounter(config.retiredCCEventsCount)
        logger.warn(
          s"programEnrolConsumption: retired collection courseId=$courseId — skipping"
        )
        None
      } else {
        metrics.incCounter(config.failedEventCount)
        val msg =
          s"programEnrolConsumption: leafNodes unavailable for live collection courseId=$courseId"
        logger.error(msg)
        throw new Exception(msg)
      }
    } else {
      val completedContentIds = userConsumption.contents
        .filter { case (_, cs) => cs.status == 2 }
        .map    { case (_, cs) => cs.contentId }
        .toList.distinct

      val completedCount = leafNodes.intersect(completedContentIds).size
      val contentStatus  =
        userConsumption.contents.map { case (_, cs) => cs.contentId -> cs.status }.toMap
      val inputContents  =
        userConsumption.contents.filter { case (_, cs) => cs.fromInput }.keys.toList

      logger.info(
        s"programEnrolConsumption: userId=$userId courseId=$courseId" +
        s" leafNodes=${leafNodes.size} completedCount=$completedCount"
      )

      if (completedCount >= leafNodes.size) {
        logger.info(
          s"programEnrolConsumption: COMPLETED userId=$userId courseId=$courseId" +
          s" completedCount=$completedCount leafNodes=${leafNodes.size}"
        )
        Some(CollectionProgress(
          userId, userConsumption.batchId, courseId,
          completedCount, new java.util.Date(), contentStatus, inputContents,
          completed = true
        ))
      } else {
        logger.info(
          s"programEnrolConsumption: IN PROGRESS userId=$userId courseId=$courseId" +
          s" completedCount=$completedCount / leafNodes=${leafNodes.size}"
        )
        Some(CollectionProgress(
          userId, userConsumption.batchId, courseId,
          completedCount, null, contentStatus, inputContents
        ))
      }
    }
  }

  def getCollectionStatus(collectionId: String): String = {
    val cacheStatus =
      collectionStatusCache.getNonExpired(collectionId).getOrElse("")
    if (StringUtils.isEmpty(cacheStatus)) {
      val dbStatus = getDBStatus(collectionId)
      collectionStatusCache =
        collectionStatusCache.putClocked(collectionId, dbStatus)._2
      dbStatus
    } else {
      cacheStatus
    }
  }


  def getDBStatus(collectionId: String): String = {
    val requestBody =
      s"""{
         |  "request": {
         |    "filters": {
         |      "objectType": "Collection",
         |      "identifier": "$collectionId",
         |      "status": ["Live", "Unlisted", "Retired"]
         |    },
         |    "fields": ["status"]
         |  }
         |}""".stripMargin

    val response = httpUtil.post(config.searchAPIURL, requestBody)
    if (response.status == 200) {
      val responseBody =
        gson.fromJson(response.body, classOf[java.util.Map[String, AnyRef]])
      val result = responseBody
        .getOrDefault("result", new java.util.HashMap[String, AnyRef]())
        .asInstanceOf[java.util.Map[String, AnyRef]]
      val count = result
        .getOrDefault("count", 0.asInstanceOf[Number])
        .asInstanceOf[Number].intValue()
      if (count > 0) {
        val list = result
          .getOrDefault(
            "content",
            new java.util.ArrayList[java.util.Map[String, AnyRef]]()
          )
          .asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
        list.asScala.head.get("status").asInstanceOf[String]
      } else {
        throw new Exception(
          s"getDBStatus: no published or retired collection with id=$collectionId"
        )
      }
    } else {
      logger.error(
        s"getDBStatus: search-service error status=${response.status} body=${response.body}"
      )
      throw new Exception(
        s"getDBStatus: search-service error status=${response.status}"
      )
    }
  }
}
