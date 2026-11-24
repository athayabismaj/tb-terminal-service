package com.service.tbterminal.inventory

import com.service.tbterminal.plugins.configureSerialization
import com.service.tbterminal.plugins.configureStatusPages
import com.service.tbterminal.shared.AuthenticatedUserPrincipal
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.system.SystemRepository
import com.service.tbterminal.system.SystemService
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductPhotoRouteTest {

    @BeforeTest
    fun resetKoinBefore() = stopKoin()

    @AfterTest
    fun resetKoinAfter() = stopKoin()

    private fun validJpegBytes(extraSize: Int = 16): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(extraSize) { 0x2A }

    private fun setupApp(
        builder: ApplicationTestBuilder,
        repo: FakeProductPhotoRepository,
        mediaStorage: ProductMediaStorage,
        maxBytes: Long = 1024L
    ) {
        Database.connect(
            url = "jdbc:h2:mem:product_photo_route_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        val service = InventoryService(
            repository = repo,
            mediaStorage = mediaStorage,
            maxImageBytesProvider = { maxBytes }
        )
        val systemService = SystemService(SystemRepository())
        builder.application {
            install(Koin) {
                modules(
                    module {
                        single { service }
                        single { systemService }
                    }
                )
            }
            configureSerialization()
            configureStatusPages()
            install(Authentication) {
                bearer("jwt-auth") {
                    authenticate { credential ->
                        when (credential.token) {
                            "owner-token" -> AuthenticatedUserPrincipal(UUID.randomUUID(), "owner", "owner", 1)
                            "kasir-token" -> AuthenticatedUserPrincipal(UUID.randomUUID(), "kasir", "kasir", 1)
                            else -> null
                        }
                    }
                }
            }
            inventoryRoutes()
        }
    }

    @Test
    fun `disabled feature returns 503 for upload and delete`() = testApplication {
        val productId = UUID.randomUUID()
        val repo = FakeProductPhotoRepository(activeProductIds = mutableSetOf(productId))
        setupApp(this, repo, DisabledProductMediaStorage())

        val uploadRes = client.post("/api/inventory/products/$productId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            key = "photo",
                            value = validJpegBytes(),
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"foto.jpg\"")
                            }
                        )
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, uploadRes.status)

        val deleteRes = client.delete("/api/inventory/products/$productId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, deleteRes.status)
    }

    @Test
    fun `kasir role is forbidden 403 and missing product returns 404 before upload`() = testApplication {
        val existingId = UUID.randomUUID()
        val missingId = UUID.randomUUID()
        val repo = FakeProductPhotoRepository(activeProductIds = mutableSetOf(existingId))
        val storage = FakeMediaStorage()
        setupApp(this, repo, storage)

        val forbidden = client.post("/api/inventory/products/$existingId/photo") {
            header(HttpHeaders.Authorization, "Bearer kasir-token")
            setBody(MultiPartFormDataContent(formData {
                append("photo", validJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"foto.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals(0, storage.uploadCount)

        val notFound = client.post("/api/inventory/products/$missingId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
            setBody(MultiPartFormDataContent(formData {
                append("photo", validJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"foto.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.NotFound, notFound.status)
        assertEquals(0, storage.uploadCount)
    }

    @Test
    fun `invalid magic bytes returns 400 and oversized payload returns 413`() = testApplication {
        val productId = UUID.randomUUID()
        val repo = FakeProductPhotoRepository(activeProductIds = mutableSetOf(productId))
        val storage = FakeMediaStorage()
        setupApp(this, repo, storage, maxBytes = 64L)

        val invalidFile = client.post("/api/inventory/products/$productId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
            setBody(MultiPartFormDataContent(formData {
                append("photo", "plain-text-not-image".encodeToByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"note.txt\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.BadRequest, invalidFile.status)
        assertEquals(0, storage.uploadCount)

        val oversized = client.post("/api/inventory/products/$productId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
            setBody(MultiPartFormDataContent(formData {
                append("photo", validJpegBytes(extraSize = 200), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"big.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertEquals(0, storage.uploadCount)
    }

    @Test
    fun `upload replaces photo destroys old publicId and delete is idempotent 204`() = testApplication {
        val productId = UUID.randomUUID()
        val repo = FakeProductPhotoRepository(activeProductIds = mutableSetOf(productId))
        val storage = FakeMediaStorage()
        setupApp(this, repo, storage, maxBytes = 4096L)

        // First upload
        val firstRes = client.post("/api/inventory/products/$productId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
            setBody(MultiPartFormDataContent(formData {
                append("photo", validJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"first.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, firstRes.status, firstRes.bodyAsText())
        assertTrue(firstRes.bodyAsText().contains("https://res.cloudinary.com/demo/image/upload/v1/pub-1"))
        assertFalse(firstRes.bodyAsText().contains("photoPublicId"))
        assertTrue(storage.destroyedPublicIds.isEmpty())

        // Verify GET product and stock detail expose photoUrl and omit photoPublicId
        val productRes = client.get("/api/inventory/products/$productId") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
        }
        assertEquals(HttpStatusCode.OK, productRes.status)
        assertTrue(productRes.bodyAsText().contains("pub-1"))
        assertFalse(productRes.bodyAsText().contains("photoPublicId"))

        val stockRes = client.get("/api/inventory/stock") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
        }
        assertEquals(HttpStatusCode.OK, stockRes.status)
        assertTrue(stockRes.bodyAsText().contains("pub-1"))

        // Second upload replaces old photo and destroys pub-1 after commit
        val secondRes = client.post("/api/inventory/products/$productId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
            setBody(MultiPartFormDataContent(formData {
                append("photo", validJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"second.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, secondRes.status)
        assertEquals(listOf("tbterminal/products/pub-1"), storage.destroyedPublicIds)
        assertEquals("https://res.cloudinary.com/demo/image/upload/v2/pub-2", repo.photos[productId]?.first)

        // Delete removes photo and destroys pub-2
        val delRes1 = client.delete("/api/inventory/products/$productId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
        }
        assertEquals(HttpStatusCode.NoContent, delRes1.status)
        assertNull(repo.photos[productId])
        assertEquals(
            listOf("tbterminal/products/pub-1", "tbterminal/products/pub-2"),
            storage.destroyedPublicIds
        )

        // Second delete is idempotent 204 with no extra destroy call
        val delRes2 = client.delete("/api/inventory/products/$productId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
        }
        assertEquals(HttpStatusCode.NoContent, delRes2.status)
        assertEquals(2, storage.destroyedPublicIds.size)
    }

    @Test
    fun `upload failure during db update compensates by destroying newly uploaded publicId`() = testApplication {
        val productId = UUID.randomUUID()
        val repo = FakeProductPhotoRepository(
            activeProductIds = mutableSetOf(productId),
            failSetProductPhoto = true
        )
        val storage = FakeMediaStorage()
        setupApp(this, repo, storage, maxBytes = 4096L)

        val response = client.post("/api/inventory/products/$productId/photo") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
            setBody(MultiPartFormDataContent(formData {
                append("photo", validJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"faildb.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(listOf("tbterminal/products/pub-1"), storage.destroyedPublicIds)
        assertNull(repo.photos[productId])
    }

    private class FakeMediaStorage : ProductMediaStorage {
        override val isEnabled: Boolean = true
        var uploadCount: Int = 0
        val destroyedPublicIds = mutableListOf<String>()

        override fun ensureEnabled() = Unit

        override suspend fun uploadProductPhoto(bytes: ByteArray, mediaType: ImageMediaType): UploadedProductMedia {
            uploadCount += 1
            val publicId = "tbterminal/products/pub-$uploadCount"
            val url = "https://res.cloudinary.com/demo/image/upload/v$uploadCount/pub-$uploadCount"
            return UploadedProductMedia(secureUrl = url, publicId = publicId)
        }

        override suspend fun destroyProductPhoto(publicId: String) {
            destroyedPublicIds += publicId
        }
    }

    private class FakeProductPhotoRepository(
        val activeProductIds: MutableSet<UUID>,
        private val failSetProductPhoto: Boolean = false
    ) : InventoryRepository {
        val photos = mutableMapOf<UUID, Pair<String, String>>()

        override suspend fun getProductById(id: UUID): ProductResponse? {
            if (id !in activeProductIds) return null
            return ProductResponse(
                id = id.toString(),
                categoryId = UUID.randomUUID().toString(),
                baseUnitId = UUID.randomUUID().toString(),
                sku = "SKU-001",
                name = "Semen Tiga Roda",
                priceBuy = BigDecimal("50000.00"),
                priceRetail = BigDecimal("65000.00"),
                priceContractor = BigDecimal("60000.00"),
                discount = BigDecimal.ZERO,
                minStock = BigDecimal("10.00"),
                photoUrl = photos[id]?.first,
                isActive = true
            )
        }

        override suspend fun setProductPhoto(id: UUID, photoUrl: String, publicId: String): String? {
            if (failSetProductPhoto) error("Simulated DB write failure")
            if (id !in activeProductIds) throw NotFoundException("Produk tidak ditemukan")
            val old = photos[id]?.second
            photos[id] = photoUrl to publicId
            return old
        }

        override suspend fun clearProductPhoto(id: UUID): String? {
            if (id !in activeProductIds) throw NotFoundException("Produk tidak ditemukan")
            return photos.remove(id)?.second
        }

        override suspend fun getPaginatedStockDetail(
            limit: Int,
            offset: Int,
            search: String?
        ): PaginatedResponse<StockDetailResponse> {
            val items = activeProductIds.map { id ->
                StockDetailResponse(
                    productId = id.toString(),
                    sku = "SKU-001",
                    productName = "Semen Tiga Roda",
                    categoryName = "Semen",
                    unitName = "Sak",
                    baseUnitId = "00000000-0000-0000-0000-000000000001",
                    baseUnitName = "Sak",
                    quantity = BigDecimal("15.00"),
                    minStock = BigDecimal("10.00"),
                    priceBuy = BigDecimal("50000.00"),
                    priceRetail = BigDecimal("65000.00"),
                    priceContractor = BigDecimal("60000.00"),
                    discount = BigDecimal.ZERO,
                    isActive = true,
                    photoUrl = photos[id]?.first
                )
            }
            return PaginatedResponse(items, items.size.toLong(), 1, limit, 1)
        }

        override suspend fun getAllCategories(): List<CategoryResponse> = emptyList()
        override suspend fun getCategories(page: Int, limit: Int, search: String?): PaginatedResponse<CategoryResponse> = TODO()
        override suspend fun getCategoryById(id: UUID): CategoryResponse? = TODO()
        override suspend fun getCategoryByName(name: String): CategoryResponse? = TODO()
        override suspend fun createCategory(name: String): UUID = TODO()
        override suspend fun updateCategory(id: UUID, name: String): Boolean = TODO()
        override suspend fun deleteCategory(id: UUID): Boolean = TODO()
        override suspend fun getAllUnits(): List<UnitResponse> = emptyList()
        override suspend fun getUnits(page: Int, limit: Int, search: String?): PaginatedResponse<UnitResponse> = TODO()
        override suspend fun getUnitById(id: UUID): UnitResponse? = TODO()
        override suspend fun getUnitByNameOrSymbol(name: String, symbol: String): UnitResponse? = TODO()
        override suspend fun createUnit(name: String, symbol: String): UUID = TODO()
        override suspend fun updateUnit(id: UUID, name: String, symbol: String): Boolean = TODO()
        override suspend fun deleteUnit(id: UUID): Boolean = TODO()
        override suspend fun getProducts(page: Int, limit: Int, search: String?): PaginatedResponse<ProductResponse> = TODO()
        override suspend fun getProductByIdIncludingInactive(id: UUID): ProductResponse? = getProductById(id)
        override suspend fun getProductBySku(sku: String, includeInactive: Boolean): ProductResponse? = null
        override suspend fun getAllSkus(): Set<String> = emptySet()
        override suspend fun createProductAndInitStock(
            categoryId: UUID, baseUnitId: UUID, sku: String, name: String,
            priceBuy: BigDecimal, priceRetail: BigDecimal, priceContractor: BigDecimal,
            discount: BigDecimal, minStock: BigDecimal, secondaryUnitId: UUID?, secondaryUnitFactor: BigDecimal?
        ): UUID = TODO()
        override suspend fun updateProduct(
            id: UUID, categoryId: UUID, baseUnitId: UUID, name: String,
            priceBuy: BigDecimal, priceRetail: BigDecimal, priceContractor: BigDecimal,
            discount: BigDecimal, minStock: BigDecimal, secondaryUnitId: UUID?, secondaryUnitFactor: BigDecimal?
        ): Boolean = TODO()
        override suspend fun restoreProductAndOverwrite(
            id: UUID, categoryId: UUID, baseUnitId: UUID, name: String,
            priceBuy: BigDecimal, priceRetail: BigDecimal, priceContractor: BigDecimal,
            discount: BigDecimal, minStock: BigDecimal, secondaryUnitId: UUID?, secondaryUnitFactor: BigDecimal?
        ): Boolean = TODO()
        override suspend fun softDeleteProduct(id: UUID): Boolean = TODO()
        override suspend fun activateProduct(id: UUID): Boolean = TODO()
        override suspend fun getProductPriceTiers(productId: UUID): List<ProductPriceTierDto> = emptyList()
        override suspend fun replaceProductPriceTiers(productId: UUID, tiers: List<ProductPriceTierDto>): List<ProductPriceTierDto> = TODO()
        override suspend fun getStockAdjustments(page: Int, limit: Int, search: String?, type: String?): PaginatedResponse<StockAdjustmentResponse> = TODO()
        override suspend fun getStockCard(
            page: Int, limit: Int, productId: UUID?, search: String?,
            type: StockMovementType?, startAt: OffsetDateTime?, endExclusive: OffsetDateTime?
        ): StockCardResponse = TODO()
        override suspend fun getCurrentStockForUpdate(productId: UUID): BigDecimal? = TODO()
        override suspend fun executeOpname(productId: UUID, newQty: BigDecimal, userId: UUID, adjType: AdjType, notes: String?): Boolean = TODO()
        override suspend fun createOpeningBalance(productId: UUID, quantity: BigDecimal, occurredOn: LocalDate, note: String, userId: UUID): UUID = TODO()
        override suspend fun importProducts(rows: List<ResolvedProductImportRow>, userId: UUID): ProductCsvImportResponse = TODO()
    }
}
