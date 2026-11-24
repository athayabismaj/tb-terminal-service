package com.service.tbterminal.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.request.path
import org.slf4j.event.Level
import java.util.UUID

fun Application.configureMonitoring() {
    // Backend hanya dapat dijangkau lewat nginx (lihat deploy/nginx.conf). nginx menimpa
    // X-Forwarded-For dengan $remote_addr; useLastProxy() tetap aman walau suatu saat
    // dikonfigurasi ulang menjadi append ($proxy_add_x_forwarded_for), karena entri
    // terakhir selalu ditulis nginx, bukan klien.
    install(XForwardedHeaders) {
        useLastProxy()
    }
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.length in 8..128 && it.all { char -> char.isLetterOrDigit() || char in "-_." } }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        filter { call -> !call.request.path().startsWith("/health") }
    }
}
