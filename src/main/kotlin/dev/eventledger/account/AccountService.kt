package dev.eventledger.account

import dev.eventledger.shared.ConflictException
import dev.eventledger.shared.InvalidRequestException
import dev.eventledger.shared.Money
import dev.eventledger.shared.NotFoundException
import org.springframework.cache.annotation.Cacheable
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Locale
import java.util.UUID

@Service
class AccountCommandService(
    private val accountRepository: AccountRepository,
    private val clock: Clock,
) {
    @Transactional
    fun create(request: CreateAccountRequest): AccountResponse {
        if (request.accountType == AccountType.SYSTEM) {
            throw InvalidRequestException(
                "INVALID_ACCOUNT_TYPE",
                "System accounts cannot be created through the public API.",
            )
        }

        val id = UUID.randomUUID()
        val now = clock.instant()
        val currency = request.currency.uppercase(Locale.ROOT)

        try {
            accountRepository.insert(
                id = id,
                ownerReference = request.ownerReference.trim(),
                type = request.accountType,
                currency = currency,
                now = now,
            )
        } catch (_: DuplicateKeyException) {
            throw ConflictException(
                "ACCOUNT_ALREADY_EXISTS",
                "An account with that owner reference, type, and currency already exists.",
            )
        }

        return AccountResponse(
            id = id,
            ownerReference = request.ownerReference.trim(),
            accountType = request.accountType,
            currency = currency,
            status = AccountStatus.ACTIVE,
            createdAt = now,
        )
    }
}

@Service
class AccountQueryService(
    private val accountRepository: AccountRepository,
) {
    @Cacheable(cacheNames = ["account-metadata"], key = "#accountId")
    fun get(accountId: UUID): AccountResponse {
        val account =
            accountRepository.findById(accountId)
                ?: throw NotFoundException("ACCOUNT_NOT_FOUND", "Account '$accountId' does not exist.")
        return AccountResponse(
            id = account.id,
            ownerReference = account.ownerReference,
            accountType = account.accountType,
            currency = account.currency,
            status = account.status,
            createdAt = account.createdAt,
        )
    }

    fun getBalance(accountId: UUID): AccountBalanceResponse {
        val account =
            accountRepository.findById(accountId)
                ?: throw NotFoundException("ACCOUNT_NOT_FOUND", "Account '$accountId' does not exist.")
        return AccountBalanceResponse(
            accountId = account.id,
            currency = account.currency,
            balance = Money.fromMinor(account.balanceMinor),
            balanceMinor = account.balanceMinor,
            version = account.version,
            updatedAt = account.updatedAt,
        )
    }
}
