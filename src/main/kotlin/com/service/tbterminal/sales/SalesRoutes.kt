package com.service.tbterminal.sales

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.getUserRole
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

                    // GET histori sesi kas seluruh kasir
                    get {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                        val status = call.request.queryParameters["status"]
                        val startDate = call.request.queryParameters["startDate"]
                        val endDate = call.request.queryParameters["endDate"]
                        call.respond(HttpStatusCode.OK, ApiResponse.success(service.getSessions(page, limit, status, startDate, endDate)))
                    }

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
                        call.respond(HttpStatusCode.Created, ApiResponse.success(session, "Sesi kasir berhasil dibuka"))
                    }

                    // POST sync sesi kas lokal yang dibuka saat offline
                    post("/sync/open") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<OfflineCashSessionOpenSyncRequest>()
                        val syncResult = service.syncOpenCashSession(userId, request)

                        val status = if (syncResult.syncStatus == "CREATED") {
                            HttpStatusCode.Created
                        } else {
                            HttpStatusCode.OK
                        }
                        call.respond(status, ApiResponse.success(syncResult, "Sync sesi kas offline berhasil diproses"))
                    }

                    // POST sync tutup sesi kas lokal yang ditutup saat offline
                    post("/sync/close") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<OfflineCashSessionCloseSyncRequest>()
                        val syncResult = service.syncCloseCashSession(userId, request)

                        call.respond(HttpStatusCode.OK, ApiResponse.success(syncResult, "Sync tutup sesi kas offline berhasil diproses"))
                    }

                    // POST tutup sesi kasir yang aktif
                    post("/close") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<CloseSessionRequest>()
                        val session = service.closeSession(userId, request)
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

                    // GET histori pengeluaran kas seluruh sesi
                    get("/expenses") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                        val sessionId = call.request.queryParameters["sessionId"]
                        val startDate = call.request.queryParameters["startDate"]
                        val endDate = call.request.queryParameters["endDate"]
                        call.respond(
                            HttpStatusCode.OK,
                            ApiResponse.success(service.getExpenseHistory(page, limit, sessionId, startDate, endDate))
                        )
                    }

                    // GET daftar pengeluaran kasir per sesi
                    get("/{sessionId}/expenses") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val sessionId = call.parameters["sessionId"]
                            ?: throw com.service.tbterminal.shared.ValidationException("Session ID wajib diisi")
                        val expenses = service.getExpenses(sessionId)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(expenses))
                    }

                    // GET detail rekonsiliasi satu sesi kas
                    get("/{sessionId}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val sessionId = call.parameters["sessionId"]
                            ?: throw com.service.tbterminal.shared.ValidationException("Session ID wajib diisi")
                        call.respond(HttpStatusCode.OK, ApiResponse.success(service.getSessionById(sessionId)))
                    }
                }

                // ==========================================
                // POS — CHECKOUT & HISTORY
                // ==========================================

                // POST sync checkout offline dari local database Android
                post("/checkout/sync") {
                    call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                    val userId = call.getUserId()
                    val request = call.receive<OfflineCheckoutSyncRequest>()
                    val syncResult = service.syncOfflineCheckout(userId, request)

                    val status = if (syncResult.syncStatus == "CREATED") {
                        HttpStatusCode.Created
                    } else {
                        HttpStatusCode.OK
                    }
                    call.respond(status, ApiResponse.success(syncResult, "Sync checkout offline berhasil diproses"))
                }

                // POST sync pengeluaran kas offline dari local database Android
                post("/cash-expenses/sync") {
                    call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                    val userId = call.getUserId()
                    val request = call.receive<OfflineCashExpenseSyncRequest>()
                    val syncResult = service.syncCashExpense(userId, request)

                    if (syncResult.syncStatus == "CREATED") {
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = userId,
                            action = AuditAction.INSERT,
                            schemaName = "sales",
                            tableName = "cash_expenses",
                            recordId = syncResult.serverExpenseId
                        )
                    }

                    val status = if (syncResult.syncStatus == "CREATED") {
                        HttpStatusCode.Created
                    } else {
                        HttpStatusCode.OK
                    }
                    call.respond(status, ApiResponse.success(syncResult, "Sync pengeluaran kas offline berhasil diproses"))
                }

                // POST checkout (transaksi baru)
                post("/checkout") {
                    call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                    val userId = call.getUserId()
                    val request = call.receive<CheckoutRequest>()
                    val transaction = service.checkout(userId, request)
                    val status = if (transaction.idempotentReplay) HttpStatusCode.OK else HttpStatusCode.Created
                    val message = if (transaction.idempotentReplay) {
                        "Checkout sebelumnya berhasil; hasil transaksi yang sama dikembalikan"
                    } else {
                        "Transaksi berhasil diproses"
                    }
                    call.respond(status, ApiResponse.success(transaction, message))
                }

                // GET riwayat transaksi dengan pagination
                get("/transactions") {
                    call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val sessionId = call.request.queryParameters["sessionId"]
                    val search = call.request.queryParameters["search"]
                    val receiptNumber = call.request.queryParameters["receiptNumber"]
                    val cashierId = if (call.getUserRole() == Role.KASIR) {
                        call.getUserId().toString()
                    } else call.request.queryParameters["cashierId"]
                    val customerId = call.request.queryParameters["customerId"]
                    val paymentMethod = call.request.queryParameters["paymentMethod"]
                    val status = call.request.queryParameters["status"]
                    val startDate = call.request.queryParameters["startDate"]
                    val endDate = call.request.queryParameters["endDate"]

                    val transactions = service.getTransactions(
                        page, limit, sessionId, search, receiptNumber, cashierId, customerId,
                        paymentMethod, status, startDate, endDate
                    )
                    call.respond(HttpStatusCode.OK, ApiResponse.success(transactions))
                }

                get("/transactions/{id}") {
                    call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                    val id = call.parameters["id"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>("ID Transaksi harus diisi")
                    )
                    
                    val transaction = service.getTransactionById(id)
                    if (call.getUserRole() == Role.KASIR && transaction.userId != call.getUserId().toString()) {
                        throw com.service.tbterminal.shared.ForbiddenException("Kasir hanya dapat melihat transaksi miliknya")
                    }
                    call.respond(HttpStatusCode.OK, ApiResponse.success(transaction))
                }

                post("/transactions/{id}/void") {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val id = call.parameters["id"]
                        ?: throw com.service.tbterminal.shared.ValidationException("ID Transaksi wajib diisi")
                    val result = service.voidTransaction(call.getUserId(), id, call.receive())
                    call.respond(HttpStatusCode.OK, ApiResponse.success(result, if (result.idempotentReplay) {
                        "Void sebelumnya sudah berhasil; hasil yang sama dikembalikan"
                    } else "Transaksi berhasil dibatalkan"))
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
