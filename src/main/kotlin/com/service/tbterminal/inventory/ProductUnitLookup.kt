package com.service.tbterminal.inventory

import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import java.math.BigDecimal
import java.util.UUID

internal data class ProductUnits(
    val baseUnitId: UUID,
    val secondary: SecondaryUnit?
)

internal data class SecondaryUnit(val unitId: UUID, val unitName: String, val factor: BigDecimal)

/**
 * Satuan beberapa produk dalam satu query. Hanya konversi yang bermuara ke satuan dasar yang dipakai;
 * bila lebih dari satu, yang paling lama dibuat. Wajib dipanggil di dalam transaksi database.
 */
internal fun loadProductUnits(productIds: List<UUID>): Map<UUID, ProductUnits> {
    if (productIds.isEmpty()) return emptyMap()
    return ProductsTable
        .join(UnitConversionsTable, JoinType.LEFT, additionalConstraint = {
            (UnitConversionsTable.productId eq ProductsTable.id) and
                (UnitConversionsTable.toUnitId eq ProductsTable.baseUnitId)
        })
        .join(UnitsTable, JoinType.LEFT, additionalConstraint = {
            UnitsTable.id eq UnitConversionsTable.fromUnitId
        })
        .slice(
            ProductsTable.id, ProductsTable.baseUnitId,
            UnitConversionsTable.fromUnitId, UnitConversionsTable.factor, UnitsTable.name
        )
        .select { ProductsTable.id inList productIds }
        .orderBy(UnitConversionsTable.createdAt to SortOrder.ASC, UnitConversionsTable.id to SortOrder.ASC)
        .groupBy { it[ProductsTable.id] }
        .mapValues { (_, rows) ->
            val row = rows.first()
            ProductUnits(
                baseUnitId = row[ProductsTable.baseUnitId],
                secondary = row.getOrNull(UnitConversionsTable.fromUnitId)?.let { unitId ->
                    SecondaryUnit(
                        unitId = unitId,
                        unitName = row[UnitsTable.name],
                        factor = row[UnitConversionsTable.factor].stripTrailingZeros()
                    )
                }
            )
        }
}
