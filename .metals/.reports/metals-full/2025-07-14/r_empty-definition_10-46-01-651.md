error id: file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala:`<init>`.
file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala
empty definition using pc, found symbol in pc: `<init>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 2319
uri: file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala
text:
```scala
package org.sunbird.job.contentActivity.domain

class ContentActivityUpdaterTask(config: ContentActivityUpdaterConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {
  /*  private[this] val logger = LoggerFactory.getLogger(classOf[UserActivityAnalysisUpdaterTask])*/
    @transient private[this] val logger = LoggerFactory.getLogger(classOf[ContnetActivityUpdaterTask])

    def process(): Unit = {
        // Create the Flink execution environment
        implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
        //implicit val env: StreamExecutionEnvironment = StreamExecutionEnvironment.createLocalEnvironment()
        implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])
        implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])

        // Source: Fetch data from Kafka
        val source = kafkaConnector.kafkaJobRequestSource[Event](config.kafkaInputTopic)
        logger.info("This is under process for task")


        // Process Stream: Process the events and handle side outputs for certificates
        val progressStream =
            env.addSource(source).name(config.certificatePreProcessorConsumer)
              .uid(config.certificatePreProcessorConsumer).setParallelism(config.kafkaConsumerParallelism)
              .rebalance
              .keyBy(new UserActivityAnalysisUpdaterKeySelector())
              .process(new ContentActivityUpdaterFn(config, httpUtil))
              .name("content-activity-updater").uid("content-activity-updater")
              .setParallelism(config.parallelism)
        env.execute(config.jobName)
    }
}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster

object ContentActivityUpdaterTask {
    def main(args: Array[String]): Unit = {
        val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
        val config = configFilePath.map {
            path => ConfigFactory.parseFile(new File(path)).resolve()
        }.getOrElse(ConfigFactory.load("content-activity-updater.conf").withFallback(ConfigFactory.systemEnvironment()))
        val userActivityAnalysisUpdaterConfig = new UserActivityAnalysisUpdaterConfig(config)
        val kafkaUtil = n@@ew FlinkKafkaConnector(userActivityAnalysisUpdaterConfig)
        val httpUtil = new HttpUtil()
        val task = new UserActivityAnalysisUpdaterTask(userActivityAnalysisUpdaterConfig, kafkaUtil, httpUtil)
        task.process()
    }
}

class ContentActivityUpdaterKeySelector extends KeySelector[Event, String] {
    override def getKey(event: Event): String = Set(event.userId, event.typeId, event.batchId,event.eventType,event.status).mkString("_")
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: `<init>`.