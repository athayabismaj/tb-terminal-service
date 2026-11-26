package com.service.tbterminal.shared

import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SqlFragmentTest {
    @Test
    fun `values never enter sql text and args follow placeholder order`() {
        val id = UUID.randomUUID()
        val hostile = "x' OR '1'='1 \\'; --"
        val query = sql("SELECT * FROM t WHERE ") +
            SqlFragment.join(listOf(sqlParam("a = ?::uuid", id), sqlParam("b = ?", hostile)), " AND ") +
            " ORDER BY a"

        assertEquals("SELECT * FROM t WHERE a = ?::uuid AND b = ? ORDER BY a", query.sql)
        assertEquals(listOf<Any?>(id, hostile), query.args.map { it.second })
        assertIs<UUIDColumnType>(query.args[0].first)
        assertIs<TextColumnType>(query.args[1].first)
    }

    @Test
    fun `placeholder count must match argument count`() {
        assertFailsWith<IllegalArgumentException> { SqlFragment("a = ? AND b = ?", listOf(TextColumnType() to "x")) }
        assertFailsWith<IllegalArgumentException> { sql("a = ?") }
    }
}
