error id: file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala:[1527..1532) in Input.VirtualFile("file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala", "package org.sunbird.job.contentActivity.domain

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
            env.process(new ContentActivityUpdaterFn(config, httpUtil))
              .name("content-activity-updater").uid("content-activity-updater")
              .setParallelism(config.parallelism)
        env.execute(config.jobName)
    }
}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster

object class ContentActivityUpdaterTask(config: ContentActivityUpdaterConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {
 {
    def main(args: Array[String]): Unit = {
        val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
        val config = configFilePath.map {
            path => ConfigFactory.parseFile(new File(path)).resolve()
        }.getOrElse(ConfigFactory.load("content-activity-updater.conf").withFallback(ConfigFactory.systemEnvironment()))
        val userActivityAnalysisUpdaterConfig = new UserActivityAnalysisUpdaterConfig(config)
        val kafkaUtil = new FlinkKafkaConnector(userActivityAnalysisUpdaterConfig)
        val httpUtil = new HttpUtil()
        val task = new UserActivityAnalysisUpdaterTask(userActivityAnalysisUpdaterConfig, kafkaUtil, httpUtil)
        task.process()
    }
}

class UserActivityAnalysisUpdaterKeySelector extends KeySelector[Event, String] {
    override def getKey(event: Event): String = Set(event.userId, event.typeId, event.batchId,event.eventType,event.status).mkString("_")
}")
file://<WORKSPACE>/file:<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala
file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala:30: error: expected identifier; obtained class
object class ContentActivityUpdaterTask(config: ContentActivityUpdaterConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {
       ^
#### Short summary: 

expected identifier; obtained class