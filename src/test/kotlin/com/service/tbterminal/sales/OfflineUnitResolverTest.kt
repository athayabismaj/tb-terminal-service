package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class OfflineUnitResolverTest {

    private val pcs = UUID.randomUUID()
    private val dus = UUID.randomUUID()
    private val retail = BigDecimal("10000.00")
    private val tierPrice = BigDecimal("9000.00")

    /** Harga eceran 10.000, grosir 9.000 mulai 10 satuan dasar. */
    private val tiered: (BigDecimal) -> BigDecimal = { baseQty ->
        if (baseQty >= BigDecimal("10")) tierPrice else retail
    }

    private fun resolve(
        quantity: String,
        price: String,
        units: List<OfflineProductUnit> = emptyList(),
        client: OfflineItemUnit? = null,
        history: Set<BigDecimal> = emptySet(),
        expected: (BigDecimal) -> BigDecimal = { retail }
    ) = OfflineUnitResolver.resolve(
        BigDecimal(quantity), BigDecimal(price), client, pcs, units, expected, history
    )

    private fun dusOf(factor: String) = listOf(OfflineProductUnit(dus, BigDecimal(factor)))

    private fun client(unit: UUID, baseQuantity: String) = OfflineItemUnit(unit, BigDecimal(baseQuantity))

    private fun assertResolved(
        actual: OfflineUnitResolution,
        baseQuantity: String,
        basePrice: String,
        source: OfflineUnitSource,
        soldUnitId: UUID?
    ) {
        val resolved = assertIs<OfflineUnitResolution.Resolved>(actual)
        assertEquals(BigDecimal(baseQuantity), resolved.baseQuantity)
        assertEquals(BigDecimal(basePrice), resolved.baseUnitPrice)
        assertEquals(source, resolved.source)
        assertEquals(soldUnitId, resolved.soldUnitId)
    }

    // --- Pengenalan harga (dipindah dari OfflineCheckoutGuardTest) ---

    @Test
    fun `harga eceran satuan dasar diterima dengan faktor 1`() {
        val result = resolve("2", "10000", expected = tiered)
        assertResolved(result, "2.00", "10000.00", OfflineUnitSource.LEGACY_BASE, pcs)
        assertEquals(0, BigDecimal.ONE.compareTo((result as OfflineUnitResolution.Resolved).factor))
    }

    @Test
    fun `harga grosir diterima hanya bila kuantitas memenuhi tier`() {
        assertIs<OfflineUnitResolution.Resolved>(resolve("10", "9000.00", expected = tiered))
        assertIs<OfflineUnitResolution.PriceUnknown>(resolve("2", "9000.00", expected = tiered))
    }

    @Test
    fun `harga yang dimanipulasi kasir ditolak`() {
        assertIs<OfflineUnitResolution.PriceUnknown>(resolve("5", "1", expected = tiered))
        assertIs<OfflineUnitResolution.PriceUnknown>(resolve("5", "0", expected = tiered))
    }

    @Test
    fun `harga satuan sekunder dikenali lewat faktor konversi`() {
        // 1 dus = 12 pcs. 1 dus => 12 pcs memenuhi tier grosir (9.000 x 12).
        val result = resolve("1", "108000", dusOf("12.0000"), expected = tiered)
        assertEquals(0, BigDecimal("12").compareTo(assertIs<OfflineUnitResolution.Resolved>(result).factor))
    }

    @Test
    fun `harga lama selama sesi masih diterima`() {
        assertResolved(
            resolve("1", "9500", history = setOf(BigDecimal("9500.00")), expected = tiered),
            "1.00", "9500.00", OfflineUnitSource.LEGACY_BASE, pcs
        )
    }

    // --- Klien baru ---

    @Test
    fun `klien baru dengan satuan sekunder disimpan dalam satuan dasar`() {
        assertResolved(
            resolve("2", "120000", dusOf("12.0000"), client(dus, "24")),
            "24.00", "10000.00", OfflineUnitSource.CLIENT_UNIT, dus
        )
    }

    @Test
    fun `klien baru dengan satuan dasar tidak dikonversi`() {
        assertResolved(
            resolve("2", "10000", dusOf("12"), client(pcs, "2.00")),
            "2.00", "10000.00", OfflineUnitSource.CLIENT_UNIT, pcs
        )
    }

    @Test
    fun `unitId bukan milik produk ditolak`() {
        assertIs<OfflineUnitResolution.UnitConflict>(
            resolve("2", "120000", dusOf("12"), client(UUID.randomUUID(), "24"))
        )
    }

    @Test
    fun `baseQuantity harus sama dengan quantity kali faktor`() {
        listOf("2", "23", "24.01", "0", "-24").forEach { baseQuantity ->
            assertIs<OfflineUnitResolution.UnitConflict>(
                resolve("2", "120000", dusOf("12"), client(dus, baseQuantity)), baseQuantity
            )
        }
        assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "10000", dusOf("12"), client(pcs, "24")))
    }

    @Test
    fun `harga klien baru hanya dicocokkan dengan satuan yang dikirim`() {
        // Harga dus dengan satuan pcs (dan sebaliknya) bukan harga yang sah.
        assertIs<OfflineUnitResolution.PriceUnknown>(resolve("2", "120000", dusOf("12"), client(pcs, "2")))
        assertIs<OfflineUnitResolution.PriceUnknown>(resolve("2", "10000", dusOf("12"), client(dus, "24")))
    }

    @Test
    fun `satuan eksplisit menyelesaikan harga yang ambigu bagi klien lama`() {
        // Harga riwayat 120.000 per pcs sama dengan 12 x harga eceran.
        val history = setOf(BigDecimal("120000.00"))
        assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "120000", dusOf("12"), history = history))
        assertResolved(
            resolve("2", "120000", dusOf("12"), client(dus, "24"), history),
            "24.00", "10000.00", OfflineUnitSource.CLIENT_UNIT, dus
        )
        assertResolved(
            resolve("2", "120000", dusOf("12"), client(pcs, "2"), history),
            "2.00", "120000.00", OfflineUnitSource.CLIENT_UNIT, pcs
        )
    }

    // --- Klien lama ---

    @Test
    fun `klien lama dengan satu faktor yang cocok dikonversi otomatis`() {
        assertResolved(
            resolve("2", "120000", dusOf("12.0000")), "24.00", "10000.00", OfflineUnitSource.LEGACY_AUTO, dus
        )
        // Harga dus yang sah pada harga lama.
        assertResolved(
            resolve("2", "114000", dusOf("12"), history = setOf(BigDecimal("9500.00"))),
            "24.00", "9500.00", OfflineUnitSource.LEGACY_AUTO, dus
        )
    }

    @Test
    fun `klien lama dengan harga satuan dasar tetap tidak dikonversi`() {
        assertResolved(resolve("2", "10000", dusOf("12")), "2.00", "10000.00", OfflineUnitSource.LEGACY_BASE, pcs)
    }

    @Test
    fun `klien lama ditolak bila harga cocok dengan dua satuan sekunder`() {
        val units = dusOf("12") + OfflineProductUnit(UUID.randomUUID(), BigDecimal("6"))
        // 60.000 = 6 x 10.000 atau 12 x 5.000 (harga lama).
        assertIs<OfflineUnitResolution.UnitConflict>(
            resolve("2", "60000", units, history = setOf(BigDecimal("5000.00")))
        )
    }

    @Test
    fun `produk gratis dengan satuan sekunder ambigu bagi klien lama saja`() {
        val free: (BigDecimal) -> BigDecimal = { BigDecimal.ZERO }
        assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "0", dusOf("12"), expected = free))
        assertResolved(
            resolve("2", "0", dusOf("12"), client(dus, "24"), expected = free),
            "24.00", "0.00", OfflineUnitSource.CLIENT_UNIT, dus
        )
        assertResolved(resolve("2", "0", expected = free), "2.00", "0.00", OfflineUnitSource.LEGACY_BASE, pcs)
    }

    @Test
    fun `faktor sama di dua satuan tetap dikonversi tetapi satuan asalnya tidak ditebak`() {
        val units = dusOf("12") + OfflineProductUnit(UUID.randomUUID(), BigDecimal("12.0000"))
        assertResolved(resolve("2", "120000", units), "24.00", "10000.00", OfflineUnitSource.LEGACY_AUTO, null)
    }

    // --- Keeksakan ---

    @Test
    fun `konversi ditolak bila subtotal bergeser`() {
        // 1.250,75 x 2,5 = 3.126,875 -> 3.126,88; 2 x 3.126,88 = 6.253,76, sedangkan 5 x 1.250,75 = 6.253,75.
        val price: (BigDecimal) -> BigDecimal = { BigDecimal("1250.75") }
        assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "3126.88", dusOf("2.5"), expected = price))
        assertIs<OfflineUnitResolution.UnitConflict>(
            resolve("2", "3126.88", dusOf("2.5"), client(dus, "5"), expected = price)
        )
    }

    @Test
    fun `kuantitas dasar lebih dari dua desimal ditolak`() {
        val units = dusOf("0.3333")
        assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "3333.00", units))
        assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "3333.00", units, client(dus, "0.6666")))
    }

    @Test
    fun `faktor pecahan yang eksak diterima`() {
        assertResolved(
            resolve("2", "125000", dusOf("12.5"), client(dus, "25.00")),
            "25.00", "10000.00", OfflineUnitSource.CLIENT_UNIT, dus
        )
    }

    @Test
    fun `kandidat harga yang menjaga subtotal dipilih`() {
        // Faktor 0,01: harga 100.00 dan 100.40 sama-sama menjadi 1.00, hanya 100.00 yang menjaga subtotal.
        val history = setOf(BigDecimal("100.00"))
        assertResolved(
            resolve("100", "1.00", dusOf("0.01"), client(dus, "1"), history) { BigDecimal("100.40") },
            "1.00", "100.00", OfflineUnitSource.CLIENT_UNIT, dus
        )
    }

    // --- Payload ---

    private fun item(unitId: String? = null, baseQuantity: String? = null) = OfflineCheckoutItemRequest(
        productId = UUID.randomUUID().toString(),
        productNameSnapshot = "Produk",
        quantity = BigDecimal("2"),
        priceAtTransaction = BigDecimal("120000"),
        cogsAtTransaction = BigDecimal.ZERO,
        subtotal = BigDecimal("240000"),
        unitId = unitId,
        baseQuantity = baseQuantity?.let(::BigDecimal)
    )

    @Test
    fun `unitId dan baseQuantity wajib berpasangan`() {
        assertNull(parseOfflineItemUnit(item()))
        assertEquals(client(dus, "24"), parseOfflineItemUnit(item(dus.toString(), "24")))
        assertFailsWith<ValidationException> { parseOfflineItemUnit(item(unitId = dus.toString())) }
        assertFailsWith<ValidationException> { parseOfflineItemUnit(item(baseQuantity = "24")) }
        listOf("", "bukan-uuid").forEach {
            assertFailsWith<ValidationException> { parseOfflineItemUnit(item(it, "24")) }
        }
    }

    @Test
    fun `pesan konflik satuan memuat business rule`() {
        val message = offlineConflict(OfflineConflictCode.OFFLINE_UNIT_CONFLICT, "satuan").message.orEmpty()
        assertEquals(true, message.contains("business rule"))
    }
}
