package com.service.tbterminal.di

import com.service.tbterminal.inventory.InventoryRepository
import com.service.tbterminal.inventory.InventoryRepositoryImpl
import com.service.tbterminal.inventory.InventoryService
import com.service.tbterminal.purchasing.PurchasingRepository
import com.service.tbterminal.purchasing.PurchasingRepositoryImpl
import com.service.tbterminal.purchasing.PurchasingService
import com.service.tbterminal.receivable.ReceivableRepository
import com.service.tbterminal.receivable.ReceivableRepositoryImpl
import com.service.tbterminal.receivable.ReceivableService
import com.service.tbterminal.sales.SalesRepository
import com.service.tbterminal.sales.SalesRepositoryImpl
import com.service.tbterminal.sales.SalesService
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

    // Sales Module
    single<SalesRepository> { SalesRepositoryImpl() }
    single { SalesService(get(), get()) } // get() = SalesRepository, get() = InventoryRepository

    // Receivable Module
    single<ReceivableRepository> { ReceivableRepositoryImpl() }
    single { ReceivableService(get()) }

    // Purchasing Module
    single<PurchasingRepository> { PurchasingRepositoryImpl() }
    single { PurchasingService(get(), get()) } // get() = PurchasingRepository, get() = InventoryRepository

    // Analytics Module
    single { com.service.tbterminal.analytics.AnalyticsRepository() }
    single { com.service.tbterminal.analytics.AnalyticsService(get()) }
}
