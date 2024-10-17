package org.sunbird.job.useranalysis.task

import java.io.File
import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.sunbird.job.useranalysis.domain.Event
import org.sunbird.job.useranalysis.functions.UserActivityAnalysisUpdaterFn
import org.sunbird.job.useranalysis.task.UserActivityAnalysisUpdaterConfig


import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.util.{FlinkUtil, HttpUtil}

class UserActivityAnalysisUpdaterTask(config: UserActivityAnalysisUpdaterConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {
    def process(): Unit = {
//        implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
        implicit val env: StreamExecutionEnvironment = StreamExecutionEnvironment.createLocalEnvironment()
        implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])
        implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])
        val source = kafkaConnector.kafkaJobRequestSource[Event](config.kafkaInputTopic)

        val progressStream =
            env.addSource(source).name(config.certificatePreProcessorConsumer)
              .uid(config.certificatePreProcessorConsumer).setParallelism(config.kafkaConsumerParallelism)
              .rebalance
              .keyBy(new CollectionCertPreProcessorKeySelector())
              .process(new UserActivityAnalysisUpdaterFn(config, httpUtil))
              .name("collection-cert-pre-processor").uid("collection-cert-pre-processor")
              .setParallelism(config.parallelism)

        progressStream.getSideOutput(config.generateCertificateOutputTag).addSink(kafkaConnector.kafkaStringSink(config.kafkaOutputTopic))
          .name(config.generateCertificateProducer).uid(config.generateCertificateProducer).setParallelism(config.generateCertificateParallelism)
        
        progressStream.getSideOutput(config.generateEventCertificateOutputTag).addSink(kafkaConnector.kafkaStringSink(config.kafkaEventOutputTopic))
          .name(config.generateEventCertificateProducer).uid(config.generateEventCertificateProducer).setParallelism(config.generateEventCertificateParallelism)
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
        val task = new UserActivityAnalysisUpdaterTask()(userActivityAnalysisUpdaterConfig, kafkaUtil, httpUtil)
        task.process()
    }
}

// $COVERAGE-ON

class CollectionCertPreProcessorKeySelector extends KeySelector[Event, String] {
    override def getKey(event: Event): String = Set(event.userId, if (event.courseId == "") event.eventId else event.courseId, event.batchId).mkString("_")
}
