package org.sunbird.job.searchindexer.compositesearch.helpers

import org.scalatest.DoNotDiscover
import org.sunbird.spec.BaseTestSpec

@DoNotDiscover
class CompositeSearchIndexerHelperSpec extends BaseTestSpec {

  object TestHelper extends CompositeSearchIndexerHelper

  "getTrainingPlanEvents" should "skip retired .img identifiers" in {
    val identifier = "do_123.img"
    val indexDocument: Map[String, AnyRef] = Map("courseCategory" -> "Comprehensive Assessment", "trainingPlan_v2" -> Map("identifier" -> "tp_old").asInstanceOf[AnyRef])
    val message: Map[String, Any] = Map(
      "transactionData" -> Map(
        "properties" -> Map(
          "status" -> Map("nv" -> "Retired")
        )
      )
    )

    val events = TestHelper.getTrainingPlanEvents(identifier, indexDocument, message)
    events shouldBe empty
  }

  it should "emit REMOVE for retired regular identifiers when trainingPlan present in document" in {
    val identifier = "do_123"
    val indexDocument: Map[String, AnyRef] = Map("courseCategory" -> "Comprehensive Assessment", "trainingPlan_v2" -> Map("identifier" -> "tp_old").asInstanceOf[AnyRef])
    val message: Map[String, Any] = Map(
      "transactionData" -> Map(
        "properties" -> Map(
          "status" -> Map("nv" -> "Retired")
        )
      )
    )

    val events = TestHelper.getTrainingPlanEvents(identifier, indexDocument, message)
    events should not be empty
    val ev = events.head
    ev should include ("REMOVE")
    ev should include ("tp_old")
    ev should include (identifier)
  }

  it should "emit ADD for non-retired .img when trainingPlan_v2 nv is present" in {
    val identifier = "do_456.img"
    val indexDocument: Map[String, AnyRef] = Map("courseCategory" -> "Comprehensive Assessment")
    val message: Map[String, Any] = Map(
      "transactionData" -> Map(
        "properties" -> Map(
          "trainingPlan_v2" -> Map("nv" -> Map("identifier" -> "tp_new"))
        )
      )
    )

    val events = TestHelper.getTrainingPlanEvents(identifier, indexDocument, message)
    events should not be empty
    val ev = events.head
    ev should include ("ADD")
    ev should include ("tp_new")
    ev should include (identifier)
  }

}

