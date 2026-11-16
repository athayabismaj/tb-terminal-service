package com.service.tbterminal.sales

import com.service.tbterminal.system.CashRoundingRule
import com.service.tbterminal.system.TaxMode
import java.math.BigDecimal
import java.math.RoundingMode

data class StoreTaxRoundingConfig(
    val taxEnabled: Boolean = false,
    val taxRate: BigDecimal = TaxAndRoundingCalculator.DEFAULT_TAX_RATE,
    val taxMode: TaxMode = TaxMode.EXCLUDE,
    val taxLabel: String = TaxAndRoundingCalculator.DEFAULT_TAX_LABEL,
    val cashRoundingUnit: Int = 0,
    val cashRoundingRule: CashRoundingRule = CashRoundingRule.NEAREST
)

data class TaxCalculation(
    val taxEnabled: Boolean,
    val taxMode: TaxMode?,
    val taxRate: BigDecimal,
    val taxLabel: String?,
    val taxableBase: BigDecimal,
    val taxAmount: BigDecimal,
    val totalWithTax: BigDecimal
)

data class CashRoundingCalculation(
    val roundingAmount: BigDecimal,
    val finalTotal: BigDecimal
)

object TaxAndRoundingCalculator {

    fun calculateTax(
        netGoodsTotal: BigDecimal,
        config: StoreTaxRoundingConfig
    ): TaxCalculation {
        val net = netGoodsTotal.money()
        if (!config.taxEnabled) {
            return TaxCalculation(
                taxEnabled = false,
                taxMode = null,
                taxRate = BigDecimal.ZERO.setScale(MONEY_SCALE),
                taxLabel = null,
                taxableBase = BigDecimal.ZERO.setScale(MONEY_SCALE),
                taxAmount = BigDecimal.ZERO.setScale(MONEY_SCALE),
                totalWithTax = net
            )
        }

        val rate = config.taxRate.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val label = config.taxLabel.trim().ifBlank { DEFAULT_TAX_LABEL }

        return when (config.taxMode) {
            TaxMode.EXCLUDE -> {
                val taxAmount = net.multiply(rate).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP)
                TaxCalculation(
                    taxEnabled = true,
                    taxMode = TaxMode.EXCLUDE,
                    taxRate = rate,
                    taxLabel = label,
                    taxableBase = net,
                    taxAmount = taxAmount,
                    totalWithTax = net.add(taxAmount).money()
                )
            }
            TaxMode.INCLUDE -> {
                val divisor = BigDecimal.ONE.add(rate.divide(HUNDRED, 6, RoundingMode.HALF_UP))
                val dpp = if (divisor.compareTo(BigDecimal.ZERO) == 0) net
                else net.divide(divisor, MONEY_SCALE, RoundingMode.HALF_UP)
                val taxAmount = net.subtract(dpp).money()
                TaxCalculation(
                    taxEnabled = true,
                    taxMode = TaxMode.INCLUDE,
                    taxRate = rate,
                    taxLabel = label,
                    taxableBase = dpp,
                    taxAmount = taxAmount,
                    totalWithTax = net
                )
            }
        }
    }

    fun calculateCashRounding(
        totalBeforeRounding: BigDecimal,
        hasCashPayment: Boolean,
        unit: Int,
        rule: CashRoundingRule
    ): CashRoundingCalculation {
        val base = totalBeforeRounding.money()
        if (!hasCashPayment || unit !in ALLOWED_ROUNDING_UNITS || unit == 0 || base.compareTo(BigDecimal.ZERO) <= 0) {
            return CashRoundingCalculation(
                roundingAmount = BigDecimal.ZERO.setScale(MONEY_SCALE),
                finalTotal = base
            )
        }

        val step = BigDecimal(unit)
        val roundingMode = when (rule) {
            CashRoundingRule.NEAREST -> RoundingMode.HALF_UP
            CashRoundingRule.DOWN -> RoundingMode.FLOOR
            CashRoundingRule.UP -> RoundingMode.CEILING
        }
        val rounded = base.divide(step, 0, roundingMode)
            .multiply(step)
            .coerceAtLeast(BigDecimal.ZERO)
            .money()

        return CashRoundingCalculation(
            roundingAmount = rounded.subtract(base).money(),
            finalTotal = rounded
        )
    }

    private fun BigDecimal.money(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.HALF_UP)

    private const val MONEY_SCALE = 2
    private val HUNDRED = BigDecimal("100")
    val DEFAULT_TAX_RATE: BigDecimal = BigDecimal("11.00")
    const val DEFAULT_TAX_LABEL = "PPN"
    val ALLOWED_ROUNDING_UNITS = setOf(0, 100, 500, 1000)
}
