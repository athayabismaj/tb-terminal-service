# Troubleshooting

| Gejala | Pemeriksaan | Tindakan aman |
|---|---|---|
| `/health` gagal | container/backend process | lihat log berdasarkan waktu/requestId, restart bila tidak ada operasi restore |
| `/health` 200 tetapi `/ready` atau `/api/readiness` 503 | PostgreSQL, credential, pool, disk | pulihkan koneksi DB; jangan menerima checkout atau sync sebelum readiness 200 |
| Login 429 | terlalu banyak percobaan/IP | tunggu jendela rate limit; verifikasi credential, jangan mematikan proteksi |
| Aplikasi Android tidak terhubung | DNS/TLS, `PROD_BASE_URL`, jaringan | pastikan URL HTTPS release dan sertifikat dipercaya perangkat |
| Timeout checkout/pembayaran | jaringan atau backend lambat | cek histori lebih dulu; retry request yang sama agar idempotency key tetap digunakan |
| Stok dan kartu stok berbeda | rekonsiliasi ledger | hentikan transaksi produk terkait, ekspor bukti, eskalasi; jangan edit SQL langsung |
| Backup gagal | ruang disk, permission, `pg_dump` | perbaiki volume/tool, jalankan backup manual, verifikasi SHA-256 |
| Restore ditolak | flag maintenance, dump tidak kompatibel, token kedaluwarsa | gunakan dump TB Terminal yang tervalidasi dan ulangi tahap validasi |
| Confirm restore timeout/ambigu | jaringan terputus setelah job diterima | jangan auto-retry confirm; login ulang lalu poll/cari status job dan audit |
| Startup production ditolak | default credential/store atau env tidak aman | isi bootstrap/store secara aman; jangan mengubah guard di kode |

Log production tidak boleh memuat password/token. Saat mengirim bukti ke support, redaksi Authorization header, file environment, dump, dan data pelanggan.
