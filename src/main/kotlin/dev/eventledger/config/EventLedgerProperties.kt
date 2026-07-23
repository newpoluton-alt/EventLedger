package dev.eventledger.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("event-ledger")
data class EventLedgerProperties(
    val outbox: Outbox = Outbox(),
    val reconciliation: Reconciliation = Reconciliation(),
    val kafka: Kafka = Kafka(),
    val security: Security = Security(),
) {
    data class Outbox(
        val enabled: Boolean = true,
        val batchSize: Int = 10,
        val fixedDelayMs: Long = 500,
        val lockDuration: Duration = Duration.ofMinutes(1),
        val sendTimeout: Duration = Duration.ofSeconds(5),
        val maxAttempts: Int = 12,
        val initialBackoff: Duration = Duration.ofSeconds(1),
        val maxBackoff: Duration = Duration.ofMinutes(5),
    )

    data class Reconciliation(
        val enabled: Boolean = true,
        val fixedDelayMs: Long = 30_000,
        val stalePaymentAge: Duration = Duration.ofMinutes(10),
        val staleOutboxAge: Duration = Duration.ofMinutes(5),
    )

    data class Kafka(
        val paymentTopic: String = "payments.v1",
        val settlementTopic: String = "settlements.v1",
        val deadLetterSuffix: String = ".DLT",
        val partitions: Int = 6,
        val replicationFactor: Short = 1,
        val minInSyncReplicas: Int = 1,
        val topicCreationEnabled: Boolean = true,
        val consumerEnabled: Boolean = true,
    )

    data class Security(
        val apiKey: String = "",
    )
}
