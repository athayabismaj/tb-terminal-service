package com.service.tbterminal.system

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.util.UUID

interface ManagerApprovalRepository {
    suspend fun findApproverByUsername(username: String): ManagerApprovalApprover?

    suspend fun createApproval(
        requestedByUserId: UUID,
        approvedByUserId: UUID,
        action: ManagerApprovalAction,
        resourceType: ManagerApprovalResourceType?,
        resourceId: UUID?,
        createdAt: OffsetDateTime,
        expiresAt: OffsetDateTime,
        ipAddress: String?
    ): ManagerApprovalRecord

    suspend fun findApprovalById(id: UUID): ManagerApprovalRecord?

    /** Must only be called from an existing Exposed suspended transaction. */
    suspend fun findApprovalByIdForUpdateInCurrentTransaction(id: UUID): ManagerApprovalRecord?

    suspend fun markExpiredIfNeeded(id: UUID, now: OffsetDateTime): ManagerApprovalRecord?

    suspend fun consumeApproval(
        scope: ManagerApprovalScope,
        now: OffsetDateTime,
        ipAddress: String?
    ): ManagerApprovalConsumeResult

    /** Must only be called from an existing Exposed suspended transaction. */
    suspend fun consumeApprovalInCurrentTransaction(
        scope: ManagerApprovalScope,
        now: OffsetDateTime,
        ipAddress: String?
    ): ManagerApprovalConsumeResult
}

class ManagerApprovalRepositoryImpl : ManagerApprovalRepository {
    override suspend fun findApproverByUsername(username: String): ManagerApprovalApprover? =
        newSuspendedTransaction(Dispatchers.IO) {
            (UsersTable innerJoin RolesTable)
                .select { UsersTable.username eq username }
                .singleOrNull()
                ?.let { row ->
                    ManagerApprovalApprover(
                        userId = row[UsersTable.id],
                        role = row[RolesTable.name],
                        pinHash = row[UsersTable.pinHash],
                        isActive = row[UsersTable.isActive]
                    )
                }
        }

    override suspend fun createApproval(
        requestedByUserId: UUID,
        approvedByUserId: UUID,
        action: ManagerApprovalAction,
        resourceType: ManagerApprovalResourceType?,
        resourceId: UUID?,
        createdAt: OffsetDateTime,
        expiresAt: OffsetDateTime,
        ipAddress: String?
    ): ManagerApprovalRecord = newSuspendedTransaction(Dispatchers.IO) {
        val id = UUID.randomUUID()
        ManagerApprovalsTable.insert {
            it[this.id] = id
            it[this.requestedByUserId] = requestedByUserId
            it[this.approvedByUserId] = approvedByUserId
            it[this.action] = action.name
            it[this.resourceType] = resourceType?.name
            it[this.resourceId] = resourceId
            it[status] = ManagerApprovalStatus.APPROVED.name
            it[this.createdAt] = createdAt
            it[this.expiresAt] = expiresAt
        }
        val record = ManagerApprovalsTable.select { ManagerApprovalsTable.id eq id }
            .single()
            .toManagerApprovalRecord()
        insertAudit(
            actorUserId = requestedByUserId,
            auditAction = AuditAction.INSERT,
            event = "MANAGER_APPROVAL_CREATED",
            record = record,
            ipAddress = ipAddress
        )
        record
    }

    override suspend fun findApprovalById(id: UUID): ManagerApprovalRecord? =
        newSuspendedTransaction(Dispatchers.IO) {
            ManagerApprovalsTable.select { ManagerApprovalsTable.id eq id }
                .singleOrNull()
                ?.toManagerApprovalRecord()
        }

    override suspend fun findApprovalByIdForUpdateInCurrentTransaction(id: UUID): ManagerApprovalRecord? =
        ManagerApprovalsTable.select { ManagerApprovalsTable.id eq id }
            .forUpdate()
            .singleOrNull()
            ?.toManagerApprovalRecord()

    override suspend fun markExpiredIfNeeded(id: UUID, now: OffsetDateTime): ManagerApprovalRecord? =
        newSuspendedTransaction(Dispatchers.IO) {
            val expired = ManagerApprovalsTable.update({
                (ManagerApprovalsTable.id eq id) and
                    (ManagerApprovalsTable.status eq ManagerApprovalStatus.APPROVED.name) and
                    (ManagerApprovalsTable.expiresAt lessEq now)
            }) {
                it[status] = ManagerApprovalStatus.EXPIRED.name
            } == 1
            val record = ManagerApprovalsTable.select { ManagerApprovalsTable.id eq id }
                .singleOrNull()
                ?.toManagerApprovalRecord()
            if (expired && record != null) {
                insertAudit(
                    actorUserId = record.requestedByUserId,
                    auditAction = AuditAction.UPDATE,
                    event = "MANAGER_APPROVAL_EXPIRED",
                    record = record,
                    ipAddress = null
                )
            }
            record
        }

