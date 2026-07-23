package dev.eventledger.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.eventledger.config.EventLedgerProperties
import dev.eventledger.outbox.OutboxRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

data class SettlementMessage(
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val paymentId: UUID,
    val providerReference: String?,
    val settledAt: Instant,
)

@Component
class SettlementEventParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(rawEvent: String): SettlementMessage {
        try {
            val root = objectMapper.readTree(rawEvent)
            val payload =
                root.get("payload")
                    ?: throw InvalidEventException("Settlement event field 'payload' is required.")
            val eventType = requiredText(root, "eventType")
            val eventVersion = requiredInt(root, "eventVersion")
            val aggregateId = requiredUuid(root, "aggregateId")
            val paymentId = requiredUuid(payload, "paymentId")

            if (eventType != SUPPORTED_EVENT_TYPE || eventVersion != SUPPORTED_EVENT_VERSION) {
                throw InvalidEventException(
                    "Unsupported settlement event '$eventType' version '$eventVersion'.",
                )
            }
            if (aggregateId != paymentId) {
                throw InvalidEventException(
                    "Settlement aggregateId '$aggregateId' does not match paymentId '$paymentId'.",
                )
            }

            return SettlementMessage(
                eventId = requiredUuid(root, "eventId"),
                eventType = eventType,
                eventVersion = eventVersion,
                aggregateId = aggregateId,
                occurredAt = Instant.parse(requiredText(root, "occurredAt")),
                paymentId = paymentId,
                providerReference = optionalProviderReference(payload),
                settledAt = Instant.parse(requiredText(payload, "settledAt")),
            )
        } catch (exception: InvalidEventException) {
            throw exception
        } catch (exception: Exception) {
            throw InvalidEventException("Settlement event is malformed.", exception)
        }
    }

    private fun requiredUuid(
        node: JsonNode,
        field: String,
    ): UUID = UUID.fromString(requiredText(node, field))

    private fun requiredInt(
        node: JsonNode,
        field: String,
    ): Int {
        val value = node.get(field)
        if (value == null || !value.isIntegralNumber || !value.canConvertToInt()) {
            throw InvalidEventException("Settlement event field '$field' must be an integer.")
        }
        return value.intValue()
    }

    private fun requiredText(
        node: JsonNode,
        field: String,
    ): String {
        val value = node.get(field)?.asText()?.takeIf(String::isNotBlank)
        return value ?: throw InvalidEventException("Settlement event field '$field' is required.")
    }

    private fun optionalProviderReference(payload: JsonNode): String? {
        val value = payload.get("providerReference")
        if (value == null || value.isNull) {
            return null
        }
        if (!value.isTextual) {
            throw InvalidEventException("Settlement event field 'providerReference' must be a string or null.")
        }
        return value
            .textValue()
            .trim()
            .takeIf(String::isNotBlank)
            ?.also {
                if (it.length > MAX_PROVIDER_REFERENCE_LENGTH) {
                    throw InvalidEventException(
                        "Settlement providerReference exceeds $MAX_PROVIDER_REFERENCE_LENGTH characters.",
                    )
                }
            }
    }

    companion object {
        const val SUPPORTED_EVENT_TYPE = "settlement.confirmed.v1"
        const val SUPPORTED_EVENT_VERSION = 1
        private const val MAX_PROVIDER_REFERENCE_LENGTH = 160
    }
}

@Component
@ConditionalOnProperty(
    prefix = "event-ledger.kafka",
    name = ["consumer-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SettlementConsumer(
    private val parser: SettlementEventParser,
    private val settlementService: SettlementService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${event-ledger.kafka.settlement-topic:settlements.v1}"],
        groupId = "\${spring.kafka.consumer.group-id:event-ledger}",
    )
    fun consume(rawEvent: String) {
        val event = parser.parse(rawEvent)
        val processed = settlementService.process(event)
        logger.info(
            "Settlement event handled eventId={} paymentId={} applied={}",
            event.eventId,
            event.paymentId,
            processed,
        )
    }
}

