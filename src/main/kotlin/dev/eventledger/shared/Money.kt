package dev.eventledger.shared

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    private const val FRACTION_DIGITS = 2

    fun toMinor(amount: BigDecimal): Long {
        if (amount <= BigDecimal.ZERO) {
            throw InvalidRequestException("INVALID_AMOUNT", "Amount must be greater than zero.")
        }

        return try {
            amount
                .setScale(FRACTION_DIGITS, RoundingMode.UNNECESSARY)
                .movePointRight(FRACTION_DIGITS)
                .longValueExact()
        } catch (_: ArithmeticException) {
            throw InvalidRequestException(
                "INVALID_AMOUNT",
                "Amount must fit in a signed 64-bit minor-unit value and have at most two decimal places.",
            )
        }
    }

    fun fromMinor(amountMinor: Long): BigDecimal = BigDecimal.valueOf(amountMinor, FRACTION_DIGITS)
}
