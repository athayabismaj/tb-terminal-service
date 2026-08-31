package com.service.tbterminal.shared

import com.service.tbterminal.backup.BackupRepository
import com.service.tbterminal.backup.BackupService
import com.service.tbterminal.system.SystemRepository
import com.service.tbterminal.system.SystemService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccessPolicyTest {
    @Test
    fun `owner has all defined permissions`() {
        Permission.entries.forEach { permission ->
            assertTrue(AccessPolicy.isAllowed(Role.OWNER, permission), "Owner harus memiliki $permission")
        }
    }

    @Test
    fun `admin can manage operations but cannot manage owner-only resources`() {
        val allowed = setOf(
            Permission.READ_STORE_PROFILE,
            Permission.REQUEST_MANAGER_APPROVAL,
            Permission.APPROVE_SENSITIVE_ACTION,
            Permission.UPDATE_STORE_PROFILE,
            Permission.VIEW_AUDIT_LOG,
            Permission.MANAGE_INVENTORY,
            Permission.MANAGE_PURCHASING,
            Permission.USE_RECEIVABLES,
            Permission.MANAGE_RECEIVABLES,
            Permission.OPERATE_POS,
            Permission.MANAGE_CASH_SESSIONS,
            Permission.VOID_TRANSACTION,
            Permission.REFUND_TRANSACTION,
            Permission.OVERRIDE_DISCOUNT_LIMIT,
            Permission.VIEW_ANALYTICS
        )

        Permission.entries.forEach { permission ->
            if (permission in allowed) {
                assertTrue(AccessPolicy.isAllowed(Role.ADMIN, permission), "Admin harus memiliki $permission")
            } else {
                assertFalse(AccessPolicy.isAllowed(Role.ADMIN, permission), "Admin tidak boleh memiliki $permission")
            }
        }
    }

    @Test
    fun `cashier is limited to daily operations`() {
        val allowed = setOf(
            Permission.READ_STORE_PROFILE,
            Permission.REQUEST_MANAGER_APPROVAL,
            Permission.USE_RECEIVABLES,
            Permission.OPERATE_POS
        )

        Permission.entries.forEach { permission ->
            if (permission in allowed) {
                assertTrue(AccessPolicy.isAllowed(Role.KASIR, permission), "Kasir harus memiliki $permission")
            } else {
                assertFalse(AccessPolicy.isAllowed(Role.KASIR, permission), "Kasir tidak boleh memiliki $permission")
            }
        }
    }

    @Test
    fun `sensitive services reject admin before repository access`() {
        runBlocking {
            val systemService = SystemService(SystemRepository())
            val backupService = BackupService(BackupRepository())

            assertFailsWith<ForbiddenException> { systemService.getRoles(Role.ADMIN) }
            assertFailsWith<ForbiddenException> { systemService.getUsers(Role.ADMIN, 1, 20, null) }
            assertFailsWith<ForbiddenException> { systemService.getSecuritySettings(Role.ADMIN) }
            assertFailsWith<ForbiddenException> { backupService.list(Role.ADMIN, 10) }
        }
    }
}
