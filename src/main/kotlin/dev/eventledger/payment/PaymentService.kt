package dev.eventledger.payment

import com.fasterxml.jackson.databind.ObjectMapper
import dev.eventledger.account.AccountRepository
import dev.eventledger.account.AccountStatus
import dev.eventledger.config.EventLedgerProperties
import dev.eventledger.ledger.LedgerRepository
import dev.eventledger.messaging.EventEnvelope
import dev.eventledger.messaging.PaymentPostedPayload
import dev.eventledger.outbox.OutboxRepository
import dev.eventledger.shared.ConflictException
import dev.eventledger.shared.DependencyUnavailableException
import dev.eventledger.shared.InvalidRequestException
import dev.eventledger.shared.Money
import dev.eventledger.shared.NotFoundException
import dev.eventledger.shared.UnprocessableException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.UUID

@Service
class PaymentService(
    private val accountRepository: AccountRepository,
    private val paymentRepository: PaymentRepository,
    private val idempotencyRepository: IdempotencyRepository,
    private val ledgerRepository: LedgerRepository,
    private val outboxRepository: OutboxRepository,
    private val requestHasher: PaymentRequestHasher,
    private val objectMapper: ObjectMapper,
    private val properties: EventLedgerProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val createdCounter: Counter =
        meterRegistry.counter("eventledger.payments", "result", "created")
    private val replayedCounter: Counter =
        meterRegistry.counter("eventledger.payments", "result", "replayed")
    private val rejectedCounter: Counter =
        meterRegistry.counter("eventledger.payments", "result", "rejected")

    @Transactional
    fun create(
        request: CreatePaymentRequest,
        rawIdempotencyKey: String,
    ): PaymentCreation {
        val idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey)
        if (request.sourceAccountId == request.destinationAccountId) {
            rejectedCounter.increment()
            throw InvalidRequestException(
                "SAME_ACCOUNT_TRANSFER",
                "Source and destination accounts must be different.",
            )
        }

        val amountMinor = Money.toMinor(request.amount)
        val currency = request.currency.uppercase(Locale.ROOT)
        val reference = request.reference?.trim()?.takeIf(String::isNotEmpty)
        val requestHash = requestHasher.hash(request, amountMinor)
        val now = clock.instant()
        val operation = "CREATE_PAYMENT"

        val started =
            idempotencyRepository.tryStart(
                operation = operation,
                key = idempotencyKey,
                requestHash = requestHash,
                now = now,
                expiresAt = now.plus(Duration.ofHours(24)),
            )
        if (!started) {
            return replayExisting(operation, idempotencyKey, requestHash)
        }

        val lockedAccounts =
            accountRepository
                .lockForTransfer(setOf(request.sourceAccountId, request.destinationAccountId))
                .associateBy { it.id }
        val source =
            lockedAccounts[request.sourceAccountId]
                ?: throw NotFoundException(
                    "ACCOUNT_NOT_FOUND",
                    "Source account '${request.sourceAccountId}' does not exist.",
                )
        val destination =
            lockedAccounts[request.destinationAccountId]
                ?: throw NotFoundException(
                    "ACCOUNT_NOT_FOUND",
                    "Destination account '${request.destinationAccountId}' does not exist.",
                )

        if (source.status != AccountStatus.ACTIVE || destination.status != AccountStatus.ACTIVE) {
            rejectedCounter.increment()
            throw UnprocessableException(
                "ACCOUNT_NOT_ACTIVE",
                "Both accounts must be active before a payment can be posted.",
            )
        }
        if (source.currency != currency || destination.currency != currency) {
            rejectedCounter.increment()
            throw UnprocessableException(
                "CURRENCY_MISMATCH",
                "Payment currency must match both ledger accounts.",
            )
        }
        if (!source.allowNegative && source.balanceMinor < amountMinor) {
            rejectedCounter.increment()
            throw UnprocessableException(
                "INSUFFICIENT_FUNDS",
                "Source account has insufficient posted funds.",
            )
        }

        val paymentId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        val eventId = UUID.randomUUID()

        paymentRepository.insert(
            id = paymentId,
            idempotencyKey = idempotencyKey,
            sourceAccountId = source.id,
            destinationAccountId = destination.id,
            amountMinor = amountMinor,
            currency = currency,
            reference = reference,
            now = now,
        )
        ledgerRepository.postPayment(
            entryId = entryId,
            paymentId = paymentId,
            sourceAccountId = source.id,
            destinationAccountId = destination.id,
            amountMinor = amountMinor,
            currency = currency,
            reference = reference,
            now = now,
        )
        accountRepository.applyTransfer(
            sourceAccountId = source.id,
            destinationAccountId = destination.id,
            amountMinor = amountMinor,
            now = now,
        )

        val response =
            PaymentResponse(
                id = paymentId,
                idempotencyKey = idempotencyKey,
                sourceAccountId = source.id,
                destinationAccountId = destination.id,
                amount = Money.fromMinor(amountMinor),
                amountMinor = amountMinor,
                currency = currency,
                reference = reference,
                status = PaymentStatus.POSTED,
                createdAt = now,
                settledAt = null,
            )
        val envelope =
            EventEnvelope(
                eventId = eventId,
                eventType = "payment.posted.v1",
                eventVersion = 1,
                aggregateId = paymentId,
                occurredAt = now,
                traceId = MDC.get("traceId"),
                payload =
                    PaymentPostedPayload(
                        paymentId = paymentId,
                        sourceAccountId = source.id,
                        destinationAccountId = destination.id,
                        amountMinor = amountMinor,
                        currency = currency,
                        reference = reference,
                    ),
            )
        outboxRepository.insert(
            id = eventId,
            aggregateType = "payment",
            aggregateId = paymentId,
            aggregateSequence = 1,
            eventType = envelope.eventType,
            eventVersion = envelope.eventVersion,
            topic = properties.kafka.paymentTopic,
            eventKey = paymentId.toString(),
            payload = objectMapper.writeValueAsString(envelope),
            now = now,
        )
        idempotencyRepository.complete(
            operation = operation,
            key = idempotencyKey,
            resourceId = paymentId,
            responseBody = objectMapper.writeValueAsString(response),
        )

        createdCounter.increment()
        return PaymentCreation(response, replay = false)
    }

    fun get(paymentId: UUID): PaymentResponse =
        paymentRepository.findById(paymentId)?.toResponse()
            ?: throw NotFoundException("PAYMENT_NOT_FOUND", "Payment '$paymentId' does not exist.")

    private fun replayExisting(
        operation: String,
        idempotencyKey: String,
        requestHash: String,
    ): PaymentCreation {
        val record =
            idempotencyRepository.find(operation, idempotencyKey)
                ?: throw DependencyUnavailableException(
                    "The idempotency result is temporarily unavailable; retry the request.",
                )
        if (record.requestHash != requestHash) {
            rejectedCounter.increment()
            throw ConflictException(
                "IDEMPOTENCY_KEY_REUSED",
                "That idempotency key was already used for a different payment request.",
            )
        }
        if (record.state != "COMPLETED") {
            throw DependencyUnavailableException(
                "The original request is still being committed; retry shortly with the same key.",
            )
        }
        val resourceId =
            record.resourceId
                ?: throw DependencyUnavailableException(
                    "The original request is still being committed; retry shortly with the same key.",
                )
        val storedResponse =
            record.responseBody?.let { body ->
                runCatching {
                    objectMapper.readValue(body, PaymentResponse::class.java)
                }.getOrNull()
            }
        if (storedResponse != null) {
            replayedCounter.increment()
            return PaymentCreation(storedResponse, replay = true)
        }
        val payment =
            paymentRepository.findById(resourceId)
                ?: throw DependencyUnavailableException(
                    "The stored idempotent payment cannot be read yet; retry shortly.",
                )

        replayedCounter.increment()
        return PaymentCreation(payment.toResponse(), replay = true)
    }

    private fun normalizeIdempotencyKey(rawKey: String): String {
        val key = rawKey.trim()
        if (key.length !in 8..128 || !IDEMPOTENCY_KEY_PATTERN.matches(key)) {
            throw InvalidRequestException(
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key must be 8-128 characters using letters, numbers, '.', '_', ':', or '-'.",
            )
        }
        return key
    }

    private fun PaymentRecord.toResponse(): PaymentResponse =
        PaymentResponse(
            id = id,
            idempotencyKey = idempotencyKey,
            sourceAccountId = sourceAccountId,
            destinationAccountId = destinationAccountId,
            amount = Money.fromMinor(amountMinor),
            amountMinor = amountMinor,
            currency = currency,
            reference = reference,
            status = status,
            createdAt = createdAt,
            settledAt = settledAt,
        )

    companion object {
        private val IDEMPOTENCY_KEY_PATTERN = Regex("^[A-Za-z0-9._:-]+$")
    }
}
