package com.service.tbterminal.analytics

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.requirePermission
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.SystemService
import com.service.tbterminal.system.recordOperationalAudit
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.analyticsRoutes() {
    val service: AnalyticsService by inject()
    val systemService: SystemService by inject()

    routing {
        authenticate("jwt-auth") {
            route("/api/analytics") {
                
                // ==========================================
                // DASHBOARD METRICS
                // ==========================================
                get("/dashboard") {
                    // MUTLAK: Hanya ADMIN dan OWNER
                    call.requirePermission(Permission.VIEW_ANALYTICS)
                    val metrics = service.getDashboardMetrics()
                    call.respond(HttpStatusCode.OK, ApiResponse.success(metrics))
                }

                // ==========================================
                // DAILY SALES
                // ==========================================
                get("/sales") {
                    // MUTLAK: Hanya ADMIN dan OWNER
                    call.requirePermission(Permission.VIEW_ANALYTICS)
                    
                    val startDate = call.request.queryParameters["startDate"]
                    val endDate = call.request.queryParameters["endDate"]
                    
                    val sales = service.getDailySales(startDate, endDate)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(sales))
                }

                // ==========================================
                // SALES REPORT AGGREGATE
                // ==========================================
                get("/sales/report") {
                    // MUTLAK: Hanya ADMIN dan OWNER
                    call.requirePermission(Permission.VIEW_ANALYTICS)

                    val report = service.getSalesReport(
                        startDateStr = call.request.queryParameters["startDate"],
                        endDateStr = call.request.queryParameters["endDate"],
                        cashierIdStr = call.request.queryParameters["cashierId"],
                        sessionIdStr = call.request.queryParameters["sessionId"],
                        topProductsLimitStr = call.request.queryParameters["topProductsLimit"],
                        customerIdStr = call.request.queryParameters["customerId"],
                        productIdStr = call.request.queryParameters["productId"],
                        categoryIdStr = call.request.queryParameters["categoryId"],
                        paymentMethodStr = call.request.queryParameters["paymentMethod"],
                        statusStr = call.request.queryParameters["status"]
                    )
                    call.respond(HttpStatusCode.OK, ApiResponse.success(report))
                }

                get("/exports/{type}.csv") {
                    call.requirePermission(Permission.VIEW_ANALYTICS)
                    val typePath = call.parameters["type"].orEmpty()
                    val type = CsvExportType.fromPath(typePath)
                        ?: throw com.service.tbterminal.shared.ValidationException("Jenis ekspor tidak didukung")
                    val filter = service.parseExportFilter(
                        call.request.queryParameters.entries().associate { it.key to it.value.first() }
                    )
                    val fileName = "${type.path}-${filter.startDate}-${filter.endDate}.csv"
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
                    )
                    var exportedRows = 0L
                    call.respondOutputStream(ContentType.parse("text/csv; charset=utf-8")) {
                        bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write('\uFEFF'.code)
                            var offset = 0L
                            var wroteHeader = false
                            while (true) {
                                val page = service.getCsvPage(type, filter, EXPORT_PAGE_SIZE, offset)
                                if (!wroteHeader) {
                                    CsvSupport.writeRow(writer, page.headers)
                                    wroteHeader = true
                                }
                                page.rows.forEach { CsvSupport.writeRow(writer, it) }
                                exportedRows += page.rows.size
                                if (page.rows.size < EXPORT_PAGE_SIZE) break
                                offset += page.rows.size
                                writer.flush()
                            }
                        }
                    }
                    systemService.recordOperationalAudit(
                        call = call,
                        actorUserId = call.getUserId(),
                        action = AuditAction.INSERT,
                        schemaName = "analytics",
                        tableName = "csv_export_${type.path.replace('-', '_')}",
                        recordId = null
                    )
                }
            }
        }
    }
}

private const val EXPORT_PAGE_SIZE = 500
