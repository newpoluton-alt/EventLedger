package dev.eventledger.payment

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

@Component
class PaymentRequestHasher {
    fun hash(
        request: CreatePaymentRequest,
        amountMinor: Long,
    ): String {
        val canonical =
            listOf(
                request.sourceAccountId.toString(),
                request.destinationAccountId.toString(),
                amountMinor.toString(),
                request.currency.uppercase(Locale.ROOT),
                request.reference?.trim().orEmpty(),
            ).joinToString(separator = "\u001F")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
