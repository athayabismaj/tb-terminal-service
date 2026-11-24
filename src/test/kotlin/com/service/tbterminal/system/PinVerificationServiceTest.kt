package com.service.tbterminal.system

import com.service.tbterminal.plugins.configureSerialization
import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.AuthenticatedUserPrincipal
import com.service.tbterminal.shared.JwtHelper
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.TooManyAttemptsException
import com.service.tbterminal.shared.UnauthorizedException
import com.service.tbterminal.shared.ValidationException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post as routePost
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinVerificationServiceTest {

    private val cashierId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val adminId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val ownerId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    @Test
    fun `kasir wrong PIN stays unlocked with decremented attempts_left and generic message`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-11-19T10:00:00Z"))
        val gateway = FakePinVerificationGateway().apply {
            users[cashierId] = testUser(cashierId, "kasir1", Role.KASIR, VALID_PIN)
        }
        val service = PinVerificationService(gateway, clock)

        val response = service.verifyUnlock(
            userId = cashierId,
            request = UnlockRequest(pin = WRONG_PIN, deviceId = "pos-1"),
            ipAddress = "10.0.0.5"
        )

        assertFalse(response.success)
        assertEquals("INVALID_PIN", response.code)
        assertEquals("PIN salah", response.error)
        val data = assertNotNull(response.data)
        assertFalse(data.valid)
        assertEquals(4, data.attemptsLeft)
        assertNull(data.lockedUntil)
        assertFalse(data.forceLogout)
        assertEquals(0, gateway.revokedUsers.size)
        assertEquals(1, gateway.audits.size)
        assertEquals("pin_unlock_failed", gateway.audits.single().tableName)
        assertFalse(gateway.audits.single().metadataJson.contains(WRONG_PIN))
    }

    @Test
    fun `kasir reaches limit 5 then triggers escalating backoff 30s 1m 5m 15m and 10th failure forces logout`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-11-19T10:00:00Z"))
        val gateway = FakePinVerificationGateway().apply {
            users[cashierId] = testUser(cashierId, "kasir1", Role.KASIR, VALID_PIN)
        }
        val service = PinVerificationService(gateway, clock)

        // Attempts 1..4 -> attempts_left 4..1, unlocked
        for (attempt in 1..4) {
            val res = service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1"))
            assertEquals("INVALID_PIN", res.code)
            assertEquals(5 - attempt, res.data?.attemptsLeft)
            assertNull(res.data?.lockedUntil)
            assertFalse(res.data?.forceLogout == true)
        }

        // Attempt 5 -> 30s lockout
        val lock30 = service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1"))
        assertEquals("PIN_LOCKED", lock30.code)
        assertEquals(0, lock30.data?.attemptsLeft)
        assertEquals(
            OffsetDateTime.ofInstant(clock.instant().plusSeconds(30), ZoneOffset.UTC).toString(),
            lock30.data?.lockedUntil
        )

        // Calling while locked stays blocked without incrementing attempts
        clock.advanceSeconds(10)
        val stillLocked = service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1"))
        assertEquals("PIN_LOCKED", stillLocked.code)
        assertEquals(lock30.data?.lockedUntil, stillLocked.data?.lockedUntil)
        assertEquals(5, gateway.attempts[cashierId to PIN_SCOPE]?.failedAttempts)

        // Advance past 30s -> Attempt 6 -> 1m (60s) lockout
        clock.advanceSeconds(21)
        val lock60 = service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1"))
        assertEquals("PIN_LOCKED", lock60.code)
        assertEquals(
            OffsetDateTime.ofInstant(clock.instant().plusSeconds(60), ZoneOffset.UTC).toString(),
            lock60.data?.lockedUntil
        )

        // Advance past 1m -> Attempt 7 -> 5m (300s) lockout
        clock.advanceSeconds(61)
        val lock300 = service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1"))
        assertEquals("PIN_LOCKED", lock300.code)
        assertEquals(
            OffsetDateTime.ofInstant(clock.instant().plusSeconds(300), ZoneOffset.UTC).toString(),
            lock300.data?.lockedUntil
        )

        // Advance past 5m -> Attempt 8 -> 15m (900s) lockout
        clock.advanceSeconds(301)
        val lock900 = service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1"))
        assertEquals("PIN_LOCKED", lock900.code)
        assertEquals(
            OffsetDateTime.ofInstant(clock.instant().plusSeconds(900), ZoneOffset.UTC).toString(),
            lock900.data?.lockedUntil
        )

        // Advance past 15m -> Attempt 9 -> 15m (900s) lockout
        clock.advanceSeconds(901)
        val lock900Second = service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1"))
        assertEquals("PIN_LOCKED", lock900Second.code)
        assertFalse(lock900Second.data?.forceLogout == true)

        // Advance past 15m -> Attempt 10 -> force_logout & session revocation
        clock.advanceSeconds(901)
        val forceLogout = service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1"))
        assertEquals("PIN_MAX_ATTEMPTS_EXCEEDED", forceLogout.code)
        assertTrue(forceLogout.data?.forceLogout == true)
        assertEquals(0, forceLogout.data?.attemptsLeft)
        assertEquals(listOf(cashierId), gateway.revokedUsers)
        assertTrue(gateway.audits.any { it.tableName == "pin_unlock_revoked" })
    }

    @Test
    fun `valid PIN resets counter to 0 while lockout expiration alone does not`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-11-19T10:00:00Z"))
        val gateway = FakePinVerificationGateway().apply {
            users[cashierId] = testUser(cashierId, "kasir1", Role.KASIR, VALID_PIN)
        }
        val service = PinVerificationService(gateway, clock)

        repeat(5) { service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1")) }
        assertEquals(5, gateway.attempts[cashierId to PIN_SCOPE]?.failedAttempts)

        // Lockout expires -> failure counter remains 5 until valid PIN is entered
        clock.advanceSeconds(31)
        assertEquals(5, gateway.attempts[cashierId to PIN_SCOPE]?.failedAttempts)

        val unlocked = service.verifyUnlock(cashierId, UnlockRequest(VALID_PIN, "pos-1"))
        assertTrue(unlocked.success)
        assertTrue(unlocked.data?.valid == true)
        assertEquals(5, unlocked.data?.attemptsLeft)
        assertNull(unlocked.data?.lockedUntil)
        assertNull(gateway.attempts[cashierId to PIN_SCOPE])

        // Next wrong PIN starts cleanly from 4 attempts left again
        val nextWrong = service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "pos-1"))
        assertEquals(4, nextWrong.data?.attemptsLeft)
    }

    @Test
    fun `admin and owner have stricter 3-attempt threshold and longer backoff`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-11-19T10:00:00Z"))
        val gateway = FakePinVerificationGateway().apply {
            users[adminId] = testUser(adminId, "admin1", Role.ADMIN, VALID_PIN)
            users[ownerId] = testUser(ownerId, "owner1", Role.OWNER, VALID_PIN)
        }
        val service = PinVerificationService(gateway, clock)

        val adminFirst = service.verifyUnlock(adminId, UnlockRequest(WRONG_PIN, "tab-1"))
        assertEquals(2, adminFirst.data?.attemptsLeft)
        val adminSecond = service.verifyUnlock(adminId, UnlockRequest(WRONG_PIN, "tab-1"))
        assertEquals(1, adminSecond.data?.attemptsLeft)
        val adminThird = service.verifyUnlock(adminId, UnlockRequest(WRONG_PIN, "tab-1"))
        assertEquals("PIN_LOCKED", adminThird.code)
        assertEquals(
            OffsetDateTime.ofInstant(clock.instant().plusSeconds(60), ZoneOffset.UTC).toString(),
            adminThird.data?.lockedUntil
        )

        clock.advanceSeconds(61)
        val adminFourth = service.verifyUnlock(adminId, UnlockRequest(WRONG_PIN, "tab-1"))
        assertEquals(
            OffsetDateTime.ofInstant(clock.instant().plusSeconds(300), ZoneOffset.UTC).toString(),
            adminFourth.data?.lockedUntil
        )

        repeat(2) { service.verifyUnlock(ownerId, UnlockRequest(WRONG_PIN, "tab-2")) }
        val ownerThird = service.verifyUnlock(ownerId, UnlockRequest(WRONG_PIN, "tab-2"))
        assertEquals("PIN_LOCKED", ownerThird.code)
        assertEquals(
            OffsetDateTime.ofInstant(clock.instant().plusSeconds(60), ZoneOffset.UTC).toString(),
            ownerThird.data?.lockedUntil
        )
    }

    @Test
    fun `changing deviceId does not reset the lockout counter`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-11-19T10:00:00Z"))
        val gateway = FakePinVerificationGateway().apply {
            users[cashierId] = testUser(cashierId, "kasir1", Role.KASIR, VALID_PIN)
        }
        val service = PinVerificationService(gateway, clock)

        // Penyerang mengganti deviceId di setiap percobaan.
        val results = (1..5).map { attempt ->
            service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "rotating-device-$attempt"))
        }
        assertEquals(listOf(4, 3, 2, 1), results.take(4).map { it.data?.attemptsLeft })
        assertEquals("PIN_LOCKED", results.last().code)

        val otherDevice = service.verifyUnlock(cashierId, UnlockRequest(VALID_PIN, "fresh-device"))
        assertEquals("PIN_LOCKED", otherDevice.code)
        assertEquals(5, gateway.attempts[cashierId to PIN_SCOPE]?.failedAttempts)
    }

    @Test
    fun `audit metadata stays valid JSON for hostile deviceId`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-11-19T10:00:00Z"))
        val gateway = FakePinVerificationGateway().apply {
            users[cashierId] = testUser(cashierId, "kasir1", Role.KASIR, VALID_PIN)
        }
        val service = PinVerificationService(gateway, clock)

        service.verifyUnlock(cashierId, UnlockRequest(WRONG_PIN, "dev\\\"\n{evil}"))

        val metadata = Json.parseToJsonElement(gateway.audits.single().metadataJson).jsonObject
        val storedDeviceId = metadata.getValue("deviceId").jsonPrimitive.content
        assertTrue(storedDeviceId.all { it.isLetterOrDigit() || it in "._:-" }, storedDeviceId)
    }

    @Test
    fun `old PIN check on change PIN shares the unlock lockout counter`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-11-19T10:00:00Z"))
        val user = testUser(cashierId, "kasir1", Role.KASIR, VALID_PIN)
        val gateway = FakePinVerificationGateway().apply { users[cashierId] = user }
        val service = PinVerificationService(gateway, clock)

        repeat(4) {
            assertFailsWith<ValidationException> {
                service.verifyCredentialForSensitiveChange(
                    user, CredentialScope.PIN, WRONG_PIN, "pos-1", null, "PIN lama tidak valid"
                )
            }
        }
        assertFailsWith<TooManyAttemptsException> {
            service.verifyCredentialForSensitiveChange(
                user, CredentialScope.PIN, WRONG_PIN, "pos-1", null, "PIN lama tidak valid"
            )
        }
        // Unlock pun ikut terkunci karena memakai penghitung yang sama.
        val unlock = service.verifyUnlock(cashierId, UnlockRequest(VALID_PIN, "pos-1"))
        assertEquals("PIN_LOCKED", unlock.code)
    }

    @Test
    fun `password check revokes sessions after max failures`() = runBlocking {
        val clock = MutableTestClock(Instant.parse("2026-11-19T10:00:00Z"))
        val user = testUser(adminId, "admin1", Role.ADMIN, VALID_PIN)
        val gateway = FakePinVerificationGateway().apply { users[adminId] = user }
        val service = PinVerificationService(gateway, clock)

        var revokedError: UnauthorizedException? = null
        for (attempt in 1..10) {
            try {
                service.verifyCredentialForSensitiveChange(
                    user, CredentialScope.PASSWORD, "wrong-password", null, null, "Password tidak valid"
                )
            } catch (_: ValidationException) {
            } catch (_: TooManyAttemptsException) {
                clock.advanceSeconds(1801)
            } catch (error: UnauthorizedException) {
                revokedError = error
            }
        }
        assertNotNull(revokedError)
        assertEquals(listOf(adminId), gateway.revokedUsers)

        // Password benar setelah reset counter diterima tanpa exception.
        service.verifyCredentialForSensitiveChange(
            user, CredentialScope.PASSWORD, "secret123", null, null, "Password tidak valid"
        )
    }

    @Test
    fun `unlock route returns 422 on wrong PIN and 423 on lockout instead of 401`() = testApplication {
        val clock = MutableTestClock(Instant.parse("2026-11-19T10:00:00Z"))
        val user = testUser(cashierId, "kasir1", Role.KASIR, VALID_PIN)
        val gateway = FakePinVerificationGateway().apply {
            users[cashierId] = user
        }
        val pinService = PinVerificationService(gateway, clock)

        application {
            configureSerialization()
            routing {
                routePost("/api/auth/unlock") {
                    val req = call.receive<UnlockRequest>()
                    val res = pinService.verifyUnlock(cashierId, req, call.request.headers["X-Device-Id"])
                    val status = when {
                        res.success -> HttpStatusCode.OK
                        res.code == "PIN_LOCKED" -> HttpStatusCode.Locked
                        res.code == "PIN_MAX_ATTEMPTS_EXCEEDED" -> HttpStatusCode.Forbidden
                        else -> HttpStatusCode.UnprocessableEntity
                    }
                    call.respond(status, res)
                }
            }
        }

        val first = client.post("/api/auth/unlock") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "dev-1")
            setBody("""{"pin":"$WRONG_PIN"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, first.status)
        val firstJson = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        val firstData = firstJson["data"]!!.jsonObject
        assertFalse(firstData["valid"]!!.jsonPrimitive.boolean)
        assertEquals(4, firstData["attempts_left"]!!.jsonPrimitive.int)

        // Exhaust attempts to hit lockout (423 Locked)
        repeat(4) {
            client.post("/api/auth/unlock") {
                contentType(ContentType.Application.Json)
                header("X-Device-Id", "dev-1")
                setBody("""{"pin":"$WRONG_PIN"}""")
            }
        }
        val lockedRes = client.post("/api/auth/unlock") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "dev-1")
            setBody("""{"pin":"$WRONG_PIN"}""")
        }
        assertEquals(HttpStatusCode.Locked, lockedRes.status)
    }

    private fun testUser(id: UUID, username: String, role: String, pin: String) = UserRow(
        id = id,
        username = username,
        name = username,
        passwordHash = BCrypt.hashpw("secret123", BCrypt.gensalt()),
        pinHash = BCrypt.hashpw(pin, BCrypt.gensalt()),
        email = null,
        roleName = role,
        isActive = true,
        tokenVersion = 1,
        createdAt = OffsetDateTime.now(),
        lastLoginAt = null
    )

    private companion object {
        const val VALID_PIN = "654321"
        const val WRONG_PIN = "999999"
        val PIN_SCOPE = CredentialScope.PIN.key
    }
}

private class MutableTestClock(private var currentInstant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = currentInstant
    fun advanceSeconds(seconds: Long) {
        currentInstant = currentInstant.plusSeconds(seconds)
    }
}

private data class RecordedAudit(
    val userId: UUID,
    val action: AuditAction,
    val tableName: String,
    val metadataJson: String
)

private class FakePinVerificationGateway : PinVerificationGateway {
    val users = mutableMapOf<UUID, UserRow>()
    val attempts = mutableMapOf<Pair<UUID, String>, PinAttemptState>()
    val revokedUsers = mutableListOf<UUID>()
    val audits = mutableListOf<RecordedAudit>()

    override suspend fun findPinUserById(userId: UUID): UserRow? = users[userId]

    override suspend fun revokeSessionsForUser(userId: UUID): Boolean {
        revokedUsers += userId
        val user = users[userId] ?: return false
        users[userId] = user.copy(tokenVersion = user.tokenVersion + 1)
        return true
    }

    override suspend fun recordPinAuditLog(
        actorUserId: UUID,
        action: AuditAction,
        tableName: String,
        recordId: UUID,
        ipAddress: String?,
        metadataJson: String
    ) {
        audits += RecordedAudit(actorUserId, action, tableName, metadataJson)
    }

    override suspend fun getPinAttemptState(userId: UUID, deviceId: String): PinAttemptState? {
        return attempts[userId to deviceId]
    }

    override suspend fun savePinAttemptState(
        userId: UUID,
        deviceId: String,
        failedAttempts: Int,
        lockedUntil: OffsetDateTime?
    ) {
        attempts[userId to deviceId] = PinAttemptState(failedAttempts, lockedUntil)
    }

    override suspend fun resetPinAttemptState(userId: UUID, deviceId: String) {
        attempts.remove(userId to deviceId)
    }

    override suspend fun resetAllPinAttemptStatesForUser(userId: UUID) {
        attempts.keys.filter { it.first == userId }.forEach { attempts.remove(it) }
    }

    override suspend fun incrementPinFailure(userId: UUID, scopeKey: String): Int {
        val current = attempts[userId to scopeKey] ?: PinAttemptState()
        val next = current.copy(failedAttempts = current.failedAttempts + 1)
        attempts[userId to scopeKey] = next
        return next.failedAttempts
    }
}
