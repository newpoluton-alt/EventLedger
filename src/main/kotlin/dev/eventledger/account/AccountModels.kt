package dev.eventledger.account

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class AccountType {
    CUSTOMER,
    MERCHANT,
    SYSTEM,
}

enum class AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED,
}

data class AccountRecord(
    val id: UUID,
    val ownerReference: String,
    val accountType: AccountType,
    val currency: String,
    val status: AccountStatus,
    val allowNegative: Boolean,
    val balanceMinor: Long,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CreateAccountRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val ownerReference: String,
    val accountType: AccountType = AccountType.CUSTOMER,
    @field:Pattern(regexp = "^(?i:EUR)$", message = "EventLedger currently supports EUR accounts only")
    val currency: String,
)

data class AccountResponse(
    val id: UUID,
    val ownerReference: String,
    val accountType: AccountType,
    val currency: String,
    val status: AccountStatus,
    val createdAt: Instant,
)

data class AccountBalanceResponse(
    val accountId: UUID,
    val currency: String,
    val balance: BigDecimal,
    val balanceMinor: Long,
    val version: Long,
    val updatedAt: Instant,
)
