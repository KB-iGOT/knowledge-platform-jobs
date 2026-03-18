package org.sunbird.job.userbadgeawarding.task

import java.io.File
import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.slf4j.LoggerFactory
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.userbadgeawarding.domain.Event
import org.sunbird.job.userbadgeawarding.functions.UserAchievementPreProcessorFn
import org.sunbird.job.util.{FlinkUtil, HttpUtil}


class UserAchievementPreProcessorTask(config: UserBadgeAwardingConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {
    private[this] val logger = LoggerFactory.getLogger(classOf[UserAchievementPreProcessorTask])

    def process(): Unit = {
        // For local IDE execution, use local Flink environment
        import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
        implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
        implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])
        implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])
        val source = kafkaConnector.kafkaJobRequestSource[Event](config.kafkaInputTopic)
        logger.info("Processing user-badge-awarding events")
        val progressStream =
            env.addSource(source).name("user-badge-awarding-consumer")
              .uid("user-badge-awarding-consumer").setParallelism(config.kafkaConsumerParallelism)
              .rebalance
              .keyBy(new UserAchievementPreProcessorKeySelector())
              .process(new UserAchievementPreProcessorFn(config, httpUtil))
              .name("user-badge-awarding-processor").uid("user-badge-awarding-processor")
              .setParallelism(config.parallelism)
      progressStream.getSideOutput(config.generateBadgeFailedOutputTag).addSink(kafkaConnector.kafkaStringSink(config.kafkaFailedTopic))
        .name(config.generateBadgeFailedEventProducer).uid(config.generateBadgeFailedEventProducer).setParallelism(config.generateBadgeParallelism)
      env.execute(config.jobName)
    }
}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster

object UserBadgeAwardingTask {
    def main(args: Array[String]): Unit = {
        val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
        val config = configFilePath.map {
            path => ConfigFactory.parseFile(new File(path)).resolve()
        }.getOrElse(ConfigFactory.load("user-achievement-processor.conf").withFallback(ConfigFactory.systemEnvironment()))
        val badgeAwardingConfig = new UserBadgeAwardingConfig(config)
        val kafkaUtil = new FlinkKafkaConnector(badgeAwardingConfig)
        val httpUtil = new HttpUtil()
        val task = new UserAchievementPreProcessorTask(badgeAwardingConfig, kafkaUtil, httpUtil)
        task.process()
    }
}

class UserAchievementPreProcessorKeySelector extends KeySelector[Event, String] {
    override def getKey(event: Event): String = Set(event.userId, event.contentId, event.batchId).mkString("_")
}