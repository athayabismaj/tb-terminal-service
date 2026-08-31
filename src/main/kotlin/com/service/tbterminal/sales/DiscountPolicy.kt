package com.service.tbterminal.sales

import com.service.tbterminal.shared.AccessPolicy
import com.service.tbterminal.shared.Permission
import java.math.BigDecimal

internal fun requiresDiscountOverride(
    actorRole: String,
    effectiveDiscountPercent: BigDecimal,
    cashierLimitPercent: BigDecimal
): Boolean = !AccessPolicy.isAllowed(actorRole, Permission.OVERRIDE_DISCOUNT_LIMIT) &&
    effectiveDiscountPercent > cashierLimitPercent
