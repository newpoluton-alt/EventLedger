package dev.eventledger.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun `converts decimal amounts to exact minor units`() {
        assertEquals(1_234L, Money.toMinor(BigDecimal("12.34")))
        assertEquals(BigDecimal("12.34"), Money.fromMinor(1_234))
    }

    @Test
    fun `rejects floating point rounding and non-positive amounts`() {
        assertThrows(InvalidRequestException::class.java) {
            Money.toMinor(BigDecimal("1.001"))
        }
        assertThrows(InvalidRequestException::class.java) {
            Money.toMinor(BigDecimal.ZERO)
        }
    }
}
