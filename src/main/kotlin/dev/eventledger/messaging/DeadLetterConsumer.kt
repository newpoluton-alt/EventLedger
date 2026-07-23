package dev.eventledger.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.eventledger.config.EventLedgerProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID

@Component
@ConditionalOnProperty(
    prefix = "event-ledger.kafka",
    name = ["consumer-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DeadLetterConsumer(
    private val service: DeadLetterService,
) {
    @KafkaListener(
        topics = ["\${event-ledger.kafka.settlement-topic:settlements.v1}\${event-ledger.kafka.dead-letter-suffix:.DLT}"],
        groupId = "\${spring.kafka.consumer.group-id:event-ledger}-dead-letters",
        containerFactory = "deadLetterKafkaListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        service.record(record)
    }
}

@Component
class DeadLetterService(
    private val settlementRepository: SettlementRepository,
    private val objectMapper: ObjectMapper,
    private val properties: EventLedgerProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val incidentCounter: Counter =
        meterRegistry.counter("eventledger.kafka.dead.letters", "result", "recorded")
    private val duplicateCounter: Counter =
        meterRegistry.counter("eventledger.kafka.dead.letters", "result", "duplicate")

    @Transactional
    fun record(record: ConsumerRecord<String, String>) {
        val parsed = parse(record.value())
        val source = sourceCoordinates(record)
        val inserted =
            settlementRepository.recordDeadLetter(
                id = UUID.randomUUID(),
                eventId = parsed.eventId,
                paymentId = parsed.paymentId,
                originalTopic = source.topic,
                partition = source.partition,
                offset = source.offset,
                errorMessage = failureMessage(record),
                payload = parsed.safeJson,
                now = clock.instant(),
            )
        if (inserted) {
            incidentCounter.increment()
            logger.error(
                "Recorded dead-letter incident topic={} partition={} offset={} eventId={} paymentId={}",
                source.topic,
                source.partition,
                source.offset,
                parsed.eventId,
                parsed.paymentId,
            )
        } else {
            duplicateCounter.increment()
        }
    }

    private fun sourceCoordinates(record: ConsumerRecord<String, String>): SourceCoordinates {
        val topic =
            record
                .headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)
                ?.value()
                ?.let { String(it, StandardCharsets.UTF_8) }
                ?: record.topic().removeSuffix(properties.kafka.deadLetterSuffix)
        val partition =
            record
                .headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION)
                ?.value()
                ?.toIntOrNull()
                ?: record.partition()
        val offset =
            record
                .headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET)
                ?.value()
                ?.toLongOrNull()
                ?: record.offset()
        return SourceCoordinates(topic, partition, offset)
    }

    private fun parse(raw: String): ParsedDeadLetter =
        runCatching {
            val root = objectMapper.readTree(raw)
            val payload = root.get("payload") ?: root
            ParsedDeadLetter(
                eventId = root.get("eventId")?.asText()?.toUuidOrNull(),
                paymentId = payload.get("paymentId")?.asText()?.toUuidOrNull(),
                safeJson = objectMapper.writeValueAsString(root),
            )
        }.getOrElse {
            val safePayload: ObjectNode = objectMapper.createObjectNode()
            safePayload.put("raw", raw)
            ParsedDeadLetter(
                eventId = null,
                paymentId = null,
                safeJson = objectMapper.writeValueAsString(safePayload),
            )
        }

    private fun failureMessage(record: ConsumerRecord<String, String>): String {
        val preferredHeaders =
            listOf(
                "kafka_dlt-exception-message",
                "kafka_exception-message",
            )
        return preferredHeaders
            .asSequence()
            .mapNotNull { name -> record.headers().lastHeader(name)?.value() }
            .map { bytes -> String(bytes, StandardCharsets.UTF_8) }
            .firstOrNull()
            ?: "Kafka listener retry budget exhausted"
    }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private fun ByteArray.toIntOrNull(): Int? =
        takeIf { it.size == Int.SIZE_BYTES }
            ?.let { ByteBuffer.wrap(it).int }

    private fun ByteArray.toLongOrNull(): Long? =
        takeIf { it.size == Long.SIZE_BYTES }
            ?.let { ByteBuffer.wrap(it).long }
}

private data class ParsedDeadLetter(
    val eventId: UUID?,
    val paymentId: UUID?,
    val safeJson: String,
)

private data class SourceCoordinates(
    val topic: String,
    val partition: Int,
    val offset: Long,
)
