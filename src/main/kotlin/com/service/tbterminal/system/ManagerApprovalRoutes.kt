package com.service.tbterminal.system

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.getUserRole
import com.service.tbterminal.shared.requirePermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

fun Application.managerApprovalRoutes() {
    val service: ManagerApprovalService by inject()

    routing {
        authenticate("jwt-auth") {
            route("/api/system/manager-approvals") {
                rateLimit(RateLimitName("manager-approval")) {
                    post {
                        call.requirePermission(Permission.REQUEST_MANAGER_APPROVAL)
                        val approval = service.createApproval(
                            requesterUserId = call.getUserId(),
                            requesterRole = call.getUserRole(),
                            request = call.receive(),
                            ipAddress = call.managerApprovalClientIpAddress()
                        )
                        call.respond(
                            HttpStatusCode.Created,
                            ApiResponse.success(approval, "Persetujuan manager berhasil dibuat")
                        )
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.managerApprovalClientIpAddress(): String? {
    return request.headers["X-Forwarded-For"]
        ?.substringBefore(",")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: request.headers["X-Real-IP"]?.trim()?.takeIf(String::isNotBlank)
}