    override suspend fun consumeApproval(
        scope: ManagerApprovalScope,
        now: OffsetDateTime,
        ipAddress: String?
    ): ManagerApprovalConsumeResult = newSuspendedTransaction(Dispatchers.IO) {
        consumeApprovalInCurrentTransaction(scope, now, ipAddress)
    }

    override suspend fun consumeApprovalInCurrentTransaction(
        scope: ManagerApprovalScope,
        now: OffsetDateTime,
        ipAddress: String?
    ): ManagerApprovalConsumeResult {
        val expired = ManagerApprovalsTable.update({
            (ManagerApprovalsTable.id eq scope.approvalId) and
                (ManagerApprovalsTable.status eq ManagerApprovalStatus.APPROVED.name) and
                (ManagerApprovalsTable.expiresAt lessEq now)
        }) {
            it[status] = ManagerApprovalStatus.EXPIRED.name
        } == 1

        var predicate: Op<Boolean> =
            (ManagerApprovalsTable.id eq scope.approvalId) and
                (ManagerApprovalsTable.requestedByUserId eq scope.requesterUserId) and
                (ManagerApprovalsTable.action eq scope.action.name) and
                (ManagerApprovalsTable.status eq ManagerApprovalStatus.APPROVED.name) and
                (ManagerApprovalsTable.expiresAt greater now)

        predicate = predicate and if (scope.resourceType == null) {
            ManagerApprovalsTable.resourceType.isNull()
        } else {
            ManagerApprovalsTable.resourceType eq scope.resourceType.name
        }
        predicate = predicate and if (scope.resourceId == null) {
            ManagerApprovalsTable.resourceId.isNull()
        } else {
            ManagerApprovalsTable.resourceId eq scope.resourceId
        }

        val consumed = ManagerApprovalsTable.update({ predicate }) {
            it[status] = ManagerApprovalStatus.USED.name
            it[usedAt] = now
        } == 1
        val record = ManagerApprovalsTable.select { ManagerApprovalsTable.id eq scope.approvalId }
            .singleOrNull()
            ?.toManagerApprovalRecord()

        if (expired && record != null) {
            insertAudit(
                actorUserId = record.requestedByUserId,
                auditAction = AuditAction.UPDATE,
                event = "MANAGER_APPROVAL_EXPIRED",
                record = record,
                ipAddress = ipAddress
            )
        } else if (consumed && record != null) {
            insertAudit(
                actorUserId = scope.requesterUserId,
                auditAction = AuditAction.UPDATE,
                event = "MANAGER_APPROVAL_USED",
                record = record,
                ipAddress = ipAddress
            )
        }
        return ManagerApprovalConsumeResult(consumed = consumed, record = record)
    }

    private fun insertAudit(
        actorUserId: UUID,
        auditAction: AuditAction,
        event: String,
        record: ManagerApprovalRecord,
        ipAddress: String?
    ) {
        val metadata = buildJsonObject {
            put("event", event)
            put("approvalId", record.id.toString())
            put("requesterUserId", record.requestedByUserId.toString())
            put("approverUserId", record.approvedByUserId.toString())
            put("approvalAction", record.action.name)
            record.resourceType?.let { put("resourceType", it.name) }
            record.resourceId?.let { put("resourceId", it.toString()) }
            put("status", record.status.name)
        }.toString()

        AuditLogsTable.insert {
            it[userId] = actorUserId
            it[action] = auditAction
            it[targetSchemaName] = "system"
            it[targetTableName] = "manager_approvals"
            it[recordId] = record.id
            it[newData] = metadata
            it[this.ipAddress] = ipAddress?.take(45)
        }
    }
}

private fun ResultRow.toManagerApprovalRecord() = ManagerApprovalRecord(
    id = this[ManagerApprovalsTable.id],
    requestedByUserId = this[ManagerApprovalsTable.requestedByUserId],
    approvedByUserId = this[ManagerApprovalsTable.approvedByUserId],
    action = ManagerApprovalAction.valueOf(this[ManagerApprovalsTable.action]),
    resourceType = this[ManagerApprovalsTable.resourceType]?.let(ManagerApprovalResourceType::valueOf),
    resourceId = this[ManagerApprovalsTable.resourceId],
    status = ManagerApprovalStatus.valueOf(this[ManagerApprovalsTable.status]),
    createdAt = this[ManagerApprovalsTable.createdAt],
    expiresAt = this[ManagerApprovalsTable.expiresAt],
    usedAt = this[ManagerApprovalsTable.usedAt]
)
