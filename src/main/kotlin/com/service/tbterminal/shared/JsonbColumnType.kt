package com.service.tbterminal.shared

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.postgresql.util.PGobject

class JsonbColumnType : ColumnType() {
    override fun sqlType(): String = "JSONB"

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = value as String?
        stmt[index] = obj
    }

    override fun valueFromDB(value: Any): Any {
        if (value !is PGobject) {
            return value.toString()
        }
        return value.value ?: "{}"
    }

    override fun valueToString(value: Any?): String = when (value) {
        is Iterable<*> -> nonNullValueToString(value)
        else -> super.valueToString(value)
    }
}

fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())
