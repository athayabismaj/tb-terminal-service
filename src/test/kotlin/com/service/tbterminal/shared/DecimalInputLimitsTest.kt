package com.service.tbterminal.shared

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecimalInputLimitsTest {
    @Test
    fun `accepts normal money and quantity values`() {
        for (raw in listOf("0", "12.50", "-1000.00", "1.2345E+1", "0.30000000000000004", "999999999999999999", "1E+17")) {
            assertEquals(BigDecimal(raw), DecimalInputLimits.parse(raw), raw)
        }
    }

    @Test
    fun `rejects huge exponent scale or length`() {
        val tooLarge = listOf(
            "1E+3000000", "1E-3000000", "0E+3000000", "-1e2147483647", "1E+18", "1000000000000000000",
            "0.000000000000000000001", "1".repeat(DecimalInputLimits.MAX_LENGTH + 1)
        )
        for (raw in tooLarge) {
            val error = assertFailsWith<ValidationException>(raw) { DecimalInputLimits.parse(raw) }
            assertEquals(DecimalInputLimits.MESSAGE, error.message)
        }
    }

    @Test
    fun `boundaries are inclusive`() {
        DecimalInputLimits.parse("9".repeat(DecimalInputLimits.MAX_INTEGER_DIGITS))
        DecimalInputLimits.parse("0." + "0".repeat(DecimalInputLimits.MAX_SCALE - 1) + "1")
        DecimalInputLimits.parse("1".repeat(18) + "." + "1".repeat(20)) // 39 karakter
    }

    @Test
    fun `invalid format keeps number format error`() {
        assertFailsWith<NumberFormatException> { DecimalInputLimits.parse("dua belas") }
    }
}
