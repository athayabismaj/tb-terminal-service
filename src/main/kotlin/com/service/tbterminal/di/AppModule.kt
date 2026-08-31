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
import com.service.tbterminal.sales.RefundRepository
import com.service.tbterminal.sales.RefundRepositoryImpl
import com.service.tbterminal.sales.RefundService
import com.service.tbterminal.sales.DiscountRepository
import com.service.tbterminal.sales.DiscountRepositoryImpl
import com.service.tbterminal.sales.DiscountService
import com.service.tbterminal.system.SystemRepository
import com.service.tbterminal.system.SystemService
import com.service.tbterminal.system.UserSessionService
import com.service.tbterminal.system.ManagerApprovalRepository
import com.service.tbterminal.system.ManagerApprovalRepositoryImpl
import com.service.tbterminal.system.ManagerApprovalService
import org.koin.dsl.module

val appModule = module {
    single { com.service.tbterminal.backup.BackupRepository() }
    single { com.service.tbterminal.backup.BackupService(get()) }
    // System Module
    single { SystemRepository() }
    single { SystemService(get()) }
    single { UserSessionService(get()) }
    single<ManagerApprovalRepository> { ManagerApprovalRepositoryImpl() }
    single { ManagerApprovalService(get()) }

    // Inventory Module
    single<InventoryRepository> { InventoryRepositoryImpl() }
    single { InventoryService(get()) }

    // Sales Module
    single<DiscountRepository> { DiscountRepositoryImpl() }
    single { DiscountService(get()) }
    single<SalesRepository> { SalesRepositoryImpl(get(), get()) }
    single { SalesService(get()) }
    single<RefundRepository> { RefundRepositoryImpl(get()) }
    single { RefundService(get()) }

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
