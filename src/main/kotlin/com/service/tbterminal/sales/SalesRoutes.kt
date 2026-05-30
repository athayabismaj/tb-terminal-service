package com.service.tbterminal.sales

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.requireRole
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.SystemService
import com.service.tbterminal.system.recordOperationalAudit
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import org.koin.ktor.ext.inject
import java.util.UUID

fun Application.salesRoutes() {
    val service by inject<SalesService>()
    val systemService by inject<SystemService>()

    routing {
        authenticate("jwt-auth") {
            route("/api/sales") {

                // ==========================================
                // CASH SESSION ROUTES
                // ==========================================
                route("/sessions") {

                    // GET sesi aktif milik user yang sedang login
                    get("/active") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val session = service.getActiveSession(userId)
                        if (session != null) {
                            call.respond(HttpStatusCode.OK, ApiResponse.success(session))
                        } else {
                            call.respond(HttpStatusCode.OK, ApiResponse.success<CashSessionResponse?>(null, "Tidak ada sesi aktif"))
                        }
                    }

                    // POST buka sesi kasir baru
                    post("/open") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<OpenSessionRequest>()
                        val session = service.openSession(userId, request)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = userId,
                            action = AuditAction.INSERT,
                            schemaName = "sales",
                            tableName = "cash_sessions",
                            recordId = session.id
                        )
                        call.respond(HttpStatusCode.Created, ApiResponse.success(session, "Sesi kasir berhasil dibuka"))
                    }

                    // POST tutup sesi kasir yang aktif
                    post("/close") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<CloseSessionRequest>()
                        val session = service.closeSession(userId, request)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = userId,
                            action = AuditAction.UPDATE,
                            schemaName = "sales",
                            tableName = "cash_sessions",
                            recordId = session.id
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(session, "Sesi kasir berhasil ditutup"))
                    }

                    // POST catat pengeluaran kasir
                    post("/expenses") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<CashExpenseRequest>()
                        val expense = service.addExpense(userId, request)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = userId,
                            action = AuditAction.INSERT,
                            schemaName = "sales",
                            tableName = "cash_expenses",
                            recordId = expense.id
                        )
                        call.respond(HttpStatusCode.Created, ApiResponse.success(expense, "Pengeluaran berhasil dicatat"))
                    }

                    // GET daftar pengeluaran kasir per sesi
                    get("/{sessionId}/expenses") {
                        val sessionId = call.parameters["sessionId"] ?: throw IllegalArgumentException("Missing sessionId")
                        val expenses = service.getExpenses(sessionId)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(expenses))
                    }
                }

                // ==========================================
                // POS — CHECKOUT & HISTORY
                // ==========================================

                // POST checkout (transaksi baru)
                post("/checkout") {
                    call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                    val userId = call.getUserId()
                    val request = call.receive<CheckoutRequest>()
                    val transaction = service.checkout(userId, request)
                    systemService.recordOperationalAudit(
                        call = call,
                        actorUserId = userId,
                        action = AuditAction.INSERT,
                        schemaName = "sales",
                        tableName = "transactions",
                        recordId = transaction.id
                    )
                    recordCheckoutReceivableAudit(
                        call = call,
                        service = service,
                        systemService = systemService,
                        actorUserId = userId,
                        transactionId = transaction.id
                    )
                    call.respond(HttpStatusCode.Created, ApiResponse.success(transaction, "Transaksi berhasil diproses"))
                }

                // GET riwayat transaksi dengan pagination
                get("/transactions") {
                    call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val sessionId = call.request.queryParameters["sessionId"]
                    val search = call.request.queryParameters["search"]
                    val status = call.request.queryParameters["status"]
                    val startDate = call.request.queryParameters["startDate"]
                    val endDate = call.request.queryParameters["endDate"]

                    val transactions = service.getTransactions(page, limit, sessionId, search, status, startDate, endDate)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(transactions))
                }

                get("/transactions/{id}") {
                    call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                    val id = call.parameters["id"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>("ID Transaksi harus diisi")
                    )
                    
                    val transaction = service.getTransactionById(id)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(transaction))
                }

                // POST pelunasan piutang (bayar hutang transaksi)
                post("/transactions/{id}/pay") {
                    call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                    val id = call.parameters["id"] ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>("ID Transaksi harus diisi")
                    )
                    val userId = call.getUserId()
                    val request = call.receive<PayDebtRequest>()
                    
                    val transaction = service.payTransactionDebt(userId, id, request)
                    systemService.recordOperationalAudit(
                        call = call,
                        actorUserId = userId,
                        action = AuditAction.UPDATE,
                        schemaName = "sales",
                        tableName = "transactions",
                        recordId = transaction.id
                    )
                    recordTransactionDebtPaymentReceivableAudit(
                        call = call,
                        service = service,
                        systemService = systemService,
                        actorUserId = userId,
                        transactionId = transaction.id
                    )
                    call.respond(HttpStatusCode.OK, ApiResponse.success(transaction, "Pembayaran berhasil dicatat"))
                }
            }
        }
    }
}

private suspend fun recordCheckoutReceivableAudit(
    call: ApplicationCall,
    service: SalesService,
    systemService: SystemService,
    actorUserId: UUID,
    transactionId: String
) {
    try {
        val receivableId = service.getReceivableIdByTransactionId(transactionId) ?: return
        systemService.recordOperationalAudit(
            call = call,
            actorUserId = actorUserId,
            action = AuditAction.INSERT,
            schemaName = "receivable",
            tableName = "receivables",
            recordId = receivableId
        )
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        call.application.log.error("Checkout receivable audit lookup failed: transactionId=$transactionId", cause)
    }
}

private suspend fun recordTransactionDebtPaymentReceivableAudit(
    call: ApplicationCall,
    service: SalesService,
    systemService: SystemService,
    actorUserId: UUID,
    transactionId: String
) {
    try {
        val receivableId = service.getReceivableIdByTransactionId(transactionId) ?: return
        systemService.recordOperationalAudit(
            call = call,
            actorUserId = actorUserId,
            action = AuditAction.UPDATE,
            schemaName = "receivable",
            tableName = "receivables",
            recordId = receivableId
        )
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        call.application.log.error("Transaction debt receivable audit lookup failed: transactionId=$transactionId", cause)
    }
}
