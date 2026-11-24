package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.PayloadTooLargeException
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.requirePermission
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.SystemService
import com.service.tbterminal.system.recordOperationalAudit
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException

internal fun Route.productRoutes(service: InventoryService, systemService: SystemService) {
    route("/products") {
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val products = service.getProducts(page, limit, call.request.queryParameters["search"])
            call.respond(HttpStatusCode.OK, ApiResponse.success(products))
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.getProductById(id)))
        }

        post {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val actorUserId = call.getUserId()
            val product = service.createProduct(call.receive())
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.INSERT,
                schemaName = "inventory",
                tableName = "products",
                recordId = product.id
            )
            call.respond(HttpStatusCode.Created, ApiResponse.success(product, "Produk berhasil ditambahkan"))
        }

        put("/{id}") {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            val previous = service.getProductById(id)
            val request = call.receive<ProductUpdateRequest>()
            val product = service.updateProduct(id, request)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.UPDATE,
                schemaName = "inventory",
                tableName = "products",
                recordId = product.id
            )
            if (previous.hasDifferentPriceThan(product)) {
                systemService.recordOperationalAudit(
                    call = call,
                    actorUserId = actorUserId,
                    action = AuditAction.UPDATE,
                    schemaName = "inventory",
                    tableName = "products_price",
                    recordId = product.id
                )
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(product, "Produk berhasil diperbarui"))
        }

        delete("/{id}") {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            service.deleteProduct(id)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.DELETE,
                schemaName = "inventory",
                tableName = "products",
                recordId = id
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Produk berhasil dihapus"))
        }

        patch("/{id}/activate") {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            val product = service.activateProduct(id)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.UPDATE,
                schemaName = "inventory",
                tableName = "products",
                recordId = product.id
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(product, "Produk berhasil diaktifkan kembali"))
        }

        get("/{id}/price-tiers") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.getProductPriceTiers(id)))
        }

        put("/{id}/price-tiers") {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            val request = call.receive<UpdateProductPriceTiersRequest>()
            val updated = service.updateProductPriceTiers(id, request.tiers)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.UPDATE,
                schemaName = "inventory",
                tableName = "product_price_tiers",
                recordId = id
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(updated, "Harga grosir bertingkat berhasil disimpan"))
        }

        post("/{id}/photo") {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            val maxBytes = service.prepareProductPhotoUpload(id)
            var uploadedBytes: ByteArray? = null
            try {
                call.receiveMultipart(formFieldLimit = maxBytes).forEachPart { part ->
                    try {
                        if (part is PartData.FileItem && uploadedBytes == null) {
                            uploadedBytes = readLimitedBytes(part.provider(), maxBytes)
                        }
                    } finally {
                        part.dispose()
                    }
                }
            } catch (cause: IOException) {
                if (cause.message?.contains("exceeds limit", ignoreCase = true) == true) {
                    throw PayloadTooLargeException("Ukuran foto melebihi batas maksimal")
                }
                throw cause
            }
            val bytes = uploadedBytes ?: throw ValidationException("File foto wajib diunggah")
            val photo = service.uploadProductPhoto(id, bytes)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.UPDATE,
                schemaName = "inventory",
                tableName = "products_photo",
                recordId = photo.productId
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(photo, "Foto produk berhasil diunggah"))
        }

        delete("/{id}/photo") {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            service.deleteProductPhoto(id)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.DELETE,
                schemaName = "inventory",
                tableName = "products_photo",
                recordId = id
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun readLimitedBytes(channel: ByteReadChannel, maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            throw PayloadTooLargeException("Ukuran foto melebihi batas maksimal")
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun ProductResponse.hasDifferentPriceThan(other: ProductResponse): Boolean {
    return priceBuy.compareTo(other.priceBuy) != 0 ||
        priceRetail.compareTo(other.priceRetail) != 0 ||
        priceContractor.compareTo(other.priceContractor) != 0 ||
        discount.compareTo(other.discount) != 0
}
