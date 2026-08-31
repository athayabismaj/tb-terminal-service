package com.service.tbterminal.system

import com.service.tbterminal.shared.AccessPolicy
import com.service.tbterminal.shared.EnvironmentConfig
import com.service.tbterminal.shared.ManagerApprovalError
import com.service.tbterminal.shared.ManagerApprovalException
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.ValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ManagerApprovalService(
    private val repository: ManagerApprovalRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(EnvironmentConfig.managerApprovalTtlMinutes)
) {
    init {
        require(!ttl.isZero && !ttl.isNegative) { "Manager approval TTL harus positif" }
    }

    suspend fun createApproval(
        requesterUserId: UUID,
        requesterRole: String,
        request: CreateManagerApprovalRequest,
        ipAddress: String?
    ): ManagerApprovalResponse {
        AccessPolicy.require(requesterRole, Permission.REQUEST_MANAGER_APPROVAL)
        val resourceId = validateAndParseScope(request.action, request.resourceType, request.resourceId)
        val approverUsername = request.approverUsername.trim()
        if (approverUsername.length !in 3..50 || !request.approverPin.matches(PIN_PATTERN)) {
            throw invalidApprover()
        }

        val approver = repository.findApproverByUsername(approverUsername)
            ?.takeIf(ManagerApprovalApprover::isActive)
            ?: throw invalidApprover()
        if (approver.userId == requesterUserId) {
            throw ManagerApprovalException(
                ManagerApprovalError.SELF_APPROVAL_FORBIDDEN,
                "Persetujuan tidak boleh dilakukan oleh pengguna yang sama"
            )
        }
        if (!AccessPolicy.isAllowed(approver.role, Permission.APPROVE_SENSITIVE_ACTION)) {
            throw ManagerApprovalException(
                ManagerApprovalError.APPROVER_FORBIDDEN,
                "Pengguna tersebut tidak dapat memberikan persetujuan"
            )
        }

        val pinValid = withContext(Dispatchers.IO) {
            BCrypt.checkpw(request.approverPin, approver.pinHash)
        }
        if (!pinValid) throw invalidApprover()

        val createdAt = now()
        return repository.createApproval(
            requestedByUserId = requesterUserId,
            approvedByUserId = approver.userId,
            action = request.action,
            resourceType = request.resourceType,
            resourceId = resourceId,
            createdAt = createdAt,
            expiresAt = createdAt.plus(ttl),
            ipAddress = ipAddress
        ).toResponse()
    }

    suspend fun validateApproval(scope: ManagerApprovalScope): ManagerApprovalRecord {
        val currentTime = now()
        var record = repository.findApprovalById(scope.approvalId)
            ?: throw ManagerApprovalException(ManagerApprovalError.INVALID, "Manager approval tidak valid")
        if (record.status == ManagerApprovalStatus.APPROVED && !currentTime.isBefore(record.expiresAt)) {
            record = repository.markExpiredIfNeeded(record.id, currentTime) ?: record
        }
        validateRecord(record, scope, currentTime)
        return record
    }

    internal suspend fun validateApprovalInCurrentTransaction(
        scope: ManagerApprovalScope
    ): ManagerApprovalRecord {
        val currentTime = now()
        val record = repository.findApprovalByIdForUpdateInCurrentTransaction(scope.approvalId)
            ?: throw ManagerApprovalException(ManagerApprovalError.INVALID, "Manager approval tidak valid")
        validateRecord(record, scope, currentTime)
        return record
    }

    suspend fun consumeApproval(
        scope: ManagerApprovalScope,
        ipAddress: String?
    ): ManagerApprovalResponse {
        validateApproval(scope)
        val result = repository.consumeApproval(scope, now(), ipAddress)
        if (!result.consumed) {
            throw consumeFailure(result.record)
        }
        return requireNotNull(result.record).toResponse()
    }

    internal suspend fun consumeApprovalInCurrentTransaction(
        scope: ManagerApprovalScope,
        ipAddress: String?
    ): ManagerApprovalRecord {
        val result = repository.consumeApprovalInCurrentTransaction(scope, now(), ipAddress)
        if (!result.consumed) {
            throw consumeFailure(result.record)
        }
        return requireNotNull(result.record)
    }

    private fun validateRecord(
        record: ManagerApprovalRecord,
        scope: ManagerApprovalScope,
        currentTime: OffsetDateTime
    ) {
        if (record.requestedByUserId != scope.requesterUserId) {
            throw ManagerApprovalException(
                ManagerApprovalError.REQUESTER_MISMATCH,
                "Manager approval tidak berlaku untuk pengguna ini"
            )
        }
        if (record.action != scope.action) {
            throw ManagerApprovalException(
                ManagerApprovalError.ACTION_MISMATCH,
                "Manager approval tidak berlaku untuk tindakan ini"
            )
        }
        if (record.resourceType != scope.resourceType || record.resourceId != scope.resourceId) {
            throw ManagerApprovalException(
                ManagerApprovalError.SCOPE_MISMATCH,
                "Manager approval tidak berlaku untuk resource ini"
            )
        }
        when {
            record.status == ManagerApprovalStatus.USED -> throw ManagerApprovalException(
                ManagerApprovalError.ALREADY_USED,
                "Manager approval sudah digunakan"
            )

            record.status == ManagerApprovalStatus.EXPIRED || !currentTime.isBefore(record.expiresAt) ->
                throw ManagerApprovalException(
                    ManagerApprovalError.EXPIRED,
                    "Manager approval sudah kedaluwarsa"
                )

            record.status != ManagerApprovalStatus.APPROVED -> throw ManagerApprovalException(
                ManagerApprovalError.INVALID,
                "Manager approval tidak valid"
            )
        }
    }

    private fun consumeFailure(record: ManagerApprovalRecord?): ManagerApprovalException {
        return when {
            record == null -> ManagerApprovalException(
                ManagerApprovalError.INVALID,
                "Manager approval tidak valid"
            )

            record.status == ManagerApprovalStatus.USED -> ManagerApprovalException(
                ManagerApprovalError.ALREADY_USED,
                "Manager approval sudah digunakan"
            )

            record.status == ManagerApprovalStatus.EXPIRED || !now().isBefore(record.expiresAt) ->
                ManagerApprovalException(
                    ManagerApprovalError.EXPIRED,
                    "Manager approval sudah kedaluwarsa"
                )

            else -> ManagerApprovalException(
                ManagerApprovalError.INVALID,
                "Manager approval tidak dapat digunakan"
            )
        }
    }

    private fun validateAndParseScope(
        action: ManagerApprovalAction,
        resourceType: ManagerApprovalResourceType?,
        resourceId: String?
    ): UUID {
        if (resourceType != action.requiredResourceType) {
            throw ValidationException("Resource type tidak sesuai untuk action ${action.name}")
        }
        return resourceId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ValidationException("Resource ID wajib berupa UUID yang valid")
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun invalidApprover() = ManagerApprovalException(
        ManagerApprovalError.INVALID,
        "Kredensial manager tidak valid"
    )

    private companion object {
        val PIN_PATTERN = Regex("^[0-9]{4,6}$")
    }
}
