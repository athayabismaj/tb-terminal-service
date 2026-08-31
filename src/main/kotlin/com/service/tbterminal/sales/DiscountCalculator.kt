package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

@Serializable
enum class DiscountType {
    PERCENTAGE,
    FIXED_AMOUNT
}

@Serializable
data class DiscountRequest(
    val type: DiscountType,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val value: BigDecimal
)

data class DiscountItemInput(
    val reference: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val discount: DiscountRequest?,
    val legacyPerUnitDiscount: BigDecimal = BigDecimal.ZERO
)

data class DiscountLineCalculation(
    val reference: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val grossLineTotal: BigDecimal,
    val discountType: DiscountType?,
    val discountValue: BigDecimal,
    val discountAmount: BigDecimal,
    val netLineTotal: BigDecimal
)

data class DiscountCalculation(
    val items: List<DiscountLineCalculation>,
    val grossSubtotal: BigDecimal,
    val itemDiscountTotal: BigDecimal,
    val subtotalAfterItemDiscount: BigDecimal,
    val transactionDiscountType: DiscountType?,
    val transactionDiscountValue: BigDecimal,
    val transactionDiscountAmount: BigDecimal,
    val totalDiscountAmount: BigDecimal,
    val effectiveDiscountPercent: BigDecimal,
    val netTotal: BigDecimal,
    val fingerprint: String
)

object DiscountCalculator {
    fun calculate(
        items: List<DiscountItemInput>,
        transactionDiscount: DiscountRequest?
    ): DiscountCalculation {
        if (items.isEmpty()) throw ValidationException("Keranjang diskon tidak boleh kosong")
        val lines = items.map(::calculateLine)
        val grossSubtotal = lines.sumMoney(DiscountLineCalculation::grossLineTotal)
        val itemDiscountTotal = lines.sumMoney(DiscountLineCalculation::discountAmount)
        val subtotalAfterItem = grossSubtotal.subtract(itemDiscountTotal).money()
        val transactionAmount = calculateAmount(subtotalAfterItem, transactionDiscount, "transaksi")
        val netTotal = subtotalAfterItem.subtract(transactionAmount).money()
        if (netTotal < BigDecimal.ZERO) throw ValidationException("Diskon transaksi membuat total menjadi negatif")
        val totalDiscount = itemDiscountTotal.add(transactionAmount).money()
        val effectivePercent = effectivePercent(grossSubtotal, totalDiscount)
        val transactionValue = normalizedValue(transactionDiscount)
        val fingerprint = fingerprint(
            lines, grossSubtotal, itemDiscountTotal, transactionDiscount?.type,
            transactionValue, transactionAmount, netTotal
        )
        return DiscountCalculation(
            items = lines,
            grossSubtotal = grossSubtotal,
            itemDiscountTotal = itemDiscountTotal,
            subtotalAfterItemDiscount = subtotalAfterItem,
            transactionDiscountType = transactionDiscount?.type,
            transactionDiscountValue = transactionValue,
            transactionDiscountAmount = transactionAmount,
            totalDiscountAmount = totalDiscount,
            effectiveDiscountPercent = effectivePercent,
            netTotal = netTotal,
            fingerprint = fingerprint
        )
    }

