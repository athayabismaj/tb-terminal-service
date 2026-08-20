# Panduan Ringkas Owner, Admin, dan Kasir

## Owner

Owner mengelola pengguna/role, konfigurasi toko, produk dan saldo awal, laporan, audit, backup/restore, void, serta operasi piutang. Ganti password dan PIN bootstrap pada login pertama. Adjustment piutang positif wajib mempunyai alasan dan referensi; koreksi pembayaran memakai reversal, bukan penghapusan.

## Admin

Admin menangani operasional yang diizinkan seperti produk, laporan, void, adjustment piutang, pembayaran, dan backup sesuai kebijakan toko. Admin tidak dapat mengelola akun sistem yang dilindungi owner. Restore hanya dilakukan pada maintenance window dengan persetujuan owner.

## Kasir

Kasir login dengan akun pribadi, membuka sesi kas dengan saldo fisik yang benar, melakukan checkout sekali, menunggu hasil request, mencetak struk, menerima pembayaran piutang, lalu menutup sesi setelah rekonsiliasi. Jangan berbagi PIN atau menggunakan akun owner. Jika terjadi timeout checkout/pembayaran, cari nomor/idempotency transaksi pada histori sebelum mencoba ulang; aplikasi menggunakan kunci idempotensi untuk mencegah duplikasi.

## Kontrol harian

- Awal hari: `/ready` sehat, printer tersedia, sesi kas sesuai petugas.
- Pergantian shift: tutup sesi lama dan cocokkan kas fisik.
- Akhir hari: cocokkan laporan aktif/void, pembayaran, sisa piutang, dan saldo kartu stok; pastikan backup terakhir sukses.

## Batas offline dan backup

Hanya transaksi, sesi kas, dan pengeluaran kas yang dapat masuk antrean offline. Master produk/pelanggan, stok, piutang, pembayaran, laporan, audit, dan backup server memerlukan `/api/readiness` sehat. Backup lokal perangkat hanya mencakup Room; backup database server mencakup PostgreSQL. Jangan mengulang confirm restore saat respons timeout/ambigu—periksa status job setelah login ulang.
