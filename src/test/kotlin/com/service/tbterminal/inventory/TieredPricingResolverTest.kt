package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TieredPricingResolverTest {

    @Test
    fun `validateAndSortTiers sorts ascending by minQty`() {
        val sorted = TieredPricingResolver.validateAndSortTiers(
            listOf(
                ProductPriceTierDto(minQty = BigDecimal("50"), unitPrice = BigDecimal("58000")),
                ProductPriceTierDto(minQty = BigDecimal("10"), unitPrice = BigDecimal("61000")),
            )
        )
        assertEquals(listOf(BigDecimal("10"), BigDecimal("50")), sorted.map { it.minQty })
    }

    @Test
    fun `validateAndSortTiers rejects duplicate minQty and excess count`() {
        assertThrows(ValidationException::class.java) {
            TieredPricingResolver.validateAndSortTiers(
                listOf(
                    ProductPriceTierDto(minQty = BigDecimal("10"), unitPrice = BigDecimal("60000")),
                    ProductPriceTierDto(minQty = BigDecimal("10.0"), unitPrice = BigDecimal("59000")),
                )
            )
        }
        val elevenTiers = (1..11).map {
            ProductPriceTierDto(minQty = BigDecimal(it), unitPrice = BigDecimal(10000 - it * 100))
        }
        assertThrows(ValidationException::class.java) {
            TieredPricingResolver.validateAndSortTiers(elevenTiers)
        }
    }

    @Test
    fun `resolveUnitPrice picks highest matching tier and falls back to retail`() {
        val tiers = listOf(
            PriceTierRule(minQty = BigDecimal("10"), unitPrice = BigDecimal("61000")),
            PriceTierRule(minQty = BigDecimal("50"), unitPrice = BigDecimal("58000")),
        )
        assertEquals(
            BigDecimal("63000"),
            TieredPricingResolver.resolveUnitPrice(
                baseRetailPrice = BigDecimal("63000"),
                contractorPrice = BigDecimal("57000"),
                isContractorCustomer = false,
                qty = BigDecimal("9"),
                tiers = tiers,
            )
        )
        assertEquals(
            BigDecimal("61000"),
            TieredPricingResolver.resolveUnitPrice(
                baseRetailPrice = BigDecimal("63000"),
                contractorPrice = BigDecimal("57000"),
                isContractorCustomer = false,
                qty = BigDecimal("10"),
                tiers = tiers,
            )
        )
        assertEquals(
            BigDecimal("58000"),
            TieredPricingResolver.resolveUnitPrice(
                baseRetailPrice = BigDecimal("63000"),
                contractorPrice = BigDecimal("57000"),
                isContractorCustomer = false,
                qty = BigDecimal("75"),
                tiers = tiers,
            )
        )
    }

    @Test
    fun `resolveUnitPrice preserves positive contractor price for contractor customer`() {
        val tiers = listOf(
            PriceTierRule(minQty = BigDecimal("10"), unitPrice = BigDecimal("61000")),
        )
        assertEquals(
            BigDecimal("57000"),
            TieredPricingResolver.resolveUnitPrice(
                baseRetailPrice = BigDecimal("63000"),
                contractorPrice = BigDecimal("57000"),
                isContractorCustomer = true,
                qty = BigDecimal("20"),
                tiers = tiers,
            )
        )
        // If contractorPrice is 0, tier still applies
        assertEquals(
            BigDecimal("61000"),
            TieredPricingResolver.resolveUnitPrice(
                baseRetailPrice = BigDecimal("63000"),
                contractorPrice = BigDecimal.ZERO,
                isContractorCustomer = true,
                qty = BigDecimal("20"),
                tiers = tiers,
            )
        )
    }
}
