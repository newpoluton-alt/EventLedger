package dev.eventledger.payment

import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val paymentService: PaymentService,
) {
    @PostMapping
    fun create(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreatePaymentRequest,
    ): ResponseEntity<PaymentResponse> {
        val result = paymentService.create(request, idempotencyKey)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/payments/${result.payment.id}"))
            .header("X-Idempotent-Replay", result.replay.toString())
            .cacheControl(CacheControl.noStore())
            .body(result.payment)
    }

    @GetMapping("/{paymentId}")
    fun get(
        @PathVariable paymentId: UUID,
    ): PaymentResponse = paymentService.get(paymentId)
}
