package org.sunbird.job.usercompetencyupdate.task

import java.io.File
import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.slf4j.LoggerFactory
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.usercompetencyupdate.domain.Event
import org.sunbird.job.usercompetencyupdate.functions.UserCompetencyPreProcessorFn
import org.sunbird.job.util.{FlinkUtil, HttpUtil}


class UserCompetencyPreUpdaterTask(config: UserCompetencyUpdaterConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {
    private[this] val logger = LoggerFactory.getLogger(classOf[UserCompetencyPreUpdaterTask])

    def process(): Unit = {
        // For local IDE execution, use local Flink environment
        import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
        implicit val env: StreamExecutionEnvironment = StreamExecutionEnvironment.createLocalEnvironment()
        implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])
        implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])
        val source = kafkaConnector.kafkaJobRequestSource[Event](config.kafkaInputTopic)
        logger.info("Processing user-competency-mapping-event only")
        val progressStream =
            env.addSource(source).name(config.certificatePreProcessorConsumer)
              .uid(config.certificatePreProcessorConsumer).setParallelism(config.kafkaConsumerParallelism)
              .rebalance
              .keyBy(new UserCompetencyPreUpdaterKeySelector())
              .process(new UserCompetencyPreProcessorFn(config, httpUtil))
              .name("user-competency-updater").uid("user-competency-updater")
              .setParallelism(config.parallelism)
        // Remove all old side outputs and sinks
        env.execute(config.jobName)
    }
}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster

object UserCompetencyUpdaterTask {
    def main(args: Array[String]): Unit = {
        val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
        val config = configFilePath.map {
            path => ConfigFactory.parseFile(new File(path)).resolve()
        }.getOrElse(ConfigFactory.load("user-competency-updater.conf").withFallback(ConfigFactory.systemEnvironment()))
        val userCompetencyUpdaterConfig = new UserCompetencyUpdaterConfig(config)
        val kafkaUtil = new FlinkKafkaConnector(userCompetencyUpdaterConfig)
        val httpUtil = new HttpUtil()
        val task = new UserCompetencyPreUpdaterTask(userCompetencyUpdaterConfig, kafkaUtil, httpUtil)
        task.process()
    }
}

class UserCompetencyPreUpdaterKeySelector extends KeySelector[Event, String] {
    override def getKey(event: Event): String = Set(event.userId, event.contentId, event.batchId).mkString("_")
}