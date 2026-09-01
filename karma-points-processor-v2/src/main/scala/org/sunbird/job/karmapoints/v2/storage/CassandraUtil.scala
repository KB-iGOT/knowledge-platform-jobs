package org.sunbird.job.karmapoints.v2.storage

import com.datastax.driver.core.Row
import com.datastax.driver.core.exceptions.DriverException
import com.datastax.driver.core.querybuilder.{Insert, QueryBuilder, Select}
import org.apache.commons.lang3.StringUtils
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.karmapoints.v2.config.KarmaPointsV2Config
import org.sunbird.job.karmapoints.v2.exceptions.{CassandraException, InvalidUserException}
import org.sunbird.job.util.{JSONUtil, CassandraUtil => JobsCoreCassandraUtil}

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util
import java.util.Date
import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`

/**
 * V2 storage layer for karma-points Cassandra access. Same tables/columns as V1's
 * `karma-points-persist-processor` `Utility` object (no schema changes) - business logic that
 * decided *when* to call these (point amounts, ACBP decay, etc.) now lives in the handlers, this
 * class only knows how to read/write rows and translate driver failures into [[CassandraException]]
 * so the 2-path error strategy in KarmaPointsProcessorFnV2 can tell data-quality apart from infra failures.
 */
class CassandraUtil(config: KarmaPointsV2Config, cassandraUtil: JobsCoreCassandraUtil) {

  private[this] val logger = LoggerFactory.getLogger(classOf[CassandraUtil])
  private lazy val mapper: ObjectMapper = new ObjectMapper()

  private def guard[T](opName: String)(f: => T): T = {
    try f catch {
      case ex: DriverException =>
        logger.error(s"Cassandra operation failed: $opName", ex)
        throw CassandraException(s"Cassandra operation failed: $opName", Some(ex))
    }
  }

  def close(): Unit = cassandraUtil.close()

  // ---- Reads ----

  def fetchContentHierarchy(courseId: String)(implicit metrics: Metrics): util.HashMap[String, AnyRef] = guard("fetchContentHierarchy") {
    val query: Select.Where = QueryBuilder
      .select(config.HIERARCHY)
      .from(config.content_hierarchy_KeySpace, config.content_hierarchy_table)
      .where(QueryBuilder.eq(config.IDENTIFIER, courseId))
    metrics.incCounter(config.dbReadCount)
    val rows = cassandraUtil.find(query.toString)
    if (rows != null && rows.size() > 0) {
      val hierarchy = rows.get(0).getString(config.HIERARCHY)
      try {
        mapper.readValue(hierarchy, classOf[java.util.Map[String, AnyRef]]).asInstanceOf[util.HashMap[String, AnyRef]]
      } catch {
        case e: Exception =>
          logger.error(s"Failed to parse hierarchy JSON for courseId: $courseId", e)
          throw e
      }
    } else new util.HashMap[String, AnyRef]()
  }

  def doesAssessmentExistInHierarchy(hierarchy: java.util.Map[String, AnyRef]): String = {
    val childrenMap = hierarchy.get(config.CHILDREN).asInstanceOf[util.ArrayList[util.HashMap[String, AnyRef]]]
    if (childrenMap == null) return config.EMPTY
    for (children <- childrenMap) {
      if (children.get(config.PRIMARY_CATEGORY) == config.COURSE_ASSESSMENT) {
        return children.get(config.IDENTIFIER).asInstanceOf[String]
      }
    }
    config.EMPTY
  }

  def fetchUserAssessmentResult(userId: String, assessmentId: String): util.List[Row] = guard("fetchUserAssessmentResult") {
    val query: Select = QueryBuilder.select(config.DB_COLUMN_SUBMIT_ASSESSMENT_RESPONSE)
      .from(config.sunbird_keyspace, config.user_assessment_data_table)
    query.where(QueryBuilder.eq(config.DB_COLUMN_USERID, userId))
      .and(QueryBuilder.eq(config.DB_COLUMN_ASSESSMENT_ID, assessmentId)).limit(1)
    cassandraUtil.find(query.toString)
  }

  def fetchUserKarmaPointsCreditLookup(userId: String, contextType: String, operationType: String, contextId: String): util.List[Row] =
    guard("fetchUserKarmaPointsCreditLookup") {
      val query: Select = QueryBuilder.select().from(config.sunbird_keyspace, config.user_karma_points_credit_lookup_table)
      query.where(QueryBuilder.eq(config.DB_COLUMN_USER_KARMA_POINTS_KEY, userId + config.PIPE + contextType + config.PIPE + contextId))
        .and(QueryBuilder.eq(config.DB_COLUMN_OPERATION_TYPE, operationType))
      cassandraUtil.find(query.toString)
    }

  def fetchUserKarmaPoints(creditDate: Date, userId: String, contextType: String, operationType: String, contextId: String): util.List[Row] =
    guard("fetchUserKarmaPoints") {
      val query: Select = QueryBuilder.select().from(config.sunbird_keyspace, config.user_karma_points_table)
      query.where(QueryBuilder.eq(config.DB_COLUMN_USERID, userId))
        .and(QueryBuilder.eq(config.DB_COLUMN_CREDIT_DATE, creditDate))
        .and(QueryBuilder.eq(config.DB_COLUMN_CONTEXT_TYPE, contextType))
        .and(QueryBuilder.eq(config.DB_COLUMN_OPERATION_TYPE, operationType))
        .and(QueryBuilder.eq(config.DB_COLUMN_CONTEXT_ID, contextId))
      cassandraUtil.find(query.toString)
    }

  def doesEntryExist(userId: String, contextType: String, operationType: String, contextId: String): Boolean = {
    val lookup = fetchUserKarmaPointsCreditLookup(userId, contextType, operationType, contextId)
    if (lookup == null || lookup.size() < 1) return false
    val creditDate = lookup.get(0).getObject(config.DB_COLUMN_CREDIT_DATE).asInstanceOf[Date]
    fetchUserKarmaPoints(creditDate, userId, contextType, operationType, contextId).size() > 0
  }

  def hasEarnedFirstEnrolmentPoints(userId: String): Boolean = guard("hasEarnedFirstEnrolmentPoints") {
    val query: Select = QueryBuilder.select().from(config.sunbird_keyspace, config.user_karma_points_table)
    query.where(QueryBuilder.eq(config.DB_COLUMN_USERID, userId))
    val rows = cassandraUtil.find(query.toString)
    rows != null && rows.exists(row =>
      config.OPERATION_TYPE_ENROLMENT.equals(row.getString(config.DB_COLUMN_OPERATION_TYPE)) && row.getInt(config.POINTS) > 0)
  }

  def hasReachedNonACBPMonthlyCutOff(userId: String): Boolean = {
    val (_, infoMap) = readSummary(userId)
    val currentDateStr = LocalDate.now.format(DateTimeFormatter.ofPattern(config.YYYY_PIPE_MM))
    var quotaCount = 0
    val currStr = infoMap.get(config.FORMATTED_MONTH)
    if (currStr != null && currentDateStr.equals(currStr)) {
      quotaCount = infoMap.getOrDefault(config.CLAIMED_NON_ACBP_COURSE_KARMA_QUOTA, Integer.valueOf(0)).asInstanceOf[Int]
    }
    quotaCount >= config.nonAcbpCourseQuota
  }

  /** Existence check for the given user id, per the storage API contract (no throw on absence). */
  def getUserExists(userId: String): Boolean = guard("getUserExists") {
    val query: Select = QueryBuilder.select(config.identifier).from(config.sunbird_keyspace, config.user_table)
    query.where(QueryBuilder.eq(config.ID, userId.trim))
    val rows = cassandraUtil.find(query.toString)
    rows != null && rows.size() > 0
  }

  /** Same lookup as [[getUserExists]] but returns the root-org-id needed for CB-Plan headers, throwing
   * InvalidUserException (data-quality, not infra) when the user row genuinely doesn't exist. */
  def fetchUserRootOrgId(userId: String): String = guard("fetchUserRootOrgId") {
    val query: Select = QueryBuilder.select(config.ROOT_ORG_ID).from(config.sunbird_keyspace, config.user_table)
    query.where(QueryBuilder.eq(config.ID, userId.trim))
    val rows = cassandraUtil.find(query.toString)
    if (rows == null || rows.size() < 1) {
      throw InvalidUserException(s"No user record found for userId: $userId")
    }
    rows.get(0).getString(config.ROOT_ORG_ID)
  }

  def fetchUserBatch(courseId: String, batchId: String): util.List[Row] = guard("fetchUserBatch") {
    val query: Select = QueryBuilder.select().from(config.sunbird_courses_keyspace, config.course_batch_table)
    query.where(QueryBuilder.eq(config.DB_COLUMN_COURSE_ID, courseId)).and(QueryBuilder.eq(config.DB_COLUMN_BATCH_ID, batchId))
    cassandraUtil.find(query.toString)
  }

  def fetchUserKpSummary(userId: String): util.List[Row] = guard("fetchUserKpSummary") {
    val query: Select = QueryBuilder.select().from(config.sunbird_keyspace, config.user_karma_summary_table)
    query.where(QueryBuilder.eq(config.DB_COLUMN_USERID, userId))
    cassandraUtil.find(query.toString)
  }

  private def readSummary(userId: String): (Int, java.util.Map[String, Any]) = {
    val rows = fetchUserKpSummary(userId)
    if (rows.size() > 0) {
      val total = rows.get(0).getInt(config.TOTAL_POINTS)
      val info = rows.get(0).getString(config.ADD_INFO)
      val infoMap = if (StringUtils.isEmpty(info)) new util.HashMap[String, Any]() else JSONUtil.deserialize[java.util.HashMap[String, Any]](info)
      (total, infoMap)
    } else (0, new util.HashMap[String, Any]())
  }

  // ---- Writes ----

  def buildAddInfo(existingAddInfo: String, updates: (String, Any)*): String = {
    val infoMap: java.util.Map[String, Any] = if (StringUtils.isEmpty(existingAddInfo)) new util.HashMap[String, Any]()
    else JSONUtil.deserialize[java.util.HashMap[String, Any]](existingAddInfo)
    updates.foreach { case (key, value) => infoMap.put(key, value.asInstanceOf[AnyRef]) }
    mapper.writeValueAsString(infoMap)
  }

  private def updatePoints(userId: String, contextType: String, operationType: String, contextId: String,
                           points: Int, addInfo: String, creditDate: Long): Boolean = guard("updatePoints") {
    val query: Insert = QueryBuilder.insertInto(config.sunbird_keyspace, config.user_karma_points_table)
      .value(config.USER_ID, userId)
      .value(config.CREDIT_DATE, creditDate)
      .value(config.CONTEXT_TYPE, contextType)
      .value(config.OPERATION_TYPE, operationType)
      .value(config.CONTEXT_ID, contextId)
      .value(config.ADD_INFO, addInfo)
      .value(config.POINTS, points)
    cassandraUtil.upsert(query.toString)
  }

  private def insertKarmaCreditLookup(userId: String, contextType: String, operationType: String,
                                      contextId: String, creditDate: Long): Boolean = guard("insertKarmaCreditLookup") {
    val query: Insert = QueryBuilder.insertInto(config.sunbird_keyspace, config.user_karma_points_credit_lookup_table)
      .value(config.DB_COLUMN_USER_KARMA_POINTS_KEY, userId + config.PIPE + contextType + config.PIPE + contextId)
      .value(config.DB_COLUMN_OPERATION_TYPE, operationType)
      .value(config.DB_COLUMN_CREDIT_DATE, creditDate)
    cassandraUtil.upsert(query.toString)
  }

  /** Insert a brand-new karma-points credit row (new courses/first-time credits). */
  def insertKarmaPoints(userId: String, contextType: String, operationType: String, contextId: String,
                        points: Int, addInfo: String, creditDate: Long = System.currentTimeMillis())
                       (implicit metrics: Metrics): Unit = {
    val applied = updatePoints(userId, contextType, operationType, contextId, points, addInfo, creditDate)
    if (!applied) {
      throw CassandraException(s"Database insert was not applied for userId=$userId, operationType=$operationType, contextId=$contextId")
    }
    insertKarmaCreditLookup(userId, contextType, operationType, contextId, creditDate)
    metrics.incCounter(config.dbUpdateCount)
  }

  /** Update an existing karma-points row in place (same credit_date) - used for re-enrolment / ACBP top-up. */
  def updateExistingKarmaPoints(userId: String, contextType: String, operationType: String, contextId: String,
                                points: Int, addInfo: String, creditDate: Long)(implicit metrics: Metrics): Unit = {
    val applied = updatePoints(userId, contextType, operationType, contextId, points, addInfo, creditDate)
    if (!applied) {
      throw CassandraException(s"Database update was not applied for userId=$userId, operationType=$operationType, contextId=$contextId")
    }
    metrics.incCounter(config.dbUpdateCount)
  }

  /** Writes the new summary total to Cassandra and returns it. Redis mirroring is the caller's job (RedisUtil). */
  def updateUserKarmaPointsSummary(userId: String, points: Int, addInfo: String): Int = guard("updateUserKarmaPointsSummary") {
    val query: Insert = QueryBuilder.insertInto(config.sunbird_keyspace, config.user_karma_summary_table)
      .value(config.USER_ID, userId)
      .value(config.TOTAL_POINTS, points)
    if (addInfo != null) query.value(config.ADD_INFO, addInfo)
    cassandraUtil.upsert(query.toString)
    points
  }

  /** Adds `points` to the user's running total. Returns the new total (for Redis mirroring). */
  def addToKarmaSummary(userId: String, points: Int): Int = {
    val (total, _) = readSummary(userId)
    updateUserKarmaPointsSummary(userId, total + points, null)
  }

  /**
   * Same as [[addToKarmaSummary]] but also tracks the monthly non-ACBP course quota counter used to
   * cap non-ACBP course-completion awards (V1 `Utility.processUserKarmaSummaryUpdate`).
   *
   * @param nonACBPQuota +1 to consume a slot, -1 to free one (ACBP claim on an already-completed course).
   * @return the new running total.
   */
  def applyKarmaSummaryUpdate(userId: String, points: Int, nonACBPQuota: Int): Int = {
    val (total, infoMap) = readSummary(userId)
    val currentDateStr = LocalDate.now.format(DateTimeFormatter.ofPattern(config.YYYY_PIPE_MM))
    var quotaCount = 0
    val currStr = infoMap.get(config.FORMATTED_MONTH)
    if (currStr != null && currentDateStr.equals(currStr)) {
      quotaCount = infoMap.getOrDefault(config.CLAIMED_NON_ACBP_COURSE_KARMA_QUOTA, Integer.valueOf(0)).asInstanceOf[Int]
    }
    quotaCount = Math.max(0, quotaCount + nonACBPQuota)
    infoMap.put(config.CLAIMED_NON_ACBP_COURSE_KARMA_QUOTA, quotaCount)
    infoMap.put(config.FORMATTED_MONTH, currentDateStr)
    val info = mapper.writeValueAsString(infoMap)
    updateUserKarmaPointsSummary(userId, total + points, info)
  }

  /**
   * Zeroes out an existing entry in place (unenrolment reversal). Returns the amount reverted
   * (as a negative delta the caller should apply to the summary/Redis total), or 0 if nothing to revert.
   */
  def revertKarmaPoints(userId: String, contextType: String, operationType: String, contextId: String,
                        addInfoRevertFlagKey: String)(implicit metrics: Metrics): Int = {
    val lookup = fetchUserKarmaPointsCreditLookup(userId, contextType, operationType, contextId)
    if (lookup == null || lookup.isEmpty) return 0
    val creditDate = lookup.get(0).getObject(config.DB_COLUMN_CREDIT_DATE).asInstanceOf[Date]
    val entry = fetchUserKarmaPoints(creditDate, userId, contextType, operationType, contextId)
    if (entry == null || entry.isEmpty) return 0
    val currentPoints = entry.get(0).getInt(config.POINTS)
    if (currentPoints == 0) return 0
    val addInfo = buildAddInfo(entry.get(0).getString(config.ADD_INFO), addInfoRevertFlagKey -> java.lang.Boolean.TRUE)
    updateExistingKarmaPoints(userId, contextType, operationType, contextId, 0, addInfo, creditDate.getTime)
    metrics.incCounter(config.dbUpdateCount)
    -currentPoints
  }
}
