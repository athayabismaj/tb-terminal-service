package com.service.tbterminal.sales

import com.service.tbterminal.inventory.PriceTierRule
import com.service.tbterminal.inventory.ProductPriceTiersTable
import com.service.tbterminal.inventory.ProductsTable
import com.service.tbterminal.inventory.TieredPricingResolver
import com.service.tbterminal.receivable.CustomersTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import java.math.BigDecimal
import java.util.UUID

internal data class SalesTierPricingContext(
    val isContractorCustomer: Boolean,
    val tiersByProduct: Map<UUID, List<PriceTierRule>>,
) {
    fun resolveUnitPrice(productRow: ResultRow, qty: BigDecimal): BigDecimal {
        val productId = productRow[ProductsTable.id]
        return TieredPricingResolver.resolveUnitPrice(
            baseRetailPrice = productRow[ProductsTable.priceRetail],
            contractorPrice = productRow[ProductsTable.priceContractor],
            isContractorCustomer = isContractorCustomer,
            qty = qty,
            tiers = tiersByProduct[productId].orEmpty(),
        )
    }
}

internal fun loadSalesTierPricingContext(
    customerId: UUID?,
    productIds: List<UUID>,
): SalesTierPricingContext {
    val isContractor = customerId?.let { cid ->
        CustomersTable.select { CustomersTable.id eq cid }
            .singleOrNull()
            ?.get(CustomersTable.isContractor)
    } == true

    val distinctProductIds = productIds.distinct()
    val tiersByProduct = if (distinctProductIds.isEmpty()) {
        emptyMap()
    } else {
        ProductPriceTiersTable
            .select { ProductPriceTiersTable.productId inList distinctProductIds }
            .orderBy(ProductPriceTiersTable.minQty to SortOrder.ASC)
            .groupBy(
                keySelector = { it[ProductPriceTiersTable.productId] },
                valueTransform = {
                    PriceTierRule(
                        minQty = it[ProductPriceTiersTable.minQty],
                        unitPrice = it[ProductPriceTiersTable.unitPrice],
                    )
                },
            )
    }
    return SalesTierPricingContext(
        isContractorCustomer = isContractor,
        tiersByProduct = tiersByProduct,
    )
}
