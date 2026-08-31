package com.service.tbterminal.inventory

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

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

    @Test
    fun `secondary unit conversion requires a distinct unit and positive factor`() {
        val baseUnitId = UUID.randomUUID()
        val secondaryUnitId = UUID.randomUUID()

        assertNull(validateUnitConversion(baseUnitId, null, null))
        assertNull(validateUnitConversion(baseUnitId, secondaryUnitId, BigDecimal("12")))
        assertTrue(validateUnitConversion(baseUnitId, baseUnitId, BigDecimal("12")) != null)
        assertTrue(validateUnitConversion(baseUnitId, secondaryUnitId, BigDecimal.ZERO) != null)
        assertTrue(validateUnitConversion(baseUnitId, secondaryUnitId, BigDecimal("1.00001")) != null)
    }

    @Test
    fun `secondary unit factor accepts comma and rejects invalid text`() {
        assertEquals(BigDecimal("2.5"), parseUnitConversionFactor("2,5"))
        assertNull(parseUnitConversionFactor("abc"))
        assertNull(parseUnitConversionFactor(" "))
    }
}
