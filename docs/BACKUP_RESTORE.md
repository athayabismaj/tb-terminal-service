# Backup dan Restore

## Backup

Backup terjadwal aktif bila `BACKUP_ENABLED=true`. Interval dan retensi dikendalikan oleh `BACKUP_INTERVAL_HOURS` dan `BACKUP_RETENTION_DAYS`. File memakai format custom `pg_dump`, disimpan atomik, memiliki SHA-256, dan metadata/audit tersimpan di database. Salin backup secara terenkripsi ke media kedua dengan akses terbatas; jangan commit atau unggah ke bucket publik.

Owner/admin dapat membuat backup manual, melihat metadata, dan mengunduh file dari menu sistem/API. Request create mengembalikan `202 Accepted` dan job `PENDING`; poll `GET /api/system/database-backups/{id}` dengan jeda/backoff sampai `SUCCEEDED` atau `FAILED`. Setelah mengunduh, cocokkan checksum SHA-256 dengan metadata. Monitor job `FAILED`, usia backup sukses terakhir, dan kapasitas volume.

## Restore terawasi

Restore bersifat destruktif dan hanya dilakukan owner/admin dalam maintenance window:

1. Hentikan akses klien dan pastikan tidak ada transaksi berjalan.
2. Buat salinan eksternal terbaru dan verifikasi checksum.
3. Set `RESTORE_ENABLED=true`, recreate hanya container backend, lalu pastikan operator yang berwenang login.
4. Unggah dump melalui endpoint validasi. Server memeriksa ukuran, header custom PostgreSQL, manifest tabel inti TB Terminal, dan checksum.
5. Masukkan token sementara, frasa `RESTORE <job-id>`, dan konfirmasi downtime/overwrite. Token berlaku 10 menit, hanya disimpan di memory mobile, dan tidak boleh ditulis ke log/Room.
6. Confirm mengembalikan `202 Accepted`. Server membuat safety backup, melakukan restore secara asynchronous, lalu menyimpan kembali metadata safety-backup dan hasil restore. Poll status job; polling aman diulang, tetapi request confirm tidak boleh diulang ketika hasilnya ambigu.
7. Segera set `RESTORE_ENABLED=false`, recreate backend, cek `/ready`, login, laporan, stok, piutang, dan audit.

Jangan menjalankan restore bersamaan dengan checkout. Bila restore gagal setelah dimulai, hentikan aplikasi dan pulihkan safety backup dari volume menggunakan prosedur PostgreSQL standar; simpan log dan checksum sebagai bukti insiden.

Pada mobile, `Backup Lokal Perangkat` (Room) dan `Backup Database Server` (PostgreSQL) adalah dua fungsi berbeda. Pemilihan sumber/tujuan file server memakai Storage Access Framework dan transfer streaming. Seluruh regression restore wajib memakai PostgreSQL local/test terisolasi, bukan development bersama atau production.
