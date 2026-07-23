package dev.eventledger.payment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class PaymentRequestHasherTest {
    private val hasher = PaymentRequestHasher()
    private val source = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val destination = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `hash is canonical for currency and reference whitespace`() {
        val first =
            request(
                amount = "10.00",
                currency = "eur",
                reference = " invoice-42 ",
            )
        val second =
            request(
                amount = "10.0",
                currency = "EUR",
                reference = "invoice-42",
            )

        assertEquals(hasher.hash(first, 1_000), hasher.hash(second, 1_000))
    }

    @Test
    fun `hash changes when a business field changes`() {
        val first = request(amount = "10.00")
        val second = request(amount = "10.01")

        assertNotEquals(hasher.hash(first, 1_000), hasher.hash(second, 1_001))
    }

    private fun request(
        amount: String,
        currency: String = "EUR",
        reference: String? = "invoice-42",
    ): CreatePaymentRequest =
        CreatePaymentRequest(
            sourceAccountId = source,
            destinationAccountId = destination,
            amount = BigDecimal(amount),
            currency = currency,
            reference = reference,
        )
}