@Service
class SettlementService(
    private val settlementRepository: SettlementRepository,
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val properties: EventLedgerProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val appliedCounter: Counter =
        meterRegistry.counter("eventledger.consumer.events", "result", "applied")
    private val duplicateCounter: Counter =
        meterRegistry.counter("eventledger.consumer.events", "result", "duplicate")

    @Transactional
    fun process(event: SettlementMessage): Boolean = process(event, recovery = false)

    @Transactional
    fun recover(event: SettlementMessage): Boolean = process(event, recovery = true)

    private fun process(
        event: SettlementMessage,
        recovery: Boolean,
    ): Boolean {
        val now = clock.instant()
        when (
            settlementRepository.recordInbox(
                consumerName = CONSUMER_NAME,
                eventId = event.eventId,
                aggregateId = event.aggregateId,
                eventFingerprint = fingerprint(event),
                now = now,
            )
        ) {
            InboxRecordResult.CONFLICT ->
                throw InvalidEventException(
                    "Settlement eventId '${event.eventId}' was reused with different content.",
                )
            InboxRecordResult.DUPLICATE -> {
                verifyAlreadyApplied(event)
                duplicateCounter.increment()
                return false
            }
            InboxRecordResult.RECORDED -> Unit
        }

        val payment =
            settlementRepository.lockPayment(event.paymentId)
                ?: throw InvalidEventException("Payment '${event.paymentId}' does not exist.")
        validateTimeline(event, payment, now)

        val existingSettlement = settlementRepository.findSettlement(event.paymentId)
        if (existingSettlement != null) {
            if (
                payment.status == "SETTLED" &&
                existingSettlement.providerReference == event.providerReference &&
                existingSettlement.settledAt == event.settledAt
            ) {
                duplicateCounter.increment()
                return false
            }
            throw InvalidEventException(
                "Payment '${event.paymentId}' already has a different final settlement.",
            )
        }

        if (payment.status == "NEEDS_ATTENTION" && !recovery) {
            throw InvalidEventException(
                "Payment '${event.paymentId}' is marked NEEDS_ATTENTION and cannot settle automatically.",
            )
        }
        if (payment.status !in setOf("POSTED", "NEEDS_ATTENTION")) {
            throw InvalidEventException(
                "Payment '${event.paymentId}' cannot settle from status '${payment.status}'.",
            )
        }

        settlementRepository.recordSettlement(
            eventId = event.eventId,
            paymentId = event.paymentId,
            providerReference = event.providerReference,
            settledAt = event.settledAt,
            receivedAt = now,
        )
        val transitioned =
            settlementRepository.markPaymentSettled(
                paymentId = event.paymentId,
                settledAt = event.settledAt,
                now = now,
                allowNeedsAttention = recovery,
            )
        if (!transitioned) {
            throw InvalidEventException(
                "Payment '${event.paymentId}' changed state before settlement could be applied.",
            )
        }

        val outboxEventId = UUID.randomUUID()
        val envelope =
            EventEnvelope(
                eventId = outboxEventId,
                eventType = "payment.settled.v1",
                eventVersion = 1,
                aggregateId = event.paymentId,
                occurredAt = now,
                traceId = MDC.get("traceId"),
                payload =
                    PaymentSettledPayload(
                        paymentId = event.paymentId,
                        providerReference = event.providerReference,
                        settledAt = event.settledAt,
                    ),
            )
        outboxRepository.insert(
            id = outboxEventId,
            aggregateType = "payment",
            aggregateId = event.paymentId,
            aggregateSequence = 2,
            eventType = envelope.eventType,
            eventVersion = envelope.eventVersion,
            topic = properties.kafka.paymentTopic,
            eventKey = event.paymentId.toString(),
            payload = objectMapper.writeValueAsString(envelope),
            now = now,
        )

        appliedCounter.increment()
        return true
    }

    private fun verifyAlreadyApplied(event: SettlementMessage) {
        val payment =
            settlementRepository.lockPayment(event.paymentId)
                ?: throw InvalidEventException("Payment '${event.paymentId}' does not exist.")
        val settlement = settlementRepository.findSettlement(event.paymentId)
        if (
            payment.status != "SETTLED" ||
            settlement == null ||
            settlement.eventId != event.eventId ||
            settlement.providerReference != event.providerReference ||
            settlement.settledAt != event.settledAt
        ) {
            throw InvalidEventException(
                "Settlement event '${event.eventId}' is recorded but its payment state does not match.",
            )
        }
    }

    private fun fingerprint(event: SettlementMessage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(event)))
    }

    private fun validateTimeline(
        event: SettlementMessage,
        payment: LockedPayment,
        now: Instant,
    ) {
        if (event.settledAt.isBefore(payment.createdAt)) {
            throw InvalidEventException(
                "Settlement time '${event.settledAt}' precedes payment creation '${payment.createdAt}'.",
            )
        }
        val latestAcceptedTime = now.plus(MAX_FUTURE_SKEW)
        if (event.settledAt.isAfter(latestAcceptedTime) || event.occurredAt.isAfter(latestAcceptedTime)) {
            throw InvalidEventException(
                "Settlement event time exceeds the allowed future clock skew.",
            )
        }
    }

    companion object {
        private const val CONSUMER_NAME = "settlement-consumer-v1"
        private val MAX_FUTURE_SKEW = java.time.Duration.ofMinutes(5)
    }
}
