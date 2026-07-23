package dev.eventledger.payment

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class PaymentStatus {
    POSTED,
    SETTLED,
    NEEDS_ATTENTION,
}

data class CreatePaymentRequest(
    val sourceAccountId: UUID,
    val destinationAccountId: UUID,
    @field:DecimalMin(value = "0.01")
    val amount: BigDecimal,
    @field:NotBlank
    @field:Pattern(regexp = "^(?i:EUR)$", message = "EventLedger currently supports EUR payments only")
    val currency: String,
    @field:Size(max = 140)
    val reference: String? = null,
)

data class PaymentRecord(
    val id: UUID,
    val idempotencyKey: String,
    val sourceAccountId: UUID,
    val destinationAccountId: UUID,
    val amountMinor: Long,
    val currency: String,
    val reference: String?,
    val status: PaymentStatus,
    val createdAt: Instant,
    val settledAt: Instant?,
    val updatedAt: Instant,
)

data class PaymentResponse(
    val id: UUID,
    val idempotencyKey: String,
    val sourceAccountId: UUID,
    val destinationAccountId: UUID,
    val amount: BigDecimal,
    val amountMinor: Long,
    val currency: String,
    val reference: String?,
    val status: PaymentStatus,
    val createdAt: Instant,
    val settledAt: Instant?,
)

data class PaymentCreation(
    val payment: PaymentResponse,
    val replay: Boolean,
)
