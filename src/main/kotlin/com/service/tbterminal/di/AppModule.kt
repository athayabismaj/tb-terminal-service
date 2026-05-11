package com.service.tbterminal.di

import com.service.tbterminal.inventory.InventoryRepository
import com.service.tbterminal.inventory.InventoryRepositoryImpl
import com.service.tbterminal.inventory.InventoryService
import com.service.tbterminal.system.SystemRepository
import com.service.tbterminal.system.SystemService
import org.koin.dsl.module

val appModule = module {
    // System Module
    single { SystemRepository() }
    single { SystemService(get()) }

    // Inventory Module
    single<InventoryRepository> { InventoryRepositoryImpl() }
    single { InventoryService(get()) }
}
