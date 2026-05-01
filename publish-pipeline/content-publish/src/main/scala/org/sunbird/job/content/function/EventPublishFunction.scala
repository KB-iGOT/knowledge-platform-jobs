package org.sunbird.job.content.function

import akka.dispatch.ExecutionContexts
import com.google.gson.reflect.TypeToken
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.neo4j.driver.v1.exceptions.ClientException
import org.sunbird.job.content.publish.domain.Event
import org.slf4j.LoggerFactory
import org.sunbird.job.content.task.ContentPublishConfig
import org.sunbird.job.content.publish.helpers.EventPublisher
import org.sunbird.job.domain.`object`.{DefinitionCache, ObjectDefinition}
import org.sunbird.job.exception.InvalidInputException
import org.sunbird.job.helper.FailedEventHelper
import org.sunbird.job.content.util.NotificationManager
import org.sunbird.job.publish.core.{ExtDataConfig, ObjectData}
import org.sunbird.job.publish.helpers._
import org.sunbird.job.util._
import org.sunbird.job.{BaseProcessFunction, Metrics}

import java.lang.reflect.Type
import java.util.UUID
import scala.concurrent.ExecutionContext


class EventPublishFunction (config: ContentPublishConfig, httpUtil: HttpUtil,
                             @transient var neo4JUtil: Neo4JUtil = null,
                             @transient var cassandraUtil: CassandraUtil = null, 
                             @transient var definitionCache: DefinitionCache = null)
                            (implicit val stringTypeInfo: TypeInformation[String])
extends BaseProcessFunction[Event, String](config) with EventPublisher with FailedEventHelper {
    private[this] val logger = LoggerFactory.getLogger(classOf[ContentPublishFunction])
    val mapType: Type = new TypeToken[java.util.Map[String, AnyRef]]() {}.getType

    @transient var ec: ExecutionContext = _

    override def open(parameters: Configuration): Unit = {
        super.open(parameters)
        cassandraUtil = new CassandraUtil(config.cassandraHost, config.cassandraPort)
        neo4JUtil = new Neo4JUtil(config.graphRoutePath, config.graphName)
        ec = ExecutionContexts.global
        definitionCache = new DefinitionCache(config)
    }

    override def close(): Unit = {
        super.close()
        cassandraUtil.close()
    }

    override def metricsList(): List[String] = {
        List(config.eventTypePublishCount, config.eventTypePublishSuccessCount, config.eventTypePublishFailedCount, config.skippedEventCount)
    }

    override def processElement(data: Event, context: ProcessFunction[Event, String]#Context, metrics: Metrics): Unit = {
        logger.info("Event publishing started for : " + data.identifier)
        metrics.incCounter(config.eventTypePublishCount)
        val definition: ObjectDefinition = definitionCache.getDefinition(data.objectType, config.schemaSupportVersionMap.getOrElse(data.objectType.toLowerCase(), "1.0").asInstanceOf[String], config.definitionBasePath)
        val readerConfig = ExtDataConfig(config.hierarchyKeyspaceName, config.hierarchyTableName, definition.getExternalPrimaryKey, definition.getExternalProps)    
        val obj: ObjectData = getObject(data.identifier, data.pkgVersion, data.mimeType, data.publishType, readerConfig)(neo4JUtil, cassandraUtil)
        try {
            if (obj.pkgVersion > data.pkgVersion) {
                metrics.incCounter(config.skippedEventCount)
                logger.info(s"""pkgVersion should be greater than or equal to the obj.pkgVersion for : ${obj.identifier}""")
            } else {
                //There is nothing to do... just want to fire post-publish event
                pushPostProcessEvent(obj, context)(metrics)
                metrics.incCounter(config.eventTypePublishSuccessCount)
                logger.info("Event publishing completed successfully for : " + data.identifier)

                logger.info("Notification for EVENT_PUBLISH started successfully.")
                try {
                    logger.info("Node metadata is {}", obj.metadata)
                    if (!config.skipResourceTypes.contains(data.resourceType)) {
                        new NotificationManager(config.notificationUrl, httpUtil).sendNotification(
                            "EVENT_PUBLISHED",
                            "UPDATE",
                            List("global"),
                            obj.metadata("name").asInstanceOf[String],
                            Map[String, Any]("id" -> obj.identifier)
                        )
                    } else {
                        logger.info("Notification not sent as resourceType is externalTraining")
                    }
                } catch {
                    case e: Exception => logger.error("Error in sending notification for Event publish ", e)
                }

            }
        } catch {
        case ex@(_: InvalidInputException | _: ClientException | _:java.lang.IllegalArgumentException) => // ClientException - Invalid input exception.
            ex.printStackTrace()
            saveOnFailure(obj, List(ex.getMessage), data.pkgVersion)(neo4JUtil)
            pushFailedEvent(data, null, ex, context)(metrics)
            logger.error("Error while publishing content :: " + ex.getMessage)
        case ex: Exception =>
            ex.printStackTrace()
            saveOnFailure(obj, List(ex.getMessage), data.pkgVersion)(neo4JUtil)
            logger.error(s"Error while processing message for Partition: ${data.partition} and Offset: ${data.offset}. Error : ${ex.getMessage}", ex)
            throw ex
        }
    }

    def getPostProcessEvent(obj: ObjectData): String = {
        val ets = System.currentTimeMillis
        val mid = s"""LP.$ets.${UUID.randomUUID}"""
        val channelId = obj.metadata("channel")
        val ver = obj.metadata("versionKey")
        val contentType = obj.metadata("contentType")
        val status = obj.metadata("status")
        val resourceType = obj.metadata("resourceType")
        //TODO: deprecate using contentType in the event.
        val event = s"""{"eid":"BE_JOB_REQUEST", "ets": $ets, "mid": "$mid", "actor": {"id": "Post Publish Processor", "type": "System"}, "context":{"pdata":{"ver":"1.0","id":"org.sunbird.platform"}, "channel":"$channelId","env":"${config.jobEnv}"},"object":{"ver":"$ver","id":"${obj.identifier}"},"edata": {"action":"post-publish-process","iteration":1,"identifier":"${obj.identifier}","channel":"$channelId","mimeType":"${obj.mimeType}","contentType":"$contentType","pkgVersion":${obj.pkgVersion},"status":"$status","resourceType":"$resourceType","name":"${obj.metadata("name")}","trackable":${obj.metadata.getOrElse("trackable",Map.empty)}}}""".stripMargin
        logger.info(s"Post Publish Process Event for identifier ${obj.identifier}  is  : $event")
        event
    }

    private def pushPostProcessEvent(obj: ObjectData, context: ProcessFunction[Event, String]#Context)(implicit metrics: Metrics): Unit = {
        try {
            val event = getPostProcessEvent(obj)
            context.output(config.generatePostPublishProcessTag, event)
        } catch  {
        case ex: Exception =>  ex.printStackTrace()
            throw new InvalidInputException("EventPublishFunction:: pushPostProcessEvent:: Error while pushing post process event.", ex)
        }
    }

    private def pushFailedEvent(event: Event, errorMessage: String, error: Throwable, context: ProcessFunction[Event, String]#Context)(implicit metrics: Metrics): Unit = {
        val failedEvent = if (error == null) getFailedEvent(event.jobName, event.getMap(), errorMessage) else getFailedEvent(event.jobName, event.getMap(), error)
        context.output(config.failedEventOutTag, failedEvent)
        metrics.incCounter(config.eventTypePublishFailedCount)
    }
}