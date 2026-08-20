package com.service.tbterminal.analytics

import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvSupportTest {
    @Test
    fun `formula-like cells are neutralized and quoted`() {
        assertEquals("\"'=SUM(A1:A2)\"", CsvSupport.encodeCell("=SUM(A1:A2)"))
        assertEquals("\"'+628123\"", CsvSupport.encodeCell("+628123"))
        assertEquals("\"'-10\"", CsvSupport.encodeCell("-10"))
        assertEquals("\"'@command\"", CsvSupport.encodeCell("@command"))
        assertEquals("\"'  =hidden\"", CsvSupport.encodeCell("  =hidden"))
    }

    @Test
    fun `quotes and newlines remain valid csv`() {
        val writer = StringWriter()
        CsvSupport.writeRow(writer, listOf("Produk \"A\"", "baris 1\nbaris 2", null))
        assertEquals("\"Produk \"\"A\"\"\",\"baris 1\nbaris 2\",\"\"\r\n", writer.toString())
    }
}
