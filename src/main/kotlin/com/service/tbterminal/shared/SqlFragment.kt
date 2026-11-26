package com.service.tbterminal.shared

import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.sql.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.sql.statements.StatementType
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Potongan SQL dengan placeholder `?` beserta nilainya, dalam urutan kemunculan.
 *
 * Nilai dari luar tidak pernah disisipkan ke teks SQL. Potongan digabung dengan `+`,
 * sehingga urutan argumen selalu mengikuti urutan placeholder di teks gabungan.
 */
class SqlFragment(val sql: String, val args: List<Pair<IColumnType, Any?>> = emptyList()) {
    init {
        require(sql.count { it == '?' } == args.size) { "Jumlah placeholder dan argumen SQL tidak sama" }
    }

    operator fun plus(other: SqlFragment): SqlFragment = SqlFragment(sql + other.sql, args + other.args)
    operator fun plus(text: String): SqlFragment = this + SqlFragment(text)

    override fun toString(): String = sql

    companion object {
        fun join(fragments: List<SqlFragment>, separator: String): SqlFragment =
            SqlFragment(fragments.joinToString(separator) { it.sql }, fragments.flatMap { it.args })
    }
}

fun sql(text: String): SqlFragment = SqlFragment(text)

fun sqlParam(text: String, value: UUID?): SqlFragment = SqlFragment(text, listOf(UUIDColumnType() to value))
fun sqlParam(text: String, value: String?): SqlFragment = SqlFragment(text, listOf(TextColumnType() to value))
fun sqlParam(text: String, value: Int): SqlFragment = SqlFragment(text, listOf(IntegerColumnType() to value))
fun sqlParam(text: String, value: Long?): SqlFragment = SqlFragment(text, listOf(LongColumnType() to value))
fun sqlParam(text: String, value: LocalDate): SqlFragment = SqlFragment(text, listOf(JavaLocalDateColumnType() to value))
fun sqlParam(text: String, value: OffsetDateTime?): SqlFragment =
    SqlFragment(text, listOf(JavaOffsetDateTimeColumnType() to value))

/** Menjalankan SELECT dari [SqlFragment] dan memetakan setiap baris. */
fun <T> Transaction.queryList(query: SqlFragment, mapper: (ResultSet) -> T): List<T> =
    exec(query.sql.trimIndent(), query.args, StatementType.SELECT) { rs ->
        buildList { while (rs.next()) add(mapper(rs)) }
    } ?: emptyList()

/** Menjalankan pernyataan non-SELECT dari [SqlFragment]. */
fun Transaction.execute(statement: SqlFragment) {
    exec(statement.sql.trimIndent(), statement.args)
}
