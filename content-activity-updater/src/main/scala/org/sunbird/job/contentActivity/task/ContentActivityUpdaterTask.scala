package org.sunbird.job.contentActivity.task

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
import org.sunbird.job.util.{FlinkUtil, HttpUtil}

class ContentActivityUpdaterTask(config: ContentActivityUpdaterConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {
  
    // logger initialization
    @transient private[this] val logger = LoggerFactory.getLogger(classOf[ContentActivityUpdaterTask])

    // Method to process the content activity updates
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


// controller object to run the task
object ContentActivityUpdaterTask {
    def main(args: Array[String]): Unit = {
        val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
        val config = configFilePath.map {
            path => ConfigFactory.parseFile(new File(path)).resolve()
        }.getOrElse(ConfigFactory.load("content-activity-updater.conf").withFallback(ConfigFactory.systemEnvironment()))
        val contentActivityUpdaterConfig = new ContentActivityUpdaterConfig(config)
        val kafkaUtil = new FlinkKafkaConnector(contentActivityUpdaterConfig)
        val httpUtil = new HttpUtil()
        val task = new ContentActivityUpdaterTask(contentActivityUpdaterConfig, kafkaUtil, httpUtil)
        task.process()
    }
}

// KeySelector implementation to extract keys for grouping events
class ContentActivityUpdaterKeySelector extends KeySelector[Event, String] {
    override def getKey(event: Event): String = Set(event.userId, event.channel, event.contentId, event.transactionData, event.status, event.createdBy, event.createdOn, event.eventId, event.operationType).mkString("_")
}