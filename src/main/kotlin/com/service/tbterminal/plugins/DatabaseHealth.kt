package com.service.tbterminal.plugins

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DatabaseHealth {
    @Volatile private var dataSource: HikariDataSource? = null

    fun attach(source: HikariDataSource) { dataSource = source }
    fun detach(source: HikariDataSource) { if (dataSource === source) dataSource = null }

    fun evictConnectionsAfterRestore() {
        val source = dataSource ?: error("Datasource belum tersedia setelah restore")
        source.hikariPoolMXBean.softEvictConnections()
    }

    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        val source = dataSource ?: return@withContext false
        runCatching {
            source.connection.use { connection ->
                connection.isValid(2) && connection.prepareStatement("SELECT 1").use { statement ->
                    statement.queryTimeout = 2
                    statement.executeQuery().use { it.next() && it.getInt(1) == 1 }
                }
            }
        }.getOrDefault(false)
    }
}
