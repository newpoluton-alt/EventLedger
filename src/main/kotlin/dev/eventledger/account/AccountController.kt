package dev.eventledger.account

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(
    private val commandService: AccountCommandService,
    private val queryService: AccountQueryService,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateAccountRequest,
    ): ResponseEntity<AccountResponse> {
        val account = commandService.create(request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/accounts/${account.id}"))
            .body(account)
    }

    @GetMapping("/{accountId}")
    fun get(
        @PathVariable accountId: UUID,
    ): AccountResponse = queryService.get(accountId)

    @GetMapping("/{accountId}/balance")
    fun balance(
        @PathVariable accountId: UUID,
    ): AccountBalanceResponse = queryService.getBalance(accountId)
}
