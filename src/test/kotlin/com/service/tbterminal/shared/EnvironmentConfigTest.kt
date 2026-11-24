package com.service.tbterminal.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentConfigTest {
    @Test
    fun `umur sesi offline default 72 jam dan dibatasi 1 sampai 168`() {
        val expected = mapOf(
            "" to 72L, "abc" to 72L, "12.5" to 72L,
            "1" to 1L, " 24 " to 24L, "168" to 168L,
            "0" to 1L, "-5" to 1L, "169" to 168L
        )
        expected.forEach { (raw, hours) ->
            assertEquals(hours, EnvironmentConfig.parseOfflineMaxSessionAgeHours(raw), "input '$raw'")
        }
    }
}
