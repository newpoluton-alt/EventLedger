package dev.eventledger.messaging

import dev.eventledger.config.EventLedgerProperties
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.TopicConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaConfiguration {
    @Bean
    @ConditionalOnProperty(
        prefix = "event-ledger.kafka",
        name = ["topic-creation-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun paymentTopic(properties: EventLedgerProperties): NewTopic = topic(properties.kafka.paymentTopic, properties)

    @Bean
    @ConditionalOnProperty(
        prefix = "event-ledger.kafka",
        name = ["topic-creation-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun settlementTopic(properties: EventLedgerProperties): NewTopic = topic(properties.kafka.settlementTopic, properties)

    @Bean
    @ConditionalOnProperty(
        prefix = "event-ledger.kafka",
        name = ["topic-creation-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun paymentDeadLetterTopic(properties: EventLedgerProperties): NewTopic =
        topic(properties.kafka.paymentTopic + properties.kafka.deadLetterSuffix, properties)

    @Bean
    @ConditionalOnProperty(
        prefix = "event-ledger.kafka",
        name = ["topic-creation-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun settlementDeadLetterTopic(properties: EventLedgerProperties): NewTopic =
        topic(properties.kafka.settlementTopic + properties.kafka.deadLetterSuffix, properties)

    @Bean
    fun kafkaAdmin(kafkaProperties: org.springframework.boot.autoconfigure.kafka.KafkaProperties): KafkaAdmin =
        KafkaAdmin(
            kafkaProperties.buildAdminProperties(null) +
                mapOf(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG to 5_000),
        ).apply {
            setFatalIfBrokerNotAvailable(false)
        }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        kafkaTemplate: KafkaTemplate<String, String>,
        properties: EventLedgerProperties,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = consumerFactory
        factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD

        val recoverer =
            DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
                TopicPartition(
                    record.topic() + properties.kafka.deadLetterSuffix,
                    record.partition(),
                )
            }
        val backoff = FixedBackOff(1_000, 3)
        val errorHandler = DefaultErrorHandler(recoverer, backoff)
        errorHandler.addNotRetryableExceptions(InvalidEventException::class.java)
        factory.setCommonErrorHandler(errorHandler)
        return factory
    }

    @Bean
    fun deadLetterKafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = consumerFactory
        factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD
        factory.setCommonErrorHandler(
            DefaultErrorHandler(FixedBackOff(5_000, Long.MAX_VALUE)),
        )
        return factory
    }

    private fun topic(
        name: String,
        properties: EventLedgerProperties,
    ): NewTopic =
        TopicBuilder
            .name(name)
            .partitions(properties.kafka.partitions)
            .replicas(properties.kafka.replicationFactor.toInt())
            .config(
                TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,
                properties.kafka.minInSyncReplicas.toString(),
            ).build()
}

class InvalidEventException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
