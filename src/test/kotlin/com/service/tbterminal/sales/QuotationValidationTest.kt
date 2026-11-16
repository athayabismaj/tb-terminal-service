package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class QuotationValidationTest {

    @Test
    fun `validateCreateRequest accepts valid quotation items and note`() {
        val request = CreateQuotationRequest(
            customerId = UUID.randomUUID().toString(),
            items = listOf(
                QuotationItemRequest(
                    productId = UUID.randomUUID().toString(),
                    qty = BigDecimal("2.50"),
                    note = "Potong 2.5m per batang",
                )
            ),
            validDays = 14,
            notes = "RAB renovasi dapur",
        )
        QuotationValidation.validateCreateRequest(request)
    }

    @Test
    fun `validateCreateRequest rejects empty items, invalid qty, out-of-range validDays, and long note`() {
        assertThrows(ValidationException::class.java) {
            QuotationValidation.validateCreateRequest(
                CreateQuotationRequest(items = emptyList())
            )
        }
        assertThrows(ValidationException::class.java) {
            QuotationValidation.validateCreateRequest(
                CreateQuotationRequest(
                    items = listOf(
                        QuotationItemRequest(productId = UUID.randomUUID().toString(), qty = BigDecimal.ZERO)
                    )
                )
            )
        }
        assertThrows(ValidationException::class.java) {
            QuotationValidation.validateCreateRequest(
                CreateQuotationRequest(
                    items = listOf(
                        QuotationItemRequest(productId = UUID.randomUUID().toString(), qty = BigDecimal.ONE)
                    ),
                    validDays = 0,
                )
            )
        }
        assertThrows(ValidationException::class.java) {
            QuotationValidation.validateCreateRequest(
                CreateQuotationRequest(
                    items = listOf(
                        QuotationItemRequest(
                            productId = UUID.randomUUID().toString(),
                            qty = BigDecimal.ONE,
                            note = "x".repeat(301),
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `validateStatusTransition enforces valid lifecycle and optional convertedTransactionId`() {
        val txId = UUID.randomUUID()
        val (target, parsedTxId) = QuotationValidation.validateStatusTransition(
            currentStatus = QuotationStatus.DRAFT,
            request = UpdateQuotationStatusRequest(
                status = "CONVERTED",
                convertedTransactionId = txId.toString(),
            ),
        )
        assertEquals(QuotationStatus.CONVERTED, target)
        assertEquals(txId, parsedTxId)

        val (sentTarget, sentTxId) = QuotationValidation.validateStatusTransition(
            currentStatus = QuotationStatus.DRAFT,
            request = UpdateQuotationStatusRequest(status = "sent"),
        )
        assertEquals(QuotationStatus.SENT, sentTarget)
        assertNull(sentTxId)

        assertThrows(ValidationException::class.java) {
            QuotationValidation.validateStatusTransition(
                currentStatus = QuotationStatus.CONVERTED,
                request = UpdateQuotationStatusRequest(status = "CANCELLED"),
            )
        }
        assertThrows(ValidationException::class.java) {
            QuotationValidation.validateStatusTransition(
                currentStatus = QuotationStatus.CANCELLED,
                request = UpdateQuotationStatusRequest(status = "CONVERTED"),
            )
        }
    }
}
