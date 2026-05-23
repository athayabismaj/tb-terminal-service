package com.service.tbterminal

import io.ktor.server.engine.*
import io.ktor.server.application.*

fun main(args: Array<String>) {
    // Force binding to 0.0.0.0 so the Android emulator (10.0.2.2) can connect
    val customArgs = args.toMutableList()
    if (!customArgs.contains("-host")) {
        customArgs.add("-host")
        customArgs.add("0.0.0.0")
    }
    io.ktor.server.netty.EngineMain.main(customArgs.toTypedArray())
}