    fun effectivePercent(grossSubtotal: BigDecimal, totalDiscountAmount: BigDecimal): BigDecimal =
        if (grossSubtotal.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO.setScale(PERCENT_SCALE)
        else totalDiscountAmount.multiply(HUNDRED)
            .divide(grossSubtotal, PERCENT_SCALE, RoundingMode.HALF_UP)

    private fun calculateLine(input: DiscountItemInput): DiscountLineCalculation {
        if (input.quantity <= BigDecimal.ZERO || input.quantity.scale() > 2) {
            throw ValidationException("Quantity harus lebih dari nol dan maksimal 2 angka desimal")
        }
        if (input.unitPrice < BigDecimal.ZERO || input.unitPrice.scale() > MONEY_SCALE) {
            throw ValidationException("Harga jual server tidak valid")
        }
        if (input.legacyPerUnitDiscount < BigDecimal.ZERO || input.legacyPerUnitDiscount.scale() > MONEY_SCALE) {
            throw ValidationException("Diskon legacy tidak valid")
        }
        if (input.discount != null && input.legacyPerUnitDiscount.compareTo(BigDecimal.ZERO) != 0) {
            throw ValidationException("Gunakan discountRequest atau field discount legacy, bukan keduanya")
        }
        val gross = input.unitPrice.multiply(input.quantity).money()
        val effectiveRequest = input.discount ?: input.legacyPerUnitDiscount
            .takeIf { it.compareTo(BigDecimal.ZERO) != 0 }
            ?.let { DiscountRequest(DiscountType.FIXED_AMOUNT, it.multiply(input.quantity).money()) }
        val discountAmount = calculateAmount(gross, effectiveRequest, "item ${input.reference}")
        return DiscountLineCalculation(
            reference = input.reference,
            quantity = input.quantity,
            unitPrice = input.unitPrice.money(),
            grossLineTotal = gross,
            discountType = effectiveRequest?.type,
            discountValue = normalizedValue(effectiveRequest),
            discountAmount = discountAmount,
            netLineTotal = gross.subtract(discountAmount).money()
        )
    }

    private fun calculateAmount(base: BigDecimal, request: DiscountRequest?, label: String): BigDecimal {
        if (request == null) return BigDecimal.ZERO.setScale(MONEY_SCALE)
        if (request.value < BigDecimal.ZERO) throw ValidationException("Diskon $label tidak boleh negatif")
        return when (request.type) {
            DiscountType.PERCENTAGE -> {
                if (request.value.scale() > PERCENT_INPUT_SCALE || request.value > HUNDRED) {
                    throw ValidationException("Persentase diskon $label harus 0-100 dan maksimal 2 angka desimal")
                }
                base.multiply(request.value).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP)
            }
            DiscountType.FIXED_AMOUNT -> {
                if (request.value.scale() > MONEY_SCALE) {
                    throw ValidationException("Diskon tetap $label maksimal 2 angka desimal")
                }
                request.value.money()
            }
        }.also {
            if (it > base) throw ValidationException("Diskon $label tidak boleh melebihi nilai yang didiskon")
        }
    }

    private fun normalizedValue(request: DiscountRequest?): BigDecimal = when (request?.type) {
        DiscountType.PERCENTAGE -> request.value.setScale(PERCENT_INPUT_SCALE, RoundingMode.HALF_UP)
        DiscountType.FIXED_AMOUNT -> request.value.money()
        null -> BigDecimal.ZERO.setScale(MONEY_SCALE)
    }

    private fun fingerprint(
        lines: List<DiscountLineCalculation>,
        gross: BigDecimal,
        itemDiscount: BigDecimal,
        transactionType: DiscountType?,
        transactionValue: BigDecimal,
        transactionAmount: BigDecimal,
        net: BigDecimal
    ): String {
        val canonical = buildString {
            lines.sortedBy { it.reference }.forEach { line ->
                append(line.reference).append(':')
                append(line.quantity.stripTrailingZeros().toPlainString()).append(':')
                append(line.unitPrice.toPlainString()).append(':')
                append(line.discountType?.name.orEmpty()).append(':')
                append(line.discountValue.toPlainString()).append(':')
                append(line.discountAmount.toPlainString()).append(';')
            }
            append('|').append(gross.toPlainString())
            append('|').append(itemDiscount.toPlainString())
            append('|').append(transactionType?.name.orEmpty())
            append('|').append(transactionValue.toPlainString())
            append('|').append(transactionAmount.toPlainString())
            append('|').append(net.toPlainString())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun List<DiscountLineCalculation>.sumMoney(
        selector: (DiscountLineCalculation) -> BigDecimal
    ): BigDecimal = fold(BigDecimal.ZERO) { total, line -> total.add(selector(line)) }.money()

    private fun BigDecimal.money(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.HALF_UP)

    private const val MONEY_SCALE = 2
    private const val PERCENT_INPUT_SCALE = 2
    private const val PERCENT_SCALE = 4
    private val HUNDRED = BigDecimal("100")
}
