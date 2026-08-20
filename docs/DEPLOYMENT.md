# Instalasi dan Deployment Production

## Prasyarat

- Host Linux 64-bit dengan Docker Engine dan Compose v2.
- DNS production yang mengarah ke host dan sertifikat TLS valid (`fullchain.pem`, `privkey.pem`).
- Volume database dan backup berada pada disk privat, terenkripsi, dipantau kapasitasnya, dan tidak diekspos sebagai web root.
- Secret disimpan di secret manager atau file environment di luar repository dengan permission `600`.

## Konfigurasi wajib

Salin `deploy/production.env.example` ke lokasi aman di host, lalu isi `DB_PASSWORD`, `JWT_SECRET` acak minimal 32 karakter, `CORS_ALLOWED_ORIGINS` HTTPS, identitas toko, dan `TLS_CERT_DIR`. Jangan menaruh file hasil pengisian di repository.

Pada startup pertama saja, isi `BOOTSTRAP_OWNER_PASSWORD` (minimal 12 karakter, bukan password umum) dan `BOOTSTRAP_OWNER_PIN` (6 digit, bukan PIN umum). Setelah login owner berhasil, kosongkan kedua nilai dan recreate container backend. Startup production akan ditolak jika akun aktif masih memakai password/PIN bawaan atau identitas toko masih berupa data contoh.

## Deployment

```bash
chmod 600 /etc/tb-terminal/production.env
docker compose --env-file /etc/tb-terminal/production.env -f deploy/docker-compose.production.yml build
docker compose --env-file /etc/tb-terminal/production.env -f deploy/docker-compose.production.yml up -d
curl --fail https://pos.example.com/health
curl --fail https://pos.example.com/ready
```

`/health` adalah liveness aplikasi. `/ready` dan `/api/readiness` baru mengembalikan HTTP 200 jika query database berhasil; kegagalan database menghasilkan HTTP 503. Arahkan uptime monitor ke `/health`, readiness/orchestrator ke `/ready`, dan kumpulkan log JSON-like dari stdout backend serta access log Nginx berdasarkan `requestId`.

HTTPS dihentikan di Nginx; port backend dan PostgreSQL hanya berada pada network internal Compose. Nginx memberi HSTS, batas koneksi, rate limit API dan login. Rate limit login juga tetap diberlakukan di aplikasi.

## Upgrade dan rollback

1. Buat backup dan verifikasi checksum/download sebelum upgrade.
2. Bangun image dengan tag versi baru dan jalankan test pada staging.
3. Lakukan deployment saat tidak ada checkout aktif; tunggu `/ready` 200.
4. Untuk rollback binary, gunakan image sebelumnya. Jika migrasi database perlu dibatalkan, gunakan prosedur restore terawasi; jangan mengedit schema manual.

Artifact JVM lokal dibuat dengan `gradlew buildFatJar`; hasilnya `build/libs/tb-terminal-service-1.0.0-all.jar`.
