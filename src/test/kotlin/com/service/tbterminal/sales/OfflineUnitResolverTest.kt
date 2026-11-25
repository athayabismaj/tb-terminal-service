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
        everUnits: List<OfflineProductUnit> = emptyList(),
        everPrices: Set<BigDecimal> = emptySet(),
        expected: (BigDecimal) -> BigDecimal = { retail }
    ) = OfflineUnitResolver.resolve(
        BigDecimal(quantity), BigDecimal(price), client, pcs, units, expected, history,
        OfflineUnitHistory(everUnits, everPrices)
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
        // Satuan sekunder yang sudah lama dihapus tetap membuat jumlahnya tidak pasti.
        assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "0", everUnits = dusOf("12"), expected = free))
        // Produk yang tidak pernah punya satuan sekunder hanya punya satu tafsir.
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

    // --- Faktor lama di dalam jendela sesi ---

    /** Faktor saat ini 10, faktor lama 12. */
    private val changedDus = dusOf("10.0000") + dusOf("12.0000")

    @Test
    fun `klien baru boleh memakai faktor saat ini atau faktor lama`() {
        assertResolved(
            resolve("2", "120000", changedDus, client(dus, "24")), "24.00", "10000.00", OfflineUnitSource.CLIENT_UNIT, dus
        )
        assertResolved(
            resolve("2", "100000", changedDus, client(dus, "20")), "20.00", "10000.00", OfflineUnitSource.CLIENT_UNIT, dus
        )
    }

    @Test
    fun `harga dan baseQuantity tidak boleh memakai faktor yang berbeda`() {
        assertIs<OfflineUnitResolution.PriceUnknown>(resolve("2", "100000", changedDus, client(dus, "24")))
        assertIs<OfflineUnitResolution.PriceUnknown>(resolve("2", "120000", changedDus, client(dus, "20")))
    }

    @Test
    fun `pesan konflik menyebut faktor saat ini`() {
        val conflict = assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "120000", changedDus, client(dus, "22")))
        assertEquals("baseQuantity tidak sama dengan quantity x faktor satuan di server (10)", conflict.reason)
    }

    @Test
    fun `satuan dasar selalu berfaktor 1 dan faktor lama hanya berlaku untuk satuannya`() {
        val box = UUID.randomUUID()
        val units = listOf(
            OfflineProductUnit(dus, BigDecimal("10")),
            OfflineProductUnit(box, BigDecimal("12")),
            OfflineProductUnit(pcs, BigDecimal("12"))
        )
        assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "120000", units, client(dus, "24")))
        assertIs<OfflineUnitResolution.UnitConflict>(resolve("2", "120000", units, client(pcs, "24")))
        assertResolved(resolve("2", "120000", units, client(box, "24")), "24.00", "10000.00", OfflineUnitSource.CLIENT_UNIT, box)
    }

    @Test
    fun `klien lama dikonversi dengan faktor lama bila hanya itu yang cocok`() {
        assertResolved(resolve("1", "120000", changedDus), "12.00", "10000.00", OfflineUnitSource.LEGACY_AUTO, dus)
        assertResolved(resolve("1", "100000", changedDus), "10.00", "10000.00", OfflineUnitSource.LEGACY_AUTO, dus)
    }

    @Test
    fun `klien lama ditolak bila faktor lama dan baru sama-sama menjelaskan harga`() {
        // 120.000 = 10.000 x 12 (faktor lama) = 12.000 x 10 (harga lama).
        assertIs<OfflineUnitResolution.UnitConflict>(
            resolve("1", "120000", changedDus, history = setOf(BigDecimal("12000.00")))
        )
    }

    @Test
    fun `faktor yang kembali ke nilai semula tetap menunjuk satuan yang sama`() {
        assertResolved(
            resolve("1", "120000", dusOf("12.0000") + dusOf("10.0000") + dusOf("12.0000")),
            "12.00", "10000.00", OfflineUnitSource.LEGACY_AUTO, dus
        )
    }

    // --- Riwayat di luar jendela: hanya untuk menolak ---

    private val oldPrice = setOf(BigDecimal("10000.00"))

    @Test
    fun `harga satuan dasar yang sama dengan harga dus lama ditolak`() {
        // Dulu 12 x 10.000; sekarang satuan dasar 120.000 dan dus sudah dihapus.
        val now: (BigDecimal) -> BigDecimal = { BigDecimal("120000.00") }
        assertIs<OfflineUnitResolution.UnitConflict>(
            resolve("2", "120000", everUnits = dusOf("12"), everPrices = oldPrice, expected = now)
        )
        // Tanpa pasangan faktor dan harga yang cocok, hanya ada satu tafsir.
        listOf(
            resolve("2", "120000", everUnits = dusOf("12"), expected = now),
            resolve("2", "120000", everPrices = oldPrice, expected = now),
            resolve("2", "120000", everUnits = dusOf("11"), everPrices = oldPrice, expected = now)
        ).forEach { assertResolved(it, "2.00", "120000.00", OfflineUnitSource.LEGACY_BASE, pcs) }
    }

    @Test
    fun `satuan pengganti dengan harga sekunder yang sama ditolak`() {
        // Dulu 12 x 10.000, sekarang 24 x 5.000.
        val now: (BigDecimal) -> BigDecimal = { BigDecimal("5000.00") }
        assertIs<OfflineUnitResolution.UnitConflict>(
            resolve("2", "120000", dusOf("24"), everUnits = dusOf("12"), everPrices = oldPrice, expected = now)
        )
        assertResolved(
            resolve("2", "120000", dusOf("24"), everUnits = dusOf("12"), expected = now),
            "48.00", "5000.00", OfflineUnitSource.LEGACY_AUTO, dus
        )
    }

    @Test
    fun `harga lama di luar jendela juga menolak faktor yang masih berlaku`() {
        // 60.000 = 12 x 5.000 (sekarang) atau 1 x 60.000 (harga lama satuan dasar).
        assertIs<OfflineUnitResolution.UnitConflict>(
            resolve("2", "60000", dusOf("12"), everPrices = setOf(BigDecimal("60000.00")), expected = { BigDecimal("5000.00") })
        )
        // Harga lama di dalam jendela dengan faktor lama di luar jendela.
        assertIs<OfflineUnitResolution.UnitConflict>(
            resolve("2", "120000", history = oldPrice, everUnits = dusOf("12"), expected = { BigDecimal("120000.00") })
        )
    }

    @Test
    fun `riwayat di luar jendela tidak pernah membuat harga atau faktor sah`() {
        assertIs<OfflineUnitResolution.PriceUnknown>(resolve("2", "120000", everUnits = dusOf("12")))
        assertIs<OfflineUnitResolution.PriceUnknown>(resolve("2", "7000", everPrices = setOf(BigDecimal("7000.00"))))
        assertIs<OfflineUnitResolution.PriceUnknown>(
            resolve("2", "0", everUnits = dusOf("12"), everPrices = setOf(BigDecimal.ZERO))
        )
        assertIs<OfflineUnitResolution.UnitConflict>(
            resolve("2", "120000", client = client(dus, "24"), everUnits = dusOf("12"))
        )
        assertIs<OfflineUnitResolution.PriceUnknown>(
            resolve("2", "7000", client = client(pcs, "2"), everPrices = setOf(BigDecimal("7000.00")))
        )
    }

    @Test
    fun `riwayat faktor dan harga yang sama tidak membuat satu tafsir menjadi ambigu`() {
        assertResolved(
            resolve("2", "120000", dusOf("12"), everUnits = dusOf("12.0000"), everPrices = oldPrice),
            "24.00", "10000.00", OfflineUnitSource.LEGACY_AUTO, dus
        )
        assertResolved(
            resolve("2", "10000", everUnits = dusOf("12"), everPrices = oldPrice),
            "2.00", "10000.00", OfflineUnitSource.LEGACY_BASE, pcs
        )
    }

    @Test
    fun `klien baru tidak terpengaruh riwayat di luar jendela`() {
        val free: (BigDecimal) -> BigDecimal = { BigDecimal.ZERO }
        assertResolved(
            resolve("2", "0", client = client(pcs, "2"), everUnits = dusOf("12"), expected = free),
            "2.00", "0.00", OfflineUnitSource.CLIENT_UNIT, pcs
        )
        assertResolved(
            resolve(
                "2", "120000", dusOf("24"), client(dus, "48"), everUnits = dusOf("12"), everPrices = oldPrice,
                expected = { BigDecimal("5000.00") }
            ),
            "48.00", "5000.00", OfflineUnitSource.CLIENT_UNIT, dus
        )
    }
}
