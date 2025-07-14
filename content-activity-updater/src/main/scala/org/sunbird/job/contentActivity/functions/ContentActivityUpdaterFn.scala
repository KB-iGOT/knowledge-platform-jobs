package org.sunbird.job.contentActivity.functions

import com.datastax.driver.core.Row
import com.datastax.driver.core.querybuilder.QueryBuilder
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.exception.InvalidEventException
import org.sunbird.job.contentActivity.task.ContentActivityUpdaterConfig
import org.sunbird.job.contentActivity.domain.Event
import org.sunbird.job.util.{CassandraUtil, HttpUtil, PostgresUtil}
import org.sunbird.job.{BaseProcessKeyedFunction, Metrics}
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, Timestamp}
import java.time.LocalDateTime
import java.util.UUID

class ContentActivityUpdaterFn(config: ContentActivityUpdaterConfig, httpUtil: HttpUtil)
                                   (implicit val stringTypeInfo: TypeInformation[String],
                                    @transient var postgresUtil: PostgresUtil = null, @transient var cassandraUtil: CassandraUtil = null) extends BaseProcessKeyedFunction[String, Event, String](config) {

    // Implement the process function to handle the events  
    // logger initialization
    private[this] val logger = LoggerFactory.getLogger(classOf[ContentActivityUpdaterFn])

    private var cache: DataCache = _
    private var relationCache: DataCache = _
    private var contentCache: DataCache = _
    lazy private val mapper: ObjectMapper = new ObjectMapper()

    /**
      * Method to initialize the function with necessary resources.
      *
      * @param parameters
      */
    override def open(parameters: Configuration): Unit = {
        super.open(parameters)
        cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
        postgresUtil = new PostgresUtil(config.postgresDbHost, config.postgresDbPort, config.postgresDbDatabase, config.postgresDbUsername, config.postgresDbPassword)
        val redisConnect = new RedisConnect(config)
        cache = new DataCache(config, redisConnect, config.collectionCacheStore, List())
        cache.init()
    }

    /**
      * Method to close the function and release resources.
      * This method is called when the function is no longer needed.
      */
    override def close(): Unit = {
        cassandraUtil.close()
    //    postgresUtil.close()
        cache.close()
        super.close()
    }

    /**
      * Method to return the list of metrics that will be used for tracking the job's performance.
      *
      * @return
      */
    override def metricsList(): List[String] = {
        List(config.totalEventsCount, config.dbReadCount, config.dbUpdateCount, config.failedEventCount, config.skippedEventCount, config.successEventCount,
        config.cacheHitCount, config.programCertIssueEventsCount, config.cacheMissCount)
    }

    /**
      * Method to process each event and update the content activity in the database.
      *
      * @param event
      * @param context
      * @param metrics
      */
    override def processElement(event: Event,
                                context: KeyedProcessFunction[String, Event, String]#Context,
                                metrics: Metrics): Unit = {
        // Process the event and update the content activity
        try {
            logger.info(s"Processing event: ${event.eventId} for user: ${event.userId}")
            metrics.incCounter(config.totalEventsCount)
            val userId = event.userId
            val operationType = event.operationType
            val mid = event.mid
            val contentId = event.contentId
            val status = event.status
            logger.info(s"Event details - UserId: $userId, operationType: $operationType, mid: $mid, contentId: $contentId, Status: $status")       
            } catch {
            case e: Exception =>
                logger.error(s"Error processing event: ${event.eventId} for user: ${event.userId}", e)
                metrics.incCounter(config.failedEventCount)
                throw e 
            }
        }
    
    /**
      * method to execute an update query on the PostgreSQL database
      * @param query
      */
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
        }
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
        }
    }
}
