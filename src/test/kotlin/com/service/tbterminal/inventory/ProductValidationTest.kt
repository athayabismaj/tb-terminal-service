package com.service.tbterminal.inventory

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductValidationTest {
    @Test
    fun `sku normalization and format are deterministic`() {
        assertEquals("SKU-01", normalizeSku(" sku-01 "))
        assertNull(validateSku("SKU-01"))
        assertTrue(validateSku("SKU 01") != null)
    }

    @Test
    fun `negative and over precision product values are rejected`() {
        val errors = validateProductValues(
            "Produk", BigDecimal("-1"), BigDecimal("1.001"), BigDecimal("2"),
            BigDecimal.ZERO, BigDecimal("0.001")
        )
        assertEquals(3, errors.size)
    }
}
