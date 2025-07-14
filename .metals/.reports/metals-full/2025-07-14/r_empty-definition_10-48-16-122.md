error id: file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala:`<none>`.
file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 962
uri: file://<WORKSPACE>/content_activity_updater/src/main/scala/org/sunbird/job/contentActivity/task/ContentActivityUpdaterTask.scala
text:
```scala
package org.sunbird.job.contentActivity.domain

import java.io.File
import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.slf4j.LoggerFactory
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.contentActivity.domain.Event
import org.sunbird.job.contentActivity.functions.ContentActivityUpdaterFn
import org.sunbird.job.contentActivity.config.ContentActivityUpdaterConfig
import org.sunbird.job.contentActivity.functions.ContentActivityUpdaterKeySelector
import org.sunbird.job.util.{FlinkUtil, HttpUtil}

class ContentActivityUpdaterTask(config: ContentActivityUpdaterConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {
  /*  pr@@ivate[this] val logger = LoggerFactory.getLogger(classOf[UserActivityAnalysisUpdaterTask])*/
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
              .keyBy(new ContentActivityUpdaterKeySelector())
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
        val kafkaUtil = new FlinkKafkaConnector(userActivityAnalysisUpdaterConfig)
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

empty definition using pc, found symbol in pc: `<none>`.