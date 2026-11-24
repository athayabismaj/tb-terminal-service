package com.service.tbterminal.sales

import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OfflineCheckoutGuardTest {

    private val retail = BigDecimal("10000.00")
    private val tierPrice = BigDecimal("9000.00")

    /** Harga eceran 10.000, grosir 9.000 mulai 10 satuan dasar. */
    private val expected: (BigDecimal) -> BigDecimal = { baseQty ->
        if (baseQty >= BigDecimal("10")) tierPrice else retail
    }

    @Test
    fun `harga eceran satuan dasar diterima dengan faktor 1`() {
        val factor = OfflineCheckoutGuard.matchUnitFactor(
            priceAtTransaction = BigDecimal("10000"),
            quantity = BigDecimal("2"),
            unitFactors = emptyList(),
            expectedBaseUnitPrice = expected,
            historicalBaseUnitPrices = emptySet()
        )
        assertEquals(0, BigDecimal.ONE.compareTo(factor))
    }

    @Test
    fun `harga grosir diterima hanya bila kuantitas memenuhi tier`() {
        assertNotNull(
            OfflineCheckoutGuard.matchUnitFactor(tierPrice, BigDecimal("10"), emptyList(), expected, emptySet())
        )
        assertNull(
            OfflineCheckoutGuard.matchUnitFactor(tierPrice, BigDecimal("2"), emptyList(), expected, emptySet())
        )
    }

    @Test
    fun `harga yang dimanipulasi kasir ditolak`() {
        assertNull(
            OfflineCheckoutGuard.matchUnitFactor(BigDecimal("1"), BigDecimal("5"), emptyList(), expected, emptySet())
        )
        assertNull(
            OfflineCheckoutGuard.matchUnitFactor(BigDecimal("0"), BigDecimal("5"), emptyList(), expected, emptySet())
        )
    }

    @Test
    fun `harga satuan sekunder dikenali lewat faktor konversi`() {
        // 1 dus = 12 pcs. 1 dus => 12 pcs memenuhi tier grosir (9.000 x 12).
        val factor = OfflineCheckoutGuard.matchUnitFactor(
            priceAtTransaction = BigDecimal("108000"),
            quantity = BigDecimal("1"),
            unitFactors = listOf(BigDecimal("12.0000")),
            expectedBaseUnitPrice = expected,
            historicalBaseUnitPrices = emptySet()
        )
        assertEquals(0, BigDecimal("12").compareTo(factor))
    }

    @Test
    fun `harga lama selama sesi masih diterima`() {
        val factor = OfflineCheckoutGuard.matchUnitFactor(
            priceAtTransaction = BigDecimal("9500"),
            quantity = BigDecimal("1"),
            unitFactors = emptyList(),
            expectedBaseUnitPrice = expected,
            historicalBaseUnitPrices = setOf(BigDecimal("9500.00"))
        )
        assertNotNull(factor)
    }

    @Test
    fun `pembulatan dibatasi kelipatan toko`() {
        assertNull(OfflineCheckoutGuard.validateRounding(BigDecimal("-400"), BigDecimal("12000"), 500, false))
        assertNotNull(OfflineCheckoutGuard.validateRounding(BigDecimal("-500"), BigDecimal("12000"), 500, false))
        assertNotNull(OfflineCheckoutGuard.validateRounding(BigDecimal("50"), BigDecimal("1000"), 0, false))
        assertNull(OfflineCheckoutGuard.validateRounding(BigDecimal("0"), BigDecimal("12345"), 0, false))
    }

    @Test
    fun `total hasil pembulatan wajib kelipatan unit pembulatan`() {
        // 12.370 dibulatkan ke 12.500: total kelipatan 500, selisih 130 < 500.
        assertNull(OfflineCheckoutGuard.validateRounding(BigDecimal("130"), BigDecimal("12500"), 500, false))
        // Total 1,00 bukan kelipatan unit mana pun, walau pengaturan berubah selama sesi.
        assertNotNull(OfflineCheckoutGuard.validateRounding(BigDecimal("-499"), BigDecimal("1"), 500, true))
    }

    @Test
    fun `pembulatan tidak boleh melebihi batas global walau pengaturan berubah`() {
        assertNull(OfflineCheckoutGuard.validateRounding(BigDecimal("900"), BigDecimal("13000"), 0, true))
        assertNotNull(OfflineCheckoutGuard.validateRounding(BigDecimal("-150000"), BigDecimal("0"), 1000, true))
    }

    @Test
    fun `snapshot pajak wajib sama dengan pengaturan toko bila pengaturan tidak berubah`() {
        assertNotNull(
            OfflineCheckoutGuard.validateTaxSnapshot(
                false, null, BigDecimal.ZERO,
                storeTaxEnabled = true, storeTaxMode = "EXCLUDE", storeTaxRate = BigDecimal("11.00"),
                settingsChangedDuringSession = false
            )
        )
        assertNull(
            OfflineCheckoutGuard.validateTaxSnapshot(
                true, "exclude", BigDecimal("11.00"),
                storeTaxEnabled = true, storeTaxMode = "EXCLUDE", storeTaxRate = BigDecimal("11.00"),
                settingsChangedDuringSession = false
            )
        )
        assertNull(
            OfflineCheckoutGuard.validateTaxSnapshot(
                false, null, BigDecimal.ZERO,
                storeTaxEnabled = true, storeTaxMode = "EXCLUDE", storeTaxRate = BigDecimal("11.00"),
                settingsChangedDuringSession = true
            )
        )
    }

    @Test
    fun `waktu transaksi wajib di dalam rentang sesi`() {
        val opened = OffsetDateTime.parse("2026-11-24T08:00:00+07:00")
        val now = OffsetDateTime.parse("2026-11-24T12:00:00+07:00")
        assertNull(OfflineCheckoutGuard.validateOccurredAt(opened.plusHours(1), opened, now))
        assertNotNull(OfflineCheckoutGuard.validateOccurredAt(opened.minusDays(3), opened, now))
        assertNotNull(OfflineCheckoutGuard.validateOccurredAt(now.plusDays(1), opened, now))
    }

    @Test
    fun `waktu buka sesi wajib dekat dengan waktu server`() {
        val now = OffsetDateTime.parse("2026-11-24T12:00:00+07:00")
        assertNull(OfflineCheckoutGuard.validateOpenedAt(now, now))
        assertNull(OfflineCheckoutGuard.validateOpenedAt(now.plusMinutes(5), now))
        assertNotNull(OfflineCheckoutGuard.validateOpenedAt(now.plusMinutes(5).plusSeconds(1), now))
        assertNull(OfflineCheckoutGuard.validateOpenedAt(now.minusHours(24), now))
        assertNotNull(OfflineCheckoutGuard.validateOpenedAt(now.minusHours(24).minusSeconds(1), now))
    }

    @Test
    fun `awal jendela tepercaya tidak lebih tua dari 24 jam sebelum sesi tercatat di server`() {
        val recordedAt = OffsetDateTime.parse("2026-11-24T12:00:00+07:00")
        assertEquals(
            recordedAt.minusHours(3),
            OfflineCheckoutGuard.trustedWindowStart(recordedAt.minusHours(3), recordedAt)
        )
        assertEquals(
            recordedAt.minusHours(24),
            OfflineCheckoutGuard.trustedWindowStart(recordedAt.minusDays(30), recordedAt)
        )
    }

    @Test
    fun `semua pesan konflik offline memuat frasa business rule tepat sekali`() {
        OfflineConflictCode.entries.forEach { code ->
            listOf("detail biasa", "Pelanggaran business rule pajak: detail").forEach { detail ->
                val error = offlineConflict(code, detail)
                assertEquals(code.name, error.code)
                assertEquals(1, Regex("business rule").findAll(error.message.orEmpty()).count(), error.message)
            }
        }
    }
}
