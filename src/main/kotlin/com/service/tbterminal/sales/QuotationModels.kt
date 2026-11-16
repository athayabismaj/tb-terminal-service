package com.service.tbterminal.sales

import com.service.tbterminal.shared.BigDecimalSerializer
import com.service.tbterminal.system.TaxMode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.math.BigDecimal

@Serializable
enum class QuotationStatus {
    DRAFT,
    SENT,
    CONVERTED,
    CANCELLED
}

object QuotationsTable : Table("sales.quotations") {
    val id = uuid("id").databaseGenerated()
    val quotationNumber = varchar("quotation_number", 50)
    val userId = uuid("user_id")
    val customerId = uuid("customer_id").nullable()
    val customerNameSnapshot = varchar("customer_name_snapshot", 150).nullable()
    val status = varchar("status", 20).default(QuotationStatus.DRAFT.name)
    val validUntil = timestampWithTimeZone("valid_until").nullable()
    val notes = text("notes").nullable()
    val grossSubtotal = decimal("gross_subtotal", 15, 2)
    val itemDiscountTotal = decimal("item_discount_total", 15, 2)
    val transactionDiscountType = enumerationByName<DiscountType>("transaction_discount_type", 20).nullable()
    val transactionDiscountValue = decimal("transaction_discount_value", 15, 2)
    val transactionDiscountAmount = decimal("transaction_discount_amount", 15, 2)
    val totalDiscountAmount = decimal("total_discount_amount", 15, 2)
    val taxEnabled = bool("tax_enabled").default(false)
    val taxMode = enumerationByName<TaxMode>("tax_mode", 20).nullable()
    val taxRate = decimal("tax_rate", 5, 2).default(BigDecimal.ZERO)
    val taxLabel = varchar("tax_label", 50).nullable()
    val taxableBase = decimal("taxable_base", 15, 2).default(BigDecimal.ZERO)
    val taxAmount = decimal("tax_amount", 15, 2).default(BigDecimal.ZERO)
    val total = decimal("total", 15, 2)
    val convertedTransactionId = uuid("converted_transaction_id").nullable()
    val convertedAt = timestampWithTimeZone("converted_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object QuotationItemsTable : Table("sales.quotation_items") {
    val id = uuid("id").databaseGenerated()
    val quotationId = uuid("quotation_id").references(QuotationsTable.id)
    val productId = uuid("product_id")
    val unitId = uuid("unit_id")
    val productNameSnapshot = varchar("product_name_snapshot", 200)
    val unitNameSnapshot = varchar("unit_name_snapshot", 50).nullable()
    val quantity = decimal("quantity", 10, 2)
    val unitPrice = decimal("unit_price", 15, 2)
    val discountType = enumerationByName<DiscountType>("discount_type", 20).nullable()
    val discountValue = decimal("discount_value", 15, 2)
    val discountAmount = decimal("discount_amount", 15, 2)
    val subtotal = decimal("subtotal", 15, 2)
    val note = text("note").nullable()

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class CreateQuotationRequest(
    val customerId: String? = null,
    val customerName: String? = null,
    val items: List<QuotationItemRequest>,
    val transactionDiscount: DiscountRequest? = null,
    val validDays: Int = 14,
    val notes: String? = null,
)

@Serializable
data class QuotationItemRequest(
    val productId: String,
    @Serializable(with = BigDecimalSerializer::class)
    val qty: BigDecimal,
    val discountRequest: DiscountRequest? = null,
    val note: String? = null,
)

@Serializable
data class UpdateQuotationStatusRequest(
    val status: String,
    val convertedTransactionId: String? = null,
)

@Serializable
data class QuotationItemResponse(
    val id: String,
    val productId: String,
    val productName: String,
    val unitId: String,
    val unitName: String? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val quantity: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class)
    val unitPrice: BigDecimal,
    val discountType: DiscountType? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val discountValue: BigDecimal = BigDecimal.ZERO,
    @Serializable(with = BigDecimalSerializer::class)
    val discountAmount: BigDecimal = BigDecimal.ZERO,
    @Serializable(with = BigDecimalSerializer::class)
    val subtotal: BigDecimal,
    val note: String? = null,
)

@Serializable
data class QuotationSummaryResponse(
    val id: String,
    val quotationNumber: String,
    val userId: String,
    val cashierName: String? = null,
    val customerId: String? = null,
    val customerName: String? = null,
    val status: String,
    val validUntil: String? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val total: BigDecimal,
    val itemCount: Int,
    val notes: String? = null,
    val convertedTransactionId: String? = null,
    val createdAt: String,
)

@Serializable
data class QuotationDetailResponse(
    val id: String,
    val quotationNumber: String,
    val userId: String,
    val cashierName: String? = null,
    val customerId: String? = null,
    val customerName: String? = null,
    val status: String,
    val validUntil: String? = null,
    val notes: String? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val grossSubtotal: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class)
    val itemDiscountTotal: BigDecimal,
    val transactionDiscountType: DiscountType? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val transactionDiscountValue: BigDecimal = BigDecimal.ZERO,
    @Serializable(with = BigDecimalSerializer::class)
    val transactionDiscountAmount: BigDecimal = BigDecimal.ZERO,
    @Serializable(with = BigDecimalSerializer::class)
    val totalDiscountAmount: BigDecimal,
    val taxEnabled: Boolean = false,
    val taxMode: String? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val taxRate: BigDecimal = BigDecimal.ZERO,
    val taxLabel: String? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val taxableBase: BigDecimal = BigDecimal.ZERO,
    @Serializable(with = BigDecimalSerializer::class)
    val taxAmount: BigDecimal = BigDecimal.ZERO,
    @Serializable(with = BigDecimalSerializer::class)
    val total: BigDecimal,
    val convertedTransactionId: String? = null,
    val convertedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val items: List<QuotationItemResponse>,
)
