package com.service.tbterminal.inventory

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductCsvParserTest {
    private val categories = mapOf("semen" to "category-id")
    private val units = mapOf("sak" to "unit-id")
    private val header = PRODUCT_CSV_HEADERS.joinToString(",")

    @Test
    fun `valid csv including quoted note is accepted`() {
        val csv = "$header\nsmn-001,Semen A,Semen,Sak,50000,60000,58000,5,10,2026-07-31,\"Gudang awal, rak A\""
        val result = previewProductCsv(csv, categories, units, emptySet(), LocalDate.of(2026, 8, 1))
        assertEquals(1, result.validRows)
        assertEquals(0, result.invalidRows)
        assertEquals("SMN-001", result.rows.single().sku)
        assertEquals("Gudang awal, rak A", result.rows.single().openingNote)
    }

    @Test
    fun `duplicate sku is case insensitive and reported before commit`() {
        val csv = "$header\nabc-1,A,Semen,Sak,1,2,2,0,0,,\nABC-1,B,Semen,Sak,1,2,2,0,0,,"
        val result = previewProductCsv(csv, categories, units, emptySet(), LocalDate.of(2026, 8, 1))
        assertEquals(1, result.invalidRows)
        assertTrue(result.rows.last().errors.any { it.contains("duplikat") })
    }

    @Test
    fun `invalid references values and future opening date are reported`() {
        val csv = "$header\nSKU1,A,Tidak Ada,Pcs,-1,2,2,-1,5,2026-08-02,"
        val result = previewProductCsv(csv, categories, units, setOf("SKU1"), LocalDate.of(2026, 8, 1))
        val errors = result.rows.single().errors.joinToString("|")
        assertTrue(errors.contains("SKU sudah digunakan"))
        assertTrue(errors.contains("Kategori tidak ditemukan"))
        assertTrue(errors.contains("Satuan tidak ditemukan"))
        assertTrue(errors.contains("tidak boleh negatif"))
        assertTrue(errors.contains("masa depan"))
        assertTrue(errors.contains("Catatan saldo awal wajib"))
    }
}
