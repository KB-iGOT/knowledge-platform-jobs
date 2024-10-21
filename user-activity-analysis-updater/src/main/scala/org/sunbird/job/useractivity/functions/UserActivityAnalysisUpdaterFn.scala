package org.sunbird.job.useractivity.functions

import com.datastax.driver.core.Row
import com.datastax.driver.core.querybuilder.QueryBuilder
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.exception.InvalidEventException
import org.sunbird.job.useractivity.domain.{Event, UserDashState}
import org.sunbird.job.useractivity.task.UserActivityAnalysisUpdaterConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil, PostgresUtil}
import org.sunbird.job.{BaseProcessKeyedFunction, Metrics}
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, Timestamp}
import java.time.LocalDateTime
import java.util.UUID


class UserActivityAnalysisUpdaterFn(config: UserActivityAnalysisUpdaterConfig, httpUtil: HttpUtil)
                                   (implicit val stringTypeInfo: TypeInformation[String],
                                    @transient var postgresUtil: PostgresUtil = null, @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessKeyedFunction[String, Event, String](config) with IssueCertificateHelper {

  private[this] val logger = LoggerFactory.getLogger(classOf[UserActivityAnalysisUpdaterFn])
  private var cache: DataCache = _
  private var relationCache: DataCache = _
  private var contentCache: DataCache = _
  lazy private val mapper: ObjectMapper = new ObjectMapper()

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
    postgresUtil = new PostgresUtil(config.postgresDbHost, config.postgresDbPort, config.postgresDbDatabase, config.postgresDbUsername, config.postgresDbPassword)
    val redisConnect = new RedisConnect(config)
    cache = new DataCache(config, redisConnect, config.collectionCacheStore, List())
    cache.init()
  }

  override def close(): Unit = {
    cassandraUtil.close()
//    postgresUtil.close()
    cache.close()
    super.close()
  }

  override def metricsList(): List[String] = {
    List(config.totalEventsCount, config.dbReadCount, config.dbUpdateCount, config.failedEventCount, config.skippedEventCount, config.successEventCount,
      config.cacheHitCount, config.programCertIssueEventsCount, config.cacheMissCount)
  }

  override def processElement(event: Event,
                              context: KeyedProcessFunction[String, Event, String]#Context,
                              metrics: Metrics): Unit = {

    val userId = event.userId
    try {
      if (userId.nonEmpty) {
        // Fetch rootOrgId
        val rootOrgIdOpt = fetchRootOrgId(userId)(metrics)
        rootOrgIdOpt match {
          case Some(rootOrgId) =>
            handleUserActivityInDB(userId, rootOrgId, event)(metrics)
          case None =>
            logger.warn(s"No rootOrgId found for userId: $userId. Skipping upsert.")
        }
      } else {
        logger.warn("Received empty userId. Skipping processing.")
      }
    } catch {
      case ex: Exception => {
        logger.error("Error processing event", ex)
        throw new InvalidEventException(ex.getMessage, Map("partition" -> event.partition, "offset" -> event.offset), ex)
      }
    }
    logger.info(s"Finished processing the event for userId: $userId")
  }

  // Fetch rootOrgId from user table in Cassandra
  def fetchRootOrgId(userId: String)(implicit metrics: Metrics): Option[String] = {
    val selectQuery = QueryBuilder.select("rootorgid")
      .from(config.keyspace, config.userTable) // Adjust userTable based on your config
      .where(QueryBuilder.eq("userid", userId))

    val row: Row = cassandraUtil.findOne(selectQuery.toString)
    if (row != null) {
      Some(row.getString("rootorgid"))
    } else {
      logger.error(s"No rootOrgId found for userId: $userId")
      None
    }
  }


  /**
   * Handles user activity by updating or inserting user dashboard state in the database.
   *
   * @param userId    Unique identifier for the user.
   * @param rootOrgId Identifier for the root organization.
   * @param event     Event containing the user activity information.
   * @param metrics   Implicit metrics for tracking performance.
   */
  def handleUserActivityInDB(userId: String, rootOrgId: String, event: Event)(implicit metrics: Metrics): Unit = {
    logger.info(s"Handling user activity for userId: $userId, event: ${event.status}")
    if (event.status.equalsIgnoreCase("enrolled")) {
      // Query to check if the user is already enrolled
      val existingRecordQuery = QueryBuilder
        .select("id")
        .from(config.postgresDbTable)
        .where(QueryBuilder.eq("user_id", userId))
        .and(QueryBuilder.eq("type_identifier", event.typeId))
        .and(QueryBuilder.eq("batch_id", event.batchId))
        .and(QueryBuilder.eq("status", "enrolled"))
        .toString()

      // Use the findOne method to check if the record exists
      val existingRecord = postgresUtil.findOne(existingRecordQuery)

      existingRecord match {
        case Some(_) =>
          logger.info(s"User already enrolled: userId: $userId with typeIdentifier: ${event.typeId} and batchId: ${event.batchId}")

        case None =>
          val userDashState = createUserDashState(event, rootOrgId, userId)
          insertUserDashState(userDashState)
          logger.info(s"Inserted new user dashboard state for userId: $userId")
      }
    } else {
      val existingRecordQuery = QueryBuilder.select("id", "org_id", "content_type", "type_identifier", "user_id", "batch_id", "status", "updated_date", "enrolled_date", "created_date", "completed_date", "certification_date")
        .from(config.postgresDbTable)
        .where(QueryBuilder.eq("user_id", userId))
        .and(QueryBuilder.eq("type_identifier", event.typeId))
        .and(QueryBuilder.eq("batch_id", event.batchId))
      val existingRecord: Option[ResultSet] = postgresUtil.executeQuery(existingRecordQuery.toString)
      if (existingRecord.isDefined) {
        existingRecord match {
          case Some(rs) =>
            if (rs.next()) {
              if (event.status.equalsIgnoreCase("complete")) {
                val currentTimestamp: LocalDateTime = LocalDateTime.now()
                val timestamp: Timestamp = Timestamp.valueOf(currentTimestamp)
                val completedDate = timestamp
                val typeIdentifier = rs.getString("type_identifier")
                val batchId = rs.getString("batch_id")
                val certificateDate = timestamp;
                val updatedDate = timestamp;
                val updatedRecord = updateUserActivity(userId, event.status, completedDate, certificateDate, typeIdentifier, updatedDate, batchId)
                if (updatedRecord == 1) {
                  metrics.incCounter(config.dbUpdateCount)
                  logger.info(s"Successfully updated user activity for userId: $userId with status: ${event.status}")
                } else
                  logger.warn(s"Failed to update user activity for userId: $userId with status: ${event.status}")
              } else if (event.status.equalsIgnoreCase("certificate")) {
                val currentTimestamp: LocalDateTime = LocalDateTime.now()
                val timestamp: Timestamp = Timestamp.valueOf(currentTimestamp)
                val completedDate = timestamp
                val typeIdentifier = rs.getString("type_identifier")
                val certificateDate = timestamp
                val updatedDate = timestamp
                val batchId = rs.getString("batch_id")
                val updatedRecord = updateUserActivity(userId, event.status, completedDate, certificateDate, typeIdentifier, updatedDate, batchId)
                if (updatedRecord == 1) {
                  metrics.incCounter(config.dbUpdateCount)
                  logger.info(s"Successfully updated user activity for userId: $userId with status: ${event.status}")
                } else
                  logger.warn(s"Failed to update user activity for userId: $userId with status: ${event.status}")
              }
            } else {
              logger.warn(s"No record found for userId: $userId with typeIdentifier: ${event.typeId}")
            }
            if (rs != null) rs.close()
          case None =>
            logger.error("Query execution failed, result set is None.")
        }
      }
      else {
        logger.warn(s"No existing records found for userId: $userId.")
      }
    }
  }


  /**
   * Updates the user activity in the database based on the given parameters.
   *
   * @param userId          Unique identifier for the user.
   * @param status          Current status of the user activity (e.g., "complete", "certificate").
   * @param completedDate   Timestamp for when the activity was completed.
   * @param certificateDate Timestamp for when the certificate was issued.
   * @param typeIdentifier  Identifier for the type of user activity.
   * @param updatedDate     Timestamp for when the record was last updated.
   * @return Number of records updated (should be 1 if successful).
   */
  def updateUserActivity(
                          userId: String,
                          status: String,
                          completedDate: Timestamp,
                          certificateDate: Timestamp,
                          typeIdentifier: String,
                          updatedDate: Timestamp,
                          batchId: String
                        ): Unit = {
    var query = ""
    if (status.equalsIgnoreCase("complete")) {
      logger.info(s"Preparing query to update user activity for userId: $userId with status: $status")
      query =
        s"""
           |UPDATE user_activity
           |SET type_identifier = '$typeIdentifier',
           |    status = '$status',
           |    completed_date ='$completedDate',
           |    updated_date ='$updatedDate'
           |WHERE user_id= '$userId' and type_identifier = '$typeIdentifier' and batch_id = '$batchId'
           |""".stripMargin.replaceAll("\n", " ")
    } else if (status.equalsIgnoreCase("certificate")) {
      logger.info(s"Preparing query to update user activity for userId: $userId with status: $status")
      query =
        s"""
           |UPDATE user_activity
           |SET type_identifier = '$typeIdentifier',
           |    status = '$status',
           |    updated_date ='$updatedDate',
           |    certification_date ='$certificateDate'
           |WHERE user_id= '$userId' and type_identifier = '$typeIdentifier' and batch_id = '$batchId'
           |""".stripMargin.replaceAll("\n", " ")
    }
    else {
      logger.warn(s"Unrecognized status: $status for userId: $userId. No update will be performed.")
      return 0
    }
    logger.info(s"Executed update query for userId: $userId")
    executeUpdate(query)
  }

  /**
   * Creates a UserDashState object based on the provided event details.
   *
   * @param event     The event containing user activity information.
   * @param rootOrgId Identifier for the root organization.
   * @param userId    Unique identifier for the user.
   * @return A UserDashState instance populated with event data.
   */
  def createUserDashState(event: Event, rootOrgId: String, userId: String): UserDashState = {
    val currentTimestamp: LocalDateTime = LocalDateTime.now()
    val timestamp: Timestamp = Timestamp.valueOf(currentTimestamp)
    val userDashState = UserDashState(
      id = UUID.randomUUID().toString,
      orgId = rootOrgId,
      contentType = event.eventType,
      typeIdentifier = event.typeId,
      batchId = event.batchId,
      userId = userId,
      status = event.status,
      updatedDate = timestamp,
      enrolledDate = timestamp,
      createdDate = timestamp
    )
    logger.info(s"Created UserDashState: $userDashState")
    userDashState
  }


  /**
   * Inserts a UserDashState record into the database.
   *
   * @param userDashState The UserDashState object containing the details to be inserted.
   */
  def insertUserDashState(userDashState: UserDashState): Unit = {
    val insertQuery =
      """INSERT INTO user_activity (id, org_id, content_type, type_identifier, user_id, batch_id, status, updated_date, enrolled_date, created_date)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    val params = Seq(
      userDashState.id,
      userDashState.orgId,
      userDashState.contentType,
      userDashState.typeIdentifier,
      userDashState.userId,
      userDashState.batchId,
      userDashState.status,
      userDashState.updatedDate,
      userDashState.enrolledDate,
      userDashState.createdDate
    )
    logger.info(s"Inserting UserDashState into the database: $userDashState")
    executeInsert(insertQuery, params)
    logger.info(s"Successfully inserted UserDashState with ID: ${userDashState.id}")
  }

  /**
   * Executes an insert operation in the database using a prepared statement.
   *
   * @param query  The SQL query to execute.
   * @param params The parameters to be set in the prepared statement.
   */
  def executeInsert(query: String, params: Seq[Any]): Unit = {
    var connectionInsert: Option[Connection] = None
    var preparedStatement: Option[PreparedStatement] = None
    try {
      val connectionUrl = s"jdbc:postgresql://${config.postgresDbHost}:${config.postgresDbPort}/${config.postgresDbDatabase}"
      val connection = DriverManager.getConnection(connectionUrl, config.postgresDbUsername, config.postgresDbPassword)
      connection.setAutoCommit(true)
      preparedStatement = Some(connection.prepareStatement(query))
      logger.info(s"Insert query statement created. $preparedStatement")
      params.zipWithIndex.foreach { case (param, index) =>
        preparedStatement.get.setObject(index + 1, param)
      }
      val rowsInserted = preparedStatement.get.executeUpdate()
      logger.info(s"Insert successful: $rowsInserted rows inserted.")
    } catch {
      case ex: Exception =>
        println(s"Error during insert: ${ex.getMessage}")
    } finally {
      preparedStatement.foreach(_.close())
//      connectionInsert.foreach(_.close())
    }
  }

  def executeUpdate(query: String): Unit = {
    var connectionInsert: Option[Connection] = None
    var preparedStatement: Option[PreparedStatement] = None
    try {
      val connectionUrl = s"jdbc:postgresql://${config.postgresDbHost}:${config.postgresDbPort}/${config.postgresDbDatabase}"
      val connection = DriverManager.getConnection(connectionUrl, config.postgresDbUsername, config.postgresDbPassword)
      connection.setAutoCommit(true)
      preparedStatement = Some(connection.prepareStatement(query))
      logger.info(s"Insert query statement created. $preparedStatement")
      val rowsInserted = preparedStatement.get.executeUpdate()
      logger.info(s"Insert successful: $rowsInserted rows inserted.")
    } catch {
      case ex: Exception =>
        println(s"Error during insert: ${ex.getMessage}")
    } finally {
      preparedStatement.foreach(_.close())
//      connectionInsert.foreach(_.close())
    }
  }
}
