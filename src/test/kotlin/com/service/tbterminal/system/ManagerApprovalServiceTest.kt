package com.service.tbterminal.system

import com.service.tbterminal.shared.ManagerApprovalError
import com.service.tbterminal.shared.ManagerApprovalException
import com.service.tbterminal.shared.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManagerApprovalServiceTest {
    private val fixedInstant = Instant.parse("2026-08-30T05:00:00Z")
    private val requesterId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val adminId = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val ownerId = UUID.fromString("30000000-0000-0000-0000-000000000001")
    private val resourceId = UUID.fromString("40000000-0000-0000-0000-000000000001")

    @Test
    fun `admin can approve with valid PIN and expiry uses configured TTL`() = runBlocking {
        val repository = FakeManagerApprovalRepository().apply {
            approvers["admin"] = approver(adminId, Role.ADMIN)
        }
        val service = service(repository)

        val response = service.createApproval(requesterId, Role.KASIR, request("admin"), null)

        assertEquals(ManagerApprovalStatus.APPROVED, response.status)
        assertEquals(ManagerApprovalAction.VOID_TRANSACTION, response.action)
        assertEquals(
            OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC).plusMinutes(5).toString(),
            response.expiresAt
        )
    }

    @Test
    fun `owner can approve`() = runBlocking {
        val repository = FakeManagerApprovalRepository().apply {
            approvers["owner-manager"] = approver(ownerId, Role.OWNER)
        }

        val response = service(repository).createApproval(
            requesterId,
            Role.KASIR,
            request("owner-manager"),
            null
        )

        assertEquals(ManagerApprovalStatus.APPROVED, response.status)
    }

    @Test
    fun `cashier cannot become approver`() = runBlocking {
        val repository = FakeManagerApprovalRepository().apply {
            approvers["cashier"] = approver(adminId, Role.KASIR)
        }

        val error = assertFailsWith<ManagerApprovalException> {
            service(repository).createApproval(requesterId, Role.KASIR, request("cashier"), null)
        }

        assertEquals(ManagerApprovalError.APPROVER_FORBIDDEN, error.reason)
        assertTrue(repository.records.isEmpty())
    }

    @Test
    fun `inactive approver is rejected without creating a grant`() = runBlocking {
        val repository = FakeManagerApprovalRepository().apply {
            approvers["inactive"] = approver(adminId, Role.ADMIN, isActive = false)
        }

        val error = assertFailsWith<ManagerApprovalException> {
            service(repository).createApproval(requesterId, Role.KASIR, request("inactive"), null)
        }

        assertEquals(ManagerApprovalError.INVALID, error.reason)
        assertTrue(repository.records.isEmpty())
    }

    @Test
    fun `invalid PIN is rejected safely`() = runBlocking {
        val repository = FakeManagerApprovalRepository().apply {
            approvers["admin"] = approver(adminId, Role.ADMIN)
        }
        val invalid = request("admin").copy(approverPin = "654321")

        val error = assertFailsWith<ManagerApprovalException> {
            service(repository).createApproval(requesterId, Role.KASIR, invalid, null)
        }

        assertEquals(ManagerApprovalError.INVALID, error.reason)
        assertEquals("Kredensial manager tidak valid", error.message)
        assertTrue(repository.records.isEmpty())
    }

    @Test
    fun `requester cannot approve itself`() = runBlocking {
        val repository = FakeManagerApprovalRepository().apply {
            approvers["self"] = approver(requesterId, Role.ADMIN)
        }

        val error = assertFailsWith<ManagerApprovalException> {
            service(repository).createApproval(requesterId, Role.ADMIN, request("self"), null)
        }

        assertEquals(ManagerApprovalError.SELF_APPROVAL_FORBIDDEN, error.reason)
    }

    @Test
    fun `invalid action is rejected by serialization allowlist`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<CreateManagerApprovalRequest>(
                """{"action":"BECOME_OWNER","resourceType":"TRANSACTION","resourceId":"$resourceId","approverUsername":"admin","approverPin":"123456"}"""
            )
        }
    }

    @Test
    fun `contracts and persistence never expose or store approval credentials`() {
        val responseFields = ManagerApprovalResponse.serializer().descriptor.let { descriptor ->
            (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        }
        val requestFields = CreateManagerApprovalRequest.serializer().descriptor.let { descriptor ->
            (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        }
        val persistenceColumns = ManagerApprovalsTable.columns.map { it.name.lowercase() }.toSet()

        assertFalse(responseFields.any { it.contains("pin", ignoreCase = true) })
        assertFalse(responseFields.any { it.contains("password", ignoreCase = true) })
        assertFalse(requestFields.contains("requestedByUserId"))
        assertFalse(requestFields.contains("approverRole"))
        assertFalse(persistenceColumns.any { it.contains("pin") || it.contains("password") || it.contains("token") })
    }

    @Test
    fun `expired approval becomes invalid`() = runBlocking {
        val repository = FakeManagerApprovalRepository()
        val record = approvalRecord(
            expiresAt = OffsetDateTime.ofInstant(fixedInstant.minusSeconds(1), ZoneOffset.UTC)
        )
        repository.records[record.id] = record

        val error = assertFailsWith<ManagerApprovalException> {
            service(repository).validateApproval(scope(record.id))
        }

        assertEquals(ManagerApprovalError.EXPIRED, error.reason)
        assertEquals(ManagerApprovalStatus.EXPIRED, repository.records.getValue(record.id).status)
    }

    @Test
    fun `used approval is invalid`() = runBlocking {
        val repository = FakeManagerApprovalRepository()
        val record = approvalRecord(status = ManagerApprovalStatus.USED)
        repository.records[record.id] = record

        val error = assertFailsWith<ManagerApprovalException> {
            service(repository).validateApproval(scope(record.id))
        }

        assertEquals(ManagerApprovalError.ALREADY_USED, error.reason)
    }

    @Test
    fun `action mismatch is rejected`() = runBlocking {
        val repository = FakeManagerApprovalRepository()
        val record = approvalRecord()
        repository.records[record.id] = record

        val error = assertFailsWith<ManagerApprovalException> {
            service(repository).validateApproval(scope(record.id).copy(action = ManagerApprovalAction.REFUND_TRANSACTION))
        }

        assertEquals(ManagerApprovalError.ACTION_MISMATCH, error.reason)
    }

    @Test
    fun `resource mismatch is rejected`() = runBlocking {
        val repository = FakeManagerApprovalRepository()
        val record = approvalRecord()
        repository.records[record.id] = record

        val error = assertFailsWith<ManagerApprovalException> {
            service(repository).validateApproval(scope(record.id).copy(resourceId = UUID.randomUUID()))
        }

        assertEquals(ManagerApprovalError.SCOPE_MISMATCH, error.reason)
    }

    @Test
    fun `requester mismatch is rejected`() = runBlocking {
        val repository = FakeManagerApprovalRepository()
        val record = approvalRecord()
        repository.records[record.id] = record

        val error = assertFailsWith<ManagerApprovalException> {
            service(repository).validateApproval(scope(record.id).copy(requesterUserId = UUID.randomUUID()))
        }

        assertEquals(ManagerApprovalError.REQUESTER_MISMATCH, error.reason)
    }

    private fun service(repository: ManagerApprovalRepository) = ManagerApprovalService(
        repository = repository,
        clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
        ttl = Duration.ofMinutes(5)
    )

    private fun request(username: String) = CreateManagerApprovalRequest(
        action = ManagerApprovalAction.VOID_TRANSACTION,
        resourceType = ManagerApprovalResourceType.TRANSACTION,
        resourceId = resourceId.toString(),
        approverUsername = username,
        approverPin = VALID_PIN
    )

    private fun approver(id: UUID, role: String, isActive: Boolean = true) = ManagerApprovalApprover(
        userId = id,
        role = role,
        pinHash = VALID_PIN_HASH,
        isActive = isActive
    )

    private fun approvalRecord(
        status: ManagerApprovalStatus = ManagerApprovalStatus.APPROVED,
        expiresAt: OffsetDateTime = OffsetDateTime.ofInstant(fixedInstant.plusSeconds(60), ZoneOffset.UTC)
    ) = ManagerApprovalRecord(
        id = UUID.randomUUID(),
        requestedByUserId = requesterId,
        approvedByUserId = adminId,
        action = ManagerApprovalAction.VOID_TRANSACTION,
        resourceType = ManagerApprovalResourceType.TRANSACTION,
        resourceId = resourceId,
        status = status,
        createdAt = OffsetDateTime.ofInstant(fixedInstant.minusSeconds(60), ZoneOffset.UTC),
        expiresAt = expiresAt,
        usedAt = if (status == ManagerApprovalStatus.USED) OffsetDateTime.ofInstant(fixedInstant.minusSeconds(5), ZoneOffset.UTC) else null
    )

    private fun scope(id: UUID) = ManagerApprovalScope(
        approvalId = id,
        requesterUserId = requesterId,
        action = ManagerApprovalAction.VOID_TRANSACTION,
        resourceType = ManagerApprovalResourceType.TRANSACTION,
        resourceId = resourceId
    )

    private companion object {
        const val VALID_PIN = "123456"
        const val VALID_PIN_HASH = "\$2a\$12\$bLnNxkF4oJBf8qoymZ46xu3RMg.1o0jYQh/3A9x9XuCv8cl.hqDa6"
    }
}

private class FakeManagerApprovalRepository : ManagerApprovalRepository {
    val approvers = mutableMapOf<String, ManagerApprovalApprover>()
    val records = mutableMapOf<UUID, ManagerApprovalRecord>()

    override suspend fun findApproverByUsername(username: String): ManagerApprovalApprover? = approvers[username]

    override suspend fun createApproval(
        requestedByUserId: UUID,
        approvedByUserId: UUID,
        action: ManagerApprovalAction,
        resourceType: ManagerApprovalResourceType?,
        resourceId: UUID?,
        createdAt: OffsetDateTime,
        expiresAt: OffsetDateTime,
        ipAddress: String?
    ): ManagerApprovalRecord {
        return ManagerApprovalRecord(
            id = UUID.randomUUID(),
            requestedByUserId = requestedByUserId,
            approvedByUserId = approvedByUserId,
            action = action,
            resourceType = resourceType,
            resourceId = resourceId,
            status = ManagerApprovalStatus.APPROVED,
            createdAt = createdAt,
            expiresAt = expiresAt,
            usedAt = null
        ).also { records[it.id] = it }
    }

    override suspend fun findApprovalById(id: UUID): ManagerApprovalRecord? = records[id]

    override suspend fun findApprovalByIdForUpdateInCurrentTransaction(id: UUID): ManagerApprovalRecord? = records[id]

    override suspend fun markExpiredIfNeeded(id: UUID, now: OffsetDateTime): ManagerApprovalRecord? {
        val record = records[id] ?: return null
        if (record.status == ManagerApprovalStatus.APPROVED && !now.isBefore(record.expiresAt)) {
            records[id] = record.copy(status = ManagerApprovalStatus.EXPIRED)
        }
        return records[id]
    }

    override suspend fun consumeApproval(
        scope: ManagerApprovalScope,
        now: OffsetDateTime,
        ipAddress: String?
    ): ManagerApprovalConsumeResult {
        val record = records[scope.approvalId]
        val matches = record != null &&
            record.status == ManagerApprovalStatus.APPROVED &&
            now.isBefore(record.expiresAt) &&
            record.requestedByUserId == scope.requesterUserId &&
            record.action == scope.action &&
            record.resourceType == scope.resourceType &&
            record.resourceId == scope.resourceId
        if (!matches) return ManagerApprovalConsumeResult(false, record)
        val consumed = record.copy(status = ManagerApprovalStatus.USED, usedAt = now)
        records[scope.approvalId] = consumed
        return ManagerApprovalConsumeResult(true, consumed)
    }

    override suspend fun consumeApprovalInCurrentTransaction(
        scope: ManagerApprovalScope,
        now: OffsetDateTime,
        ipAddress: String?
    ): ManagerApprovalConsumeResult = consumeApproval(scope, now, ipAddress)
}
