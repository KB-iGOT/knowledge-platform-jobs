package org.sunbird.job.content.function

import akka.dispatch.ExecutionContexts
import com.google.gson.reflect.TypeToken
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.neo4j.driver.v1.exceptions.ClientException
import org.slf4j.LoggerFactory
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.content.publish.domain.Event
import org.sunbird.job.content.publish.helpers.CollectionPublisher
import org.sunbird.job.content.task.ContentPublishConfig
import org.sunbird.job.content.util.NotificationManager
import org.sunbird.job.domain.`object`.{DefinitionCache, ObjectDefinition}
import org.sunbird.job.exception.InvalidInputException
import org.sunbird.job.helper.FailedEventHelper
import org.sunbird.job.publish.core.{DefinitionConfig, ExtDataConfig, ObjectData}
import org.sunbird.job.publish.helpers.EcarPackageType
import org.sunbird.job.util._
import org.sunbird.job.{BaseProcessFunction, Metrics}

import java.lang.reflect.Type
import java.util.UUID
import scala.concurrent.ExecutionContext

class CollectionPublishFunction(config: ContentPublishConfig, httpUtil: HttpUtil,
                                @transient var neo4JUtil: Neo4JUtil = null,
                                @transient var cassandraUtil: CassandraUtil = null,
                                @transient var esUtil: ElasticSearchUtil = null,
                                @transient var cloudStorageUtil: CloudStorageUtil = null,
                                @transient var definitionCache: DefinitionCache = null,
                                @transient var definitionConfig: DefinitionConfig = null)
                               (implicit val stringTypeInfo: TypeInformation[String])
  extends BaseProcessFunction[Event, String](config) with CollectionPublisher with FailedEventHelper {

  private[this] val logger = LoggerFactory.getLogger(classOf[CollectionPublishFunction])
  val mapType: Type = new TypeToken[java.util.Map[String, AnyRef]]() {}.getType
  private var cache: DataCache = _
  private val COLLECTION_CACHE_KEY_PREFIX = "hierarchy_"
  private val COLLECTION_CACHE_KEY_SUFFIX = ":leafnodes"

  @transient var ec: ExecutionContext = _
  private val pkgTypes = List(EcarPackageType.SPINE, EcarPackageType.ONLINE)

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.cassandraHost, config.cassandraPort)
    neo4JUtil = new Neo4JUtil(config.graphRoutePath, config.graphName)
    esUtil = new ElasticSearchUtil(config.esConnectionInfo, config.compositeSearchIndexName, config.compositeSearchIndexType)
    cloudStorageUtil = new CloudStorageUtil(config)
    ec = ExecutionContexts.global
    definitionCache = new DefinitionCache(config)
    definitionConfig = DefinitionConfig(config.schemaSupportVersionMap, config.definitionBasePath)
    cache = new DataCache(config, new RedisConnect(config), config.nodeStore, List())
    cache.init()
  }

  override def close(): Unit = {
    super.close()
    cassandraUtil.close()
    cache.close()
  }

  override def metricsList(): List[String] = {
    List(config.collectionPublishEventCount, config.collectionPublishSuccessEventCount, config.collectionPublishFailedEventCount, config.skippedEventCount, config.collectionPostPublishProcessEventCount, config.trainingPlanV2EventCount)
  }

  override def processElement(data: Event, context: ProcessFunction[Event, String]#Context, metrics: Metrics): Unit = {
    val definition: ObjectDefinition = definitionCache.getDefinition(data.objectType, config.schemaSupportVersionMap.getOrElse(data.objectType.toLowerCase(), "1.0").asInstanceOf[String], config.definitionBasePath)
    val readerConfig = ExtDataConfig(config.hierarchyKeyspaceName, config.hierarchyTableName, definition.getExternalPrimaryKey, definition.getExternalProps)
    logger.info("Collection publishing started for : " + data.identifier)
    metrics.incCounter(config.collectionPublishEventCount)
    val obj: ObjectData = getObject(data.identifier, data.pkgVersion, data.mimeType, data.publishType, readerConfig)(neo4JUtil, cassandraUtil)
    try {
      if (obj.pkgVersion > data.pkgVersion) {
        metrics.incCounter(config.skippedEventCount)
        logger.info(s"""pkgVersion should be greater than or equal to the obj.pkgVersion for : ${obj.identifier}""")
      } else {
        val updObj = new ObjectData(obj.identifier, obj.metadata ++ Map("lastPublishedBy" -> data.lastPublishedBy, "dialcodes" -> obj.metadata.getOrElse("dialcodes",null)), obj.extData, obj.hierarchy)
        val messages: List[String] = List.empty[String] // validate(obj, obj.identifier, validateMetadata)
        if (messages.isEmpty) {
          // Pre-publish update
          updateProcessingNode(updObj)(neo4JUtil, cassandraUtil, readerConfig, definitionCache, definitionConfig)

          val isCollectionShallowCopy = isContentShallowCopy(updObj)
          val updatedObj = if (isCollectionShallowCopy) updateOriginPkgVersion(updObj)(neo4JUtil) else updObj

          // Clear redis cache
          cache.delWithRetry(data.identifier)
          cache.delWithRetry(data.identifier + COLLECTION_CACHE_KEY_SUFFIX)
          cache.delWithRetry(COLLECTION_CACHE_KEY_PREFIX + data.identifier)

          // Collection - add step to remove units of already Live content from redis - line 243 in PublishFinalizer
          val unitNodes = if (obj.identifier.endsWith(".img")) {
            val childNodes = getUnitsFromLiveContent(updatedObj)(cassandraUtil, readerConfig)
            childNodes.filter(rec => rec.nonEmpty).foreach(childId => cache.delWithRetry(COLLECTION_CACHE_KEY_PREFIX + childId))
            childNodes.filter(rec => rec.nonEmpty)
          } else List.empty

          var enrichedObj = enrichObject(updatedObj)(neo4JUtil, cassandraUtil, readerConfig, cloudStorageUtil, config, definitionCache, definitionConfig)
          logger.info("CollectionPublishFunction:: Collection Object Enriched: " + enrichedObj.identifier)
          
          var objWithEcar = getObjectWithEcar(enrichedObj, pkgTypes, config.isECARGenerationEnabled)(ec, neo4JUtil, cassandraUtil, readerConfig, cloudStorageUtil, config, definitionCache, definitionConfig, httpUtil)
          
          val collRelationalMetadata = getRelationalMetadata(obj.identifier, obj.pkgVersion-1, readerConfig)(cassandraUtil).getOrElse(Map.empty[String, AnyRef])

          val publishObj = new ObjectData(objWithEcar.identifier, objWithEcar.metadata.-("children"), objWithEcar.extData, objWithEcar.hierarchy)
          val previousTrainingPlan = getTrainingPlanV2(publishObj.identifier)(neo4JUtil)
          saveOnSuccess(publishObj)(neo4JUtil, cassandraUtil, readerConfig, definitionCache, definitionConfig)
          pushTrainingPlanLinkEvent(publishObj, previousTrainingPlan, context)(metrics)
          logger.info("CollectionPublishFunction:: Published Collection Object metadata saved successfully to graph DB: " + objWithEcar.identifier)

          val variantsJsonString = ScalaJsonUtil.serialize(objWithEcar.metadata("variants"))
          val publishType = objWithEcar.getString("publish_type", "Public")
          val successObj = new ObjectData(objWithEcar.identifier, objWithEcar.metadata + ("status" -> (if (publishType.equalsIgnoreCase("Unlisted")) "Unlisted" else "Live"), "variants" -> variantsJsonString, "identifier" -> objWithEcar.identifier), objWithEcar.extData, objWithEcar.hierarchy)
          val children = successObj.hierarchy.getOrElse(Map()).getOrElse("children", List()).asInstanceOf[List[Map[String, AnyRef]]]

          // Collection - update and publish children - line 418 in PublishFinalizer
          val updatedChildren = updateHierarchyMetadata(children, successObj.metadata, collRelationalMetadata)(config)
          logger.info("CollectionPublishFunction:: Hierarchy Metadata updated for Collection Object: " + successObj.identifier + " || updatedChildren:: " + updatedChildren)
          publishHierarchy(updatedChildren, successObj, readerConfig, config)(cassandraUtil)

          //TODO: Save IMAGE Object with enrichedObj children and collRelationalMetadata when pkgVersion is 1 - verify with MaheshG
          if(data.pkgVersion == 1) {
            saveImageHierarchy(enrichedObj, readerConfig, collRelationalMetadata)(cassandraUtil)
          }

          if (!isCollectionShallowCopy) syncNodes(successObj, updatedChildren, unitNodes)(esUtil, neo4JUtil, cassandraUtil, readerConfig, definition, config)
          pushPostProcessEvent(successObj, context)(metrics)
          metrics.incCounter(config.collectionPublishSuccessEventCount)
          val courseCategory = Option(enrichedObj.metadata.getOrElse("courseCategory", null))
          logger.info("CollectionPublishFunction:: Collection publishing completed successfully for : " + data.identifier)
          try {
            logger.info("Node metadata is {}", obj.metadata)
            if (!courseCategory.exists(_.toString.equalsIgnoreCase(config.LEARNING_PATHWAY))) {
              new NotificationManager(config.notificationUrl, httpUtil).sendNotification(
                "CONTENT_PUBLISHED",
                "UPDATE",
                List(obj.metadata("createdBy").asInstanceOf[String]),
                obj.metadata("name").asInstanceOf[String],
                Map[String, Any]("id" -> obj.identifier)
              )
            }
          } catch {
            case e: Exception => logger.info("Error in sending notification ", e)
          }
           logger.info("Course publish notification started successfully for Collection : " + data.identifier)
            if (courseCategory.exists(cat =>
             cat.toString.equalsIgnoreCase("Course") ||
             cat.toString.equalsIgnoreCase("Moderated Course") ||
              cat.toString.equalsIgnoreCase("Case Study"))
               ) {
                  try {
                       logger.info("Course publish courseCategory : " + courseCategory)
                       logger.info("Node metadata is {}", obj.metadata)
                       new NotificationManager(config.notificationUrl, httpUtil).sendNotification(
                         "COURSE_PUBLISHED",
                         "UPDATE",
                          List("global"),
                         obj.metadata("name").asInstanceOf[String],
                         Map[String, Any]("id" -> obj.identifier)
                       )
                     } catch {
                       case e: Exception => logger.info("Error in sending notification for course publish collection", e)
                     }
                   }

               logger.info("Program publish completed successfully for Collection : " + data.identifier)
               val programCategory = Option(enrichedObj.metadata.getOrElse("courseCategory", null))
               if (programCategory.exists(cat =>
                  cat.toString.equalsIgnoreCase("Curated Program") ||
                  cat.toString.equalsIgnoreCase("Invite-only program") ||
                  cat.toString.equalsIgnoreCase("Moderated Program") ||
                  cat.toString.equalsIgnoreCase("Blended Program"))
                  ) {
                     try {
                      logger.info("Node metadata is {}", obj.metadata.toString)
                      new NotificationManager(config.notificationUrl, httpUtil).sendNotification(
                       "PROGRAM_PUBLISHED",
                       "ENGAGEMENT",
                        List("global"),
                        obj.metadata("name").asInstanceOf[String],
                        Map[String, Any]("id" -> obj.identifier)
                       )
                   } catch {
                     case e: Exception => logger.error("Error in sending notification for program", e)
                   }
                   }
        } else {
          saveOnFailure(obj, messages, data.pkgVersion)(neo4JUtil)
          val errorMessages = messages.mkString("; ")
          pushFailedEvent(data, errorMessages, null, context)(metrics)
          logger.info("CollectionPublishFunction:: Collection publishing failed for : " + data.identifier)
        }
      }
    } catch {
      case ex@(_: InvalidInputException | _: ClientException) => // ClientException - Invalid input exception.
        ex.printStackTrace()
        saveOnFailure(obj, List(ex.getMessage), data.pkgVersion)(neo4JUtil)
        pushFailedEvent(data, null, ex, context)(metrics)
        logger.error(s"CollectionPublishFunction::Error while publishing collection :: ${data.partition} and Offset: ${data.offset}. Error : ${ex.getMessage}", ex)
      case ex: Exception =>
        ex.printStackTrace()
        saveOnFailure(obj, List(ex.getMessage), data.pkgVersion)(neo4JUtil)
        logger.error(s"CollectionPublishFunction::Error while processing message for Partition: ${data.partition} and Offset: ${data.offset}. Error : ${ex.getMessage}", ex)
        throw ex
    }
  }

  private def pushPostProcessEvent(obj: ObjectData, context: ProcessFunction[Event, String]#Context)(implicit metrics: Metrics): Unit = {
    try {
      val event = getPostProcessEvent(obj)
      context.output(config.generatePostPublishProcessTag, event)
      metrics.incCounter(config.collectionPostPublishProcessEventCount)
    } catch  {
      case ex: Exception =>  ex.printStackTrace()
        throw new InvalidInputException("CollectionPublisher:: pushPostProcessEvent:: Error while pushing post process event.", ex)
    }
  }

  def getPostProcessEvent(obj: ObjectData): String = {
    val ets = System.currentTimeMillis
    val mid = s"""LP.$ets.${UUID.randomUUID}"""
    val channelId = obj.metadata("channel")
    val ver = obj.metadata("versionKey")
    val contentType = obj.metadata("contentType")
    val status = obj.metadata("status")
    //TODO: deprecate using contentType in the event.
    val event = s"""{"eid":"BE_JOB_REQUEST", "ets": $ets, "mid": "$mid", "actor": {"id": "Post Publish Processor", "type": "System"}, "context":{"pdata":{"ver":"1.0","id":"org.sunbird.platform"}, "channel":"$channelId","env":"${config.jobEnv}"},"object":{"ver":"$ver","id":"${obj.identifier}"},"edata": {"action":"post-publish-process","iteration":1,"identifier":"${obj.identifier}","channel":"$channelId","mimeType":"${obj.mimeType}","contentType":"$contentType","pkgVersion":${obj.pkgVersion},"status":"$status","name":"${obj.metadata("name")}","trackable":${obj.metadata.getOrElse("trackable",Map.empty)}}}""".stripMargin
    logger.info(s"Post Publish Process Event for identifier ${obj.identifier}  is  : $event")
    event
  }
  private def pushFailedEvent(event: Event, errorMessage: String, error: Throwable, context: ProcessFunction[Event, String]#Context)(implicit metrics: Metrics): Unit = {
    val failedEvent = if (error == null) getFailedEvent(event.jobName, event.getMap(), errorMessage) else getFailedEvent(event.jobName, event.getMap(), error)
    context.output(config.failedEventOutTag, failedEvent)
    metrics.incCounter(config.collectionPublishFailedEventCount)
  }

  private val TRAINING_PLAN_V2 = "trainingPlan_v2"

  // trainingPlan_v2 is persisted as a JSON-serialized string (see ObjectUpdater.metaDataQuery), so both the
  // Neo4j-read value and the in-memory ObjectData value (populated straight off Neo4j by ObjectReader.getMetadata,
  // with no deserialization) arrive as raw JSON strings, not parsed maps - never cast, always parse defensively.
  private def parseTrainingPlan(value: AnyRef): Option[Map[String, AnyRef]] = value match {
    case s: String if s.trim.nonEmpty =>
      try Some(ScalaJsonUtil.deserialize[Map[String, AnyRef]](s)) catch {
        case ex: Exception =>
          logger.error(s"Failed to parse $TRAINING_PLAN_V2 value: $s", ex)
          None
      }
    case m: Map[String@unchecked, AnyRef@unchecked] => Some(m.asInstanceOf[Map[String, AnyRef]])
    case _ => None
  }

  def getTrainingPlanV2(identifier: String)(implicit neo4JUtil: Neo4JUtil): Option[Map[String, AnyRef]] = {
    val props = neo4JUtil.getNodeProperties(identifier)
    if (null == props) None else parseTrainingPlan(props.get(TRAINING_PLAN_V2))
  }

  private def pushTrainingPlanLinkEvent(obj: ObjectData, previousTrainingPlan: Option[Map[String, AnyRef]], context: ProcessFunction[Event, String]#Context)(implicit metrics: Metrics): Unit = {
    val currentTrainingPlan = parseTrainingPlan(obj.metadata.getOrElse(TRAINING_PLAN_V2, null))
    if (!previousTrainingPlan.equals(currentTrainingPlan)) {
      val event = getTrainingPlanLinkEvent(obj, previousTrainingPlan, currentTrainingPlan)
      context.output(config.trainingPlanV2OutTag, event)
      metrics.incCounter(config.trainingPlanV2EventCount)
    }
  }

  def getTrainingPlanLinkEvent(obj: ObjectData, previousTrainingPlan: Option[Map[String, AnyRef]], currentTrainingPlan: Option[Map[String, AnyRef]]): String = {
    val ets = System.currentTimeMillis
    val mid = s"""LP.$ets.${UUID.randomUUID}"""
    val channelId = obj.getString("channel", "")
    val event = ScalaJsonUtil.serialize(Map(
      "eid" -> "CA_TRAININGPLAN_LINK",
      "ets" -> ets,
      "mid" -> mid,
      "identifier" -> obj.identifier,
      "objectType" -> "Collection",
      "channel" -> channelId,
      "trainingPlan" -> Map("previous" -> previousTrainingPlan.orNull, "current" -> currentTrainingPlan.orNull)
    ))
    logger.info(s"Training Plan Link Event for identifier ${obj.identifier} is : $event")
    event
  }

}
