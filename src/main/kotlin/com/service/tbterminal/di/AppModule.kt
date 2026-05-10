package com.service.tbterminal.di

import com.service.tbterminal.system.SystemRepository
import com.service.tbterminal.system.SystemService
import org.koin.dsl.module

val appModule = module {
    single { SystemRepository() }
    single { SystemService(get()) }
}
