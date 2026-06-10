package org.sunbird.job.spec

import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

// TODO: implement base test spec in next phase
// Mirror BaseActivityAggregateTestSpec from program-activity-aggregate-updater
abstract class BaseActivityAggregateTestSpecV2 extends FlatSpec with Matchers with BeforeAndAfterAll {
  // TODO: set up embedded Cassandra, Redis, Flink test harness
}

