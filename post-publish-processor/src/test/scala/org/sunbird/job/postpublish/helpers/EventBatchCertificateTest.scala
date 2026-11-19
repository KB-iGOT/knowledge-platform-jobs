package org.sunbird.job.postpublish.helpers

import com.datastax.driver.core.Row
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.streaming.api.scala.OutputTag
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyString, eq => equalTo}
import org.mockito.Mockito.{never, verify, verifyNoInteractions, when}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar
import org.sunbird.job.Metrics
import org.sunbird.job.cache.DataCache
import org.sunbird.job.postpublish.domain.Event
import org.sunbird.job.postpublish.functions.{EventBatchCreateFunction, PostPublishEventRouter}
import org.sunbird.job.postpublish.task.PostPublishProcessorConfig
import org.sunbird.job.util.{CassandraUtil, HTTPResponse, HttpUtil, Neo4JUtil}

import java.util

class EventBatchCertificateTest extends FlatSpec with Matchers with MockitoSugar {

  private val jsonMapper = new ObjectMapper()

  private class Fixture(required: Option[String]) {
    val eventId = "webinar-1"
    val batchId = "batch-1"
    val createPath = "/private/v2/event/batch/create"
    val attachPath = "/private/v1/event/batch/cert/template/add"
    val config = mock[PostPublishProcessorConfig]
    val neo4j = mock[Neo4JUtil]
    val cassandra = mock[CassandraUtil]
    val http = mock[HttpUtil]
    val context = mock[ProcessFunction[Event, String]#Context]
    val batchTag = OutputTag[util.Map[String, AnyRef]]("event-batch-create")(
      TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]]))

    when(config.totalEventsCount).thenReturn("total-events-count")
    when(config.skippedEventCount).thenReturn("skipped-events-count")
    when(config.eventBatchCreationCount).thenReturn("event-batch-creation-count")
    when(config.eventBatchCreationSuccessCount).thenReturn("event-batch-creation-success-count")
    when(config.eventBatchCreationFailedCount).thenReturn("event-batch-creation-failed-count")
    when(config.eventBatchCreateOutTag).thenReturn(batchTag)
    when(config.allowedResourceTypesForEventBatch).thenReturn(util.Collections.singletonList("Webinar"))
    when(config.lmsKeyspaceName).thenReturn("sunbird_courses")
    when(config.eventBatchTableName).thenReturn("event_batch")
    when(config.sunbirdKeyspaceName).thenReturn("sunbird")
    when(config.sbSystemSettingsTableName).thenReturn("system_settings")
    when(config.defaultEventCertTemplateId).thenReturn("default_event_template")
    when(config.identifier).thenReturn("identifier")
    when(config.resourseType).thenReturn("resourceType")
    when(config.resourceTypeDetails).thenReturn("resourceTypeDetails")
    when(config.resourceTypeCertTemplateMap).thenReturn(util.Collections.emptyMap[String, String]())
    when(config.eventBatchCreateApiPath).thenReturn(createPath)
    when(config.batchAddCertTemplateAPIPathForEvent).thenReturn(attachPath)

    private val metadata = new util.HashMap[String, AnyRef]()
    metadata.put("name", "Webinar")
    metadata.put("contentType", "Event")
    metadata.put("resourceType", "Webinar")
    metadata.put("trackable", """{"enabled":"Yes","autoBatch":"Yes"}""")
    metadata.put("startDate", "2026-11-20")
    metadata.put("endDate", "2026-11-20")
    required.foreach(value => metadata.put("certificateRequired", value))
    when(neo4j.getNodeProperties(eventId)).thenReturn(metadata)
    when(cassandra.find(anyString())).thenReturn(util.Collections.emptyList[Row]())

    private val eventData = new util.HashMap[String, AnyRef]()
    eventData.put("action", "post-publish-process")
    eventData.put("contentType", "Event")
    eventData.put("identifier", eventId)
    private val event = new Event(util.Collections.singletonMap[String, Any]("edata", eventData), 0, 0L)

    private val courseLinker = mock[SamuhikCharchaEventLinkCourse]
    private val router = new PostPublishEventRouter(config, http, neo4j, cassandra) {
      override def linkEventDetailsToCourse(id: String)(
        metrics: Metrics, jobConfig: PostPublishProcessorConfig, cache: DataCache, httpUtil: HttpUtil
      ): Unit = courseLinker.linkEventDetailsToCourse(id)(metrics, jobConfig, cache, httpUtil)
    }
    private val routerMetrics = router.registerMetrics(router.metricsList())

    def routeEvent(): Unit = {
      router.processElement(event, context, routerMetrics)
      verify(courseLinker).linkEventDetailsToCourse(eventId)(routerMetrics, config, null, http)
      routerMetrics.get(config.totalEventsCount) shouldBe 1L
      routerMetrics.get(config.skippedEventCount) shouldBe 0L
    }

    def createBatch(): Unit = {
      routeEvent()
      val details = ArgumentCaptor.forClass(classOf[util.Map[String, AnyRef]])
      verify(context).output(equalTo(batchTag), details.capture())
      details.getValue.containsKey("certificateRequired") shouldBe required.isDefined
      Option(details.getValue.get("certificateRequired")) shouldBe required

      when(http.post(equalTo(createPath), anyString(), any[Map[String, String]]()))
        .thenReturn(HTTPResponse(200, s"""{"result":{"batchId":"$batchId"}}"""))
      val batchFunction = new EventBatchCreateFunction(config, http, cassandra)
      val metrics = batchFunction.registerMetrics(batchFunction.metricsList())
      batchFunction.processElement(details.getValue, null, metrics)
      metrics.get(config.eventBatchCreationSuccessCount) shouldBe 1L

      val body = ArgumentCaptor.forClass(classOf[String])
      verify(http).post(equalTo(createPath), body.capture(), any[Map[String, String]]())
      val request = jsonMapper.readTree(body.getValue).path("request")
      request.path("eventId").asText() shouldBe eventId
      request.has("certificateRequired") shouldBe false
      request.path("batchAttributes").has("certificateRequired") shouldBe false
    }

    def stubAttachment(): Unit = {
      val row = mock[Row]
      when(row.getString("value")).thenReturn("""{"identifier":"template-1"}""")
      when(cassandra.findOne(anyString())).thenReturn(row)
      when(http.patch(equalTo(attachPath), anyString(), any[Map[String, String]]()))
        .thenReturn(HTTPResponse(200, "{}"))
    }

    def verifyAttachment(): Unit = {
      val body = ArgumentCaptor.forClass(classOf[String])
      verify(http).patch(equalTo(attachPath), body.capture(), any[Map[String, String]]())
      val batch = jsonMapper.readTree(body.getValue).path("request").path("batch")
      batch.path("eventId").asText() shouldBe eventId
      batch.path("batchId").asText() shouldBe batchId
      batch.path("template").path("identifier").asText() shouldBe "template-1"
    }
  }

  "Event batch creation" should "propagate No and create the batch without looking up or attaching a template" in {
    val fixture = new Fixture(Some("No"))
    fixture.createBatch()
    verify(fixture.cassandra, never()).findOne(anyString())
    verify(fixture.http, never()).patch(anyString(), anyString(), any[Map[String, String]]())
  }

  it should "attach the existing template when certificateRequired is Yes" in {
    val fixture = new Fixture(Some("Yes"))
    fixture.stubAttachment()
    fixture.createBatch()
    fixture.verifyAttachment()
  }

  it should "attach the existing template when certificateRequired is absent" in {
    val fixture = new Fixture(None)
    fixture.stubAttachment()
    fixture.createBatch()
    fixture.verifyAttachment()
  }

  it should "still link the Event to its course when an open batch already exists" in {
    val fixture = new Fixture(Some("No"))
    val openBatch = mock[Row]
    when(openBatch.getString("enrollmenttype")).thenReturn("open")
    when(openBatch.getInt("status")).thenReturn(1)
    when(fixture.cassandra.find(anyString())).thenReturn(util.Collections.singletonList(openBatch))

    fixture.routeEvent()

    verifyNoInteractions(fixture.context, fixture.http)
  }
}
