package org.sunbird.job.useractivity.task

import java.io.File
import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.slf4j.LoggerFactory
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.useractivity.domain.Event
import org.sunbird.job.useractivity.functions.UserActivityAnalysisUpdaterFn
import org.sunbird.job.util.{FlinkUtil, HttpUtil}

class UserActivityAnalysisUpdaterTask(config: UserActivityAnalysisUpdaterConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {
  /*  private[this] val logger = LoggerFactory.getLogger(classOf[UserActivityAnalysisUpdaterTask])*/
    @transient private[this] val logger = LoggerFactory.getLogger(classOf[UserActivityAnalysisUpdaterTask])

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
              .process(new UserActivityAnalysisUpdaterFn(config, httpUtil))
              .name("user-activity-analysis-updater").uid("user-activity-analysis-updater")
              .setParallelism(config.parallelism)
        env.execute(config.jobName)
    }
}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster

object UserActivityAnalysisUpdaterTask {
    def main(args: Array[String]): Unit = {
        val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
        val config = configFilePath.map {
            path => ConfigFactory.parseFile(new File(path)).resolve()
        }.getOrElse(ConfigFactory.load("user-activity-analysis-updater.conf").withFallback(ConfigFactory.systemEnvironment()))
        val userActivityAnalysisUpdaterConfig = new UserActivityAnalysisUpdaterConfig(config)
        val kafkaUtil = new FlinkKafkaConnector(userActivityAnalysisUpdaterConfig)
        val httpUtil = new HttpUtil()
        val task = new UserActivityAnalysisUpdaterTask(userActivityAnalysisUpdaterConfig, kafkaUtil, httpUtil)
        task.process()
    }
}

class UserActivityAnalysisUpdaterKeySelector extends KeySelector[Event, String] {
    override def getKey(event: Event): String = Set(event.userId, event.typeId, event.batchId,event.eventType,event.status).mkString("_")
}