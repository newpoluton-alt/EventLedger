package dev.eventledger.messaging

import java.time.Instant
import java.util.UUID

data class EventEnvelope<T>(
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val traceId: String?,
    val payload: T,
)

data class PaymentPostedPayload(
    val paymentId: UUID,
    val sourceAccountId: UUID,
    val destinationAccountId: UUID,
    val amountMinor: Long,
    val currency: String,
    val reference: String?,
)

data class PaymentSettledPayload(
    val paymentId: UUID,
    val providerReference: String?,
    val settledAt: Instant,
)
