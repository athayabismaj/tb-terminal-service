package com.service.tbterminal.analytics

import java.io.Writer

object CsvSupport {
    fun encodeCell(value: String?): String {
        val raw = value.orEmpty()
        val firstMeaningful = raw.trimStart().firstOrNull()
        val safe = if (firstMeaningful?.let { it == '=' || it == '+' || it == '-' || it == '@' || it == '\t' || it == '\r' } == true) {
            "'$raw"
        } else {
            raw
        }
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    fun writeRow(writer: Writer, values: List<String?>) {
        writer.append(values.joinToString(",", transform = ::encodeCell)).append("\r\n")
    }
}
