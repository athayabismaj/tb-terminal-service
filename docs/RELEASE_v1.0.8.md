# Runbook Rilis v1.0.8

Runbook ini menaikkan production dari backend lama + APK v1.0.6 ke **backend `main` (5b, V47) + APK 1.0.8**,
melalui backend v1.0.7 sebagai tahap antara. Urutan ini wajib (lihat `API_CHANGES.md` › "Urutan rilis"):
backend 5b tidak boleh aktif selama masih ada HP v1.0.6.

Semua perintah dijalankan **oleh operator** di server production, termasuk uji restore (A5) dan deteksi (F) yang
memakai container sekali pakai tanpa jaringan. Laptop hanya dipakai untuk menyimpan salinan backup dan CSV (lewat
`scp`); laptop tidak perlu Docker. Perintah ditulis untuk file di `deploy/` apa adanya.

## Checklist singkat

| # | Langkah | Selesai |
|---|---|---|
| A1 | Variabel shell disiapkan; `dc0 ps` menampilkan stack yang **sedang berjalan** (bukan stack baru) | ☐ |
| A2 | Semua HP sync, sesi kas ditutup, tidak ada checkout berjalan | ☐ |
| A3 | Backup `pg_dump -Fc` dibuat, SHA-256 dicatat, `pg_restore -l` lolos, salinan di luar server | ☐ |
| A4 | Commit, image, dan riwayat migrasi yang sedang berjalan dicatat (`state-before-1.0.7.txt`) | ☐ |
| A5 | **WAJIB:** `BACKUP_A` di-restore ke container sekali pakai di server; versi migrasi dan jumlah baris = `*.counts` | ☐ |
| B1 | `OFFLINE_MAX_SESSION_AGE_HOURS=72` di file env; override `APP_VERSION=1.0.7` + image `1.0.7` | ☐ |
| B2 | Repo server di-checkout ke `v1.0.7` = `14be847c` (fetch + checkout, **bukan** `git pull`) | ☐ |
| B3 | Build + recreate backend, restart gateway; `/health` versi `1.0.7`, `/ready` 200 | ☐ |
| B4 | V45 dan V46 `success = t` di `system.flyway_schema_history`, log tanpa ERROR | ☐ |
| B5 | Semua user login ulang; uji 1 checkout online + 1 sync offline | ☐ |
| C1 | Semua HP diperbarui ke APK 1.0.8 (**update**, jangan uninstall); login menampilkan `v1.0.8` | ☐ |
| C2 | Antrean sync tiap HP kosong; konflik dicatat, tidak dihapus | ☐ |
| D1 | Backup kedua (sebelum V47) dibuat dan diverifikasi | ☐ |
| D2 | Checkout commit `main` yang disetujui; override `APP_VERSION=1.0.8` + image `1.0.8`; build + recreate | ☐ |
| D3 | V47 `success = t`; `inventory.unit_conversion_history` ada; `/health` versi `1.0.8` | ☐ |
| D4 | `GET /api/inventory/stock` memuat `baseUnitId`/`baseUnitName` (dan `secondaryUnit*` bila ada) | ☐ |
| E | Tag `v1.0.8` di repo backend dan Android (oleh pemilik/Antigravity) | ☐ |
| F | Backup terbaru di-restore ke container sekali pakai di server; dua skrip deteksi dijalankan; CSV disalin ke laptop | ☐ |
| G | Rencana rollback B dan D dibaca sebelum mulai | ☐ |

## Hal yang perlu diketahui sebelum mulai

1. **`APP_VERSION` di compose ditulis tetap `1.0.0`** (`deploy/docker-compose.production.yml`, baris
   `APP_VERSION: 1.0.0`, bukan `${APP_VERSION}`). Menaruh `APP_VERSION=1.0.7` di file env **tidak berpengaruh**.
   Runbook ini memakai file override lokal di server (`compose.release.yml`, di luar repo) untuk mengisi
   `APP_VERSION` dan tag image. File di repo tidak diubah, jadi checkout git tidak menimpanya.
2. **Tag image juga tetap** `tb-terminal-service:1.0.0`. Tanpa override, setiap build menimpa image lama dan rollback
   binary jadi tidak mungkin. Override di bawah memberi tag per versi, dan langkah A4 menandai image yang sedang jalan.
3. **`LOG_LEVEL=WARN` di production**: pesan sukses Flyway (`Flyway: Successfully applied ...`, level INFO) tidak
   muncul di log. Migrasi diverifikasi lewat tabel `system.flyway_schema_history`, bukan lewat log. Log tetap dipakai
   untuk mencari ERROR.
4. **Riwayat git pernah di-force-push.** Jangan `git pull` di server (bisa membuat merge commit atau gagal karena
   riwayat bercabang). Pakai `git fetch --force` lalu checkout hash/tag yang sudah diverifikasi.
5. **Migrasi tidak bisa dibalik otomatis.** Tidak ada skrip "undo" untuk V45, V46, atau V47. Kembali ke skema lama
   berarti restore dari backup (bagian G), dan data yang masuk setelah backup itu hilang.
6. **Waktu pelaksanaan:** di luar jam buka, setelah semua sesi kas ditutup. Build image di server butuh internet
   (Gradle, apt) dan beberapa menit; backend hanya mati saat container dibuat ulang.
7. Nama service di compose: `postgres`, `backend`, `gateway`. Volume: `postgres-data` (database),
   `backup-data` (backup otomatis aplikasi). Runbook memakai nama service lewat `docker compose`, bukan nama container.
   Compose production tidak menulis `name:`, jadi nama project bawaan = nama folder file compose (`deploy`), dan
   volume database bernama `deploy_postgres-data`, kecuali stack pernah dijalankan dengan `-p`/`COMPOSE_PROJECT_NAME`
   lain atau dari folder lain. Langkah A1 memeriksa hal ini.
8. **Versi Flyway tidak selalu bilangan bulat** (ada `16.1`, dari `V16_1__seed_data_complete.sql`). Jangan pakai
   `version::int` atau `MAX(version)`; urutkan dengan `installed_rank`.

---

## A. Persiapan

### A1. Variabel shell

Jalankan di **setiap shell baru** di server. Ganti nilai bertanda `GANTI`.

```bash
cd /opt/tb-terminal-service                        # GANTI: lokasi clone repo backend di server
export ENV_FILE=/etc/tb-terminal/production.env    # file env production (lihat DEPLOYMENT.md)
export RELEASE_FILE=/etc/tb-terminal/compose.release.yml
export BASE_URL=https://pos.example.com            # GANTI: domain production
export BACKUP_DIR=/var/backups/tb-terminal/release-1.0.8   # GANTI bila perlu: disk privat, di luar repo

sudo mkdir -p "$BACKUP_DIR" && sudo chown "$(id -u):$(id -g)" "$BACKUP_DIR" && chmod 700 "$BACKUP_DIR"

# compose TANPA override (dipakai di A, sebelum file override dibuat)
dc0() { docker compose --env-file "$ENV_FILE" -f deploy/docker-compose.production.yml "$@"; }
# compose DENGAN override versi (dipakai mulai B)
dc()  { docker compose --env-file "$ENV_FILE" -f deploy/docker-compose.production.yml -f "$RELEASE_FILE" "$@"; }

# Query pembanding backup vs hasil restore (A3, A5, F2). Versi diurutkan per installed_rank (ada versi 16.1).
export COUNTS_SQL="
SELECT 'transactions', COUNT(*) FROM sales.transactions
UNION ALL SELECT 'cash_sessions', COUNT(*) FROM sales.cash_sessions
UNION ALL SELECT 'products', COUNT(*) FROM inventory.products
UNION ALL SELECT 'users', COUNT(*) FROM system.users;"
export LAST_MIGRATION_SQL="
SELECT version FROM system.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;"

dc0 ps
```

`dc0 ps` harus menampilkan `postgres`, `backend`, `gateway` berstatus `running (healthy)`.

> **BERHENTI bila `postgres`, `backend`, atau `gateway` TIDAK tampil running** (daftar kosong atau sebagian).
> Artinya stack production kemungkinan dijalankan dengan **nama project atau folder lain**. Perintah `dc`/`dc0`
> berikutnya (`exec` akan gagal, tetapi `up`/`build` di B dan D) akan membuat **stack dan volume database BARU yang
> kosong** di samping yang lama: aplikasi tampak jalan tanpa data, dan backup di A3 akan berisi database kosong.
> Jangan lanjut sebelum penyebabnya jelas.

Cara mengecek nama project dan volume yang sebenarnya:

```bash
docker ps --format '{{.Names}} {{.Label "com.docker.compose.project"}}'   # kolom 2 = nama project
docker compose ls                                                         # project + file compose yang dipakai
docker volume ls | grep -E 'postgres-data|backup-data'                    # <project>_postgres-data
```

Bila project-nya bukan `deploy` (mis. `tb-terminal-service`), set nama itu lalu ulangi `dc0 ps`:

```bash
export COMPOSE_PROJECT_NAME=tb-terminal-service    # GANTI: kolom 2 dari docker ps di atas
dc0 ps                                             # sekarang harus menampilkan ketiga service running
```

`COMPOSE_PROJECT_NAME` dibaca otomatis oleh `docker compose`, jadi `dc0` dan `dc` ikut memakainya; tambahkan baris
`export` itu ke blok variabel di atas untuk setiap shell baru. (Alternatif: tambahkan `-p <nama>` di kedua fungsi.)
Bila `docker volume ls` menampilkan lebih dari satu `*_postgres-data`, tanyakan pemilik mana yang dipakai production
sebelum lanjut.

### A2. Hentikan aktivitas kasir

1. Minta setiap kasir membuka aplikasi saat online, tekan **Sinkronkan Semua**, dan pastikan muncul
   "Tidak ada transaksi offline yang perlu disinkronkan." serta tidak ada penanda "• N belum sinkron".
2. Tutup semua sesi kas. Cek dari server bahwa tidak ada sesi yang masih terbuka:

```bash
dc0 exec -T postgres psql -U tb_terminal -d tb_terminal -c "
SELECT cs.id, u.username, cs.opened_at
FROM sales.cash_sessions cs JOIN system.users u ON u.id = cs.user_id
WHERE cs.closed_at IS NULL ORDER BY cs.opened_at;"
```

Hasil yang diharapkan: `(0 rows)`. Bila ada, tutup dulu dari HP kasir yang bersangkutan.

3. Sync terakhir per perangkat (pembanding untuk langkah C2):

```bash
dc0 exec -T postgres psql -U tb_terminal -d tb_terminal -c "
SELECT device_id, COUNT(*) AS trx_offline, MAX(synced_at) AS sync_terakhir
FROM sales.transactions WHERE device_id IS NOT NULL
GROUP BY device_id ORDER BY sync_terakhir DESC;"
```

### A3. Backup database production

```bash
export TS=$(date +%Y%m%d-%H%M%S)
export BACKUP_A="$BACKUP_DIR/tb_terminal_pre-1.0.7_$TS.dump"

# -T wajib: tanpa TTY agar file biner tidak rusak
dc0 exec -T postgres pg_dump -U tb_terminal -d tb_terminal -Fc > "$BACKUP_A"
echo "exit pg_dump: $?"     # harus 0

chmod 600 "$BACKUP_A"
sha256sum "$BACKUP_A" | tee "$BACKUP_A.sha256"
ls -lh "$BACKUP_A"
```

**Verifikasi backup:**

```bash
# 1. Checksum cocok
sha256sum -c "$BACKUP_A.sha256"

# 2. File bisa dibaca pg_restore dan memuat data tabel inti (container sekali pakai, tidak menyentuh DB production)
docker run --rm -v "$BACKUP_DIR":/b:ro postgres:16-alpine \
  pg_restore -l "/b/$(basename "$BACKUP_A")" > "$BACKUP_A.toc"
grep -cE 'TABLE DATA' "$BACKUP_A.toc"
grep -E 'TABLE DATA (system users|system flyway_schema_history|inventory products|inventory stock|sales transactions|sales cash_sessions)' "$BACKUP_A.toc"

# 3. Jumlah baris + migrasi terakhir di production saat backup (dibandingkan di A5 dan F2).
#    Kasir sudah berhenti (A2), jadi angka ini sama dengan isi backup. COUNTS_SQL/LAST_MIGRATION_SQL dari A1.
dc0 exec -T postgres psql -U tb_terminal -d tb_terminal -At -F ' ' -c "$COUNTS_SQL" | tee "$BACKUP_A.counts"
dc0 exec -T postgres psql -U tb_terminal -d tb_terminal -At -c "$LAST_MIGRATION_SQL" | tee "$BACKUP_A.migration"
```

Kriteria lolos: `exit pg_dump: 0`, ukuran file wajar (bukan beberapa KB), `sha256sum -c` → `OK`,
keenam baris `TABLE DATA` di atas muncul. Ini baru memeriksa bahwa file terbaca; bukti bahwa backup bisa dipulihkan
adalah uji restore penuh di **A5 (wajib sebelum B)**.

**Salin ke luar server** (dari laptop operator, lalu simpan di media terenkripsi):

```bash
# di laptop
scp 'user@server:/var/backups/tb-terminal/release-1.0.8/tb_terminal_pre-1.0.7_*' ~/tb-backup/
cd ~/tb-backup && sha256sum -c tb_terminal_pre-1.0.7_*.sha256
```

### A4. Catat keadaan yang sedang berjalan (untuk rollback)

```bash
export STATE_A="$BACKUP_DIR/state-before-1.0.7.txt"
{
  echo "== waktu";            date -Is
  echo "== git HEAD";         git rev-parse HEAD
  echo "== git describe";     git describe --tags --always
  echo "== git branch";       git rev-parse --abbrev-ref HEAD
  echo "== git status";       git status --short
  echo "== image backend";    docker inspect --format '{{.Image}} {{.Config.Image}}' "$(dc0 ps -q backend)"
  echo "== /health";          curl -fsS "$BASE_URL/health"; echo
  echo "== migrasi terakhir"
  dc0 exec -T postgres psql -U tb_terminal -d tb_terminal -c \
    "SELECT installed_rank, version, description, success, installed_on
     FROM system.flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
} | tee "$STATE_A"

# Tandai image yang sedang berjalan agar tidak tertimpa build berikutnya
docker tag "$(docker inspect --format '{{.Image}}' "$(dc0 ps -q backend)")" tb-terminal-service:rollback-pre-1.0.7
docker image ls tb-terminal-service
```

- Bila `git status --short` **tidak kosong**, ada perubahan lokal di server. Simpan dulu
  (`git diff > "$BACKUP_DIR/server-local.diff"`) dan laporkan ke pemilik sebelum lanjut, karena checkout berikutnya
  bisa menimpanya.
- Simpan juga salinan file env: `sudo cp -p "$ENV_FILE" "$BACKUP_DIR/production.env.pre-1.0.7"` (permission 600).

### A5. Uji restore `BACKUP_A` (WAJIB sebelum B)

Rollback B (G1) bergantung sepenuhnya pada file ini, jadi file harus terbukti bisa dipulihkan **sebelum** migrasi
V45/V46 berjalan. Uji dilakukan di server dengan container sekali pakai: tanpa jaringan (`--network none`), volume
sementara sendiri, tidak memakai stack compose, tidak menyentuh database production.

```bash
: "${BACKUP_A:?BACKUP_A kosong: jalankan di shell yang sama dengan A3}" "${TS:?}" "${COUNTS_SQL:?jalankan A1}"

# Ruang disk: restore butuh kira-kira ukuran DB production (lebih besar dari file .dump)
df -h /var/lib/docker
dc0 exec -T postgres psql -U tb_terminal -d tb_terminal -At -c "SELECT pg_size_pretty(pg_database_size('tb_terminal'));"

export RT_NAME="tb-restore-a5-$TS"         # nama unik; juga dipakai untuk volume sementara
docker volume create "$RT_NAME"
docker run -d --rm --name "$RT_NAME" --network none \
  -e POSTGRES_PASSWORD="$(openssl rand -hex 16)" -e POSTGRES_DB=tb_restore \
  -v "$RT_NAME":/var/lib/postgresql/data \
  -v "$BACKUP_DIR":/b:ro \
  postgres:16-alpine
# entrypoint menjalankan postgres sementara saat init lalu me-restart-nya; tunggu init selesai dulu
until docker logs "$RT_NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 2; done
until docker exec "$RT_NAME" pg_isready -U postgres -d tb_restore >/dev/null 2>&1; do sleep 2; done

docker exec "$RT_NAME" pg_restore -U postgres -d tb_restore --no-owner --no-privileges --exit-on-error \
  "/b/$(basename "$BACKUP_A")"
echo "exit pg_restore: $?"     # harus 0

# Versi migrasi dan jumlah baris harus SAMA PERSIS dengan production saat backup (A3)
docker exec "$RT_NAME" psql -U postgres -d tb_restore -At -c "$LAST_MIGRATION_SQL" \
  | diff - "$BACKUP_A.migration" && echo "migrasi COCOK"
docker exec "$RT_NAME" psql -U postgres -d tb_restore -At -F ' ' -c "$COUNTS_SQL" \
  | diff - "$BACKUP_A.counts" && echo "jumlah baris COCOK"

# Bersihkan: container (--rm) dan volume sementara
docker stop "$RT_NAME"
docker volume rm "$RT_NAME"
docker ps -a --filter "name=$RT_NAME" --format '{{.Names}}'; docker volume ls -q --filter "name=$RT_NAME"   # keduanya kosong
echo "A5 lolos $(date -Is): $(basename "$BACKUP_A")" >> "$BACKUP_DIR/log-rilis.txt"
```

Kriteria lolos: `exit pg_restore: 0`, `migrasi COCOK`, `jumlah baris COCOK`. Bila salah satu gagal, **jangan lanjut
ke B**: buat ulang backup (A3) dan ulangi A5. `--no-owner --no-privileges` hanya membuat restore uji tidak bergantung
pada role `tb_terminal`; pemulihan sungguhan (G) memakai role production.

Bila sesi terputus di tengah, bersihkan sisa dengan `docker rm -f "$RT_NAME"; docker volume rm "$RT_NAME"`
(nama bisa dilihat di `docker ps -a --filter name=tb-restore-`).

---

## B. Deploy backend v1.0.7 (tag `v1.0.7`)

Prasyarat: A2–A5 selesai. HP masih memakai APK lama; backend v1.0.7 kompatibel dengan v1.0.6.

### B1. Konfigurasi

```bash
# OFFLINE_MAX_SESSION_AGE_HOURS=72 di file env (ubah bila ada, tambahkan bila belum)
if sudo grep -q '^OFFLINE_MAX_SESSION_AGE_HOURS=' "$ENV_FILE"; then
  sudo sed -i 's/^OFFLINE_MAX_SESSION_AGE_HOURS=.*/OFFLINE_MAX_SESSION_AGE_HOURS=72/' "$ENV_FILE"
else
  echo 'OFFLINE_MAX_SESSION_AGE_HOURS=72' | sudo tee -a "$ENV_FILE" > /dev/null
fi
sudo grep '^OFFLINE_MAX_SESSION_AGE_HOURS=' "$ENV_FILE"

# Override versi: APP_VERSION + tag image per versi (file di luar repo)
sudo tee "$RELEASE_FILE" > /dev/null <<'EOF'
services:
  backend:
    image: tb-terminal-service:1.0.7
    environment:
      APP_VERSION: "1.0.7"
EOF
sudo chmod 644 "$RELEASE_FILE"
```

### B2. Checkout `v1.0.7`

```bash
git status --short                       # harus kosong (lihat A4)
git fetch --force --prune --tags origin  # --force: tag/branch yang pernah dipindah ikut diperbarui
git rev-parse 'v1.0.7^{commit}'          # HARUS: 14be847c448bb06585ecd6bbf8317a0008e210f9
git checkout --detach v1.0.7
git rev-parse HEAD                       # HARUS: 14be847c448bb06585ecd6bbf8317a0008e210f9
grep -n 'OFFLINE_MAX_SESSION_AGE_HOURS' deploy/docker-compose.production.yml   # harus ada 1 baris
```

Bila hash berbeda, **berhenti** dan tanyakan pemilik repo: tag di remote tidak sama dengan yang diuji.

### B3. Build dan restart

```bash
dc config --quiet && echo "compose OK"
dc build backend
dc up -d --no-deps backend
dc ps backend                            # tunggu sampai "healthy" (start_period 30 dtk, cek tiap 30 dtk)

# Nginx menyimpan alamat container lama; restart gateway setelah backend healthy
dc restart gateway

curl -fsS "$BASE_URL/health"; echo       # "version":"1.0.7"
curl -fsS "$BASE_URL/ready";  echo       # HTTP 200, "database":"up"
dc exec backend printenv APP_VERSION OFFLINE_MAX_SESSION_AGE_HOURS   # 1.0.7 dan 72
```

Bila backend tidak pernah `healthy` dalam ±3 menit, lihat log (B4) lalu putuskan rollback (G1).

### B4. Migrasi V45–V46 dan log

```bash
dc exec -T postgres psql -U tb_terminal -d tb_terminal -c "
SELECT installed_rank, version, description, success, installed_on
FROM system.flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;"
```

Harus ada baris `45 | refresh token rotation and credential scopes | t` dan
`46 | store settings change timestamps | t` dengan `installed_on` hari ini. Cek objek skemanya:

```bash
dc exec -T postgres psql -U tb_terminal -d tb_terminal -At -c "
SELECT to_regclass('system.refresh_tokens') IS NOT NULL AS v45_refresh_tokens,
       (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = 'system' AND table_name = 'store_settings'
          AND column_name IN ('tax_settings_updated_at','rounding_settings_updated_at')) AS v46_kolom;"
# diharapkan: t|2

dc logs --since 15m backend | grep -iE 'error|exception|flyway|migrat' || echo "tidak ada ERROR"
```

Dengan `LOG_LEVEL=WARN`, migrasi yang sukses tidak menulis log. Kegagalan migrasi muncul sebagai ERROR/exception
dan container restart berulang.

### B5. Login ulang dan uji singkat

- Refresh token lama (tanpa `jti`) ditolak sejak V45: **semua user wajib login ulang sekali** di setiap HP.
  Pesan yang muncul di HP: "Sesi telah berakhir. Silakan login kembali." — ini normal.
- Uji di satu HP: login, buka sesi kas, 1 checkout online, lalu mode pesawat → 1 checkout offline → online →
  sinkronkan. Keduanya harus berhasil. Tutup sesi kas uji (atau batalkan transaksi uji sesuai prosedur toko).
- Catat hasilnya: `echo "B selesai $(date -Is), HEAD $(git rev-parse HEAD)" >> "$BACKUP_DIR/log-rilis.txt"`

---

## C. Update semua HP ke APK 1.0.8

APK 1.0.8 (merge B1c di repo Android, `versionName "1.0.8"`, `applicationId com.tbterminal.app`) kompatibel
dengan backend v1.0.7: bila `baseUnitId` tidak ada di respons stok, aplikasi memakai `/products`.

Per HP:

1. **Sebelum update:** online, **Sinkronkan Semua**, pastikan "Tidak ada transaksi offline yang perlu
   disinkronkan." dan tidak ada "• N belum sinkron". Sesi kas di HP ditutup.
2. Pasang APK 1.0.8 sebagai **update** di atas aplikasi lama. **Jangan uninstall** aplikasi lama: uninstall
   menghapus database lokal (Room), termasuk antrean yang belum terkirim. Bila Android menolak update karena tanda
   tangan berbeda, berhenti dan hubungi pengembang; jangan uninstall.
3. **Cek versi:**
   - layar login aplikasi menampilkan `v1.0.8` (pojok bawah), atau
   - Pengaturan Android › Aplikasi › TB Terminal › versi `1.0.8`, atau
   - via USB: `adb shell dumpsys package com.tbterminal.app | grep versionName` → `versionName=1.0.8`.
4. Login, lalu buka status sync: penanda "belum sinkron" tidak ada.
5. Bila sync menghasilkan "Sebagian transaksi gagal disinkronkan": buka detail error, foto/catat
   (nomor transaksi, kode error), **jangan hapus** item konflik. Laporkan ke pemilik sebelum lanjut ke D.

Catat per HP:

| HP / kasir | Versi di layar login | Antrean kosong | Konflik (catat) | Petugas |
|---|---|---|---|---|
| | v1.0.8 | ☐ | | |
| | v1.0.8 | ☐ | | |

Pembanding dari server (sync terakhir per perangkat harus mendekati waktu langkah ini, untuk HP yang melakukan
transaksi offline):

```bash
dc exec -T postgres psql -U tb_terminal -d tb_terminal -c "
SELECT device_id, MAX(synced_at) AS sync_terakhir FROM sales.transactions
WHERE device_id IS NOT NULL GROUP BY device_id ORDER BY sync_terakhir DESC;"
```

**Jangan lanjut ke D selama masih ada HP v1.0.6** (alasan: `SECURITY_HARDENING.md` › "Urutan rilis backend 5b").

---

## D. Deploy backend `main` (5b, V47), APP_VERSION 1.0.8

Prasyarat: semua HP `v1.0.8`, antrean kosong, sesi kas ditutup.

### D1. Backup kedua (sebelum V47)

```bash
export TS=$(date +%Y%m%d-%H%M%S)
export BACKUP_D="$BACKUP_DIR/tb_terminal_pre-1.0.8_$TS.dump"
dc exec -T postgres pg_dump -U tb_terminal -d tb_terminal -Fc > "$BACKUP_D"; echo "exit pg_dump: $?"
chmod 600 "$BACKUP_D"
sha256sum "$BACKUP_D" | tee "$BACKUP_D.sha256"
docker run --rm -v "$BACKUP_DIR":/b:ro postgres:16-alpine pg_restore -l "/b/$(basename "$BACKUP_D")" \
  | grep -E 'TABLE DATA (system refresh_tokens|sales transactions|inventory products)'

# keadaan v1.0.7 untuk rollback D
{ date -Is; git rev-parse HEAD; docker inspect --format '{{.Image}} {{.Config.Image}}' "$(dc ps -q backend)"; } \
  | tee "$BACKUP_DIR/state-before-1.0.8.txt"
```

Salin `$BACKUP_D` dan `.sha256` ke luar server seperti di A3.

### D2. Checkout `main` dan deploy

Minta pemilik repo menyebutkan **hash commit `main` yang disetujui untuk rilis** (saat runbook ini ditulis,
`main` = `a368d85ec8bee7c4cda9418fc0af91cb586e79ee` ditambah commit dokumen ini bila sudah di-commit).

```bash
export MAIN_COMMIT=<hash-dari-pemilik>   # GANTI

git status --short                       # harus kosong
git fetch --force --prune --tags origin
git rev-parse origin/main                # harus sama dengan $MAIN_COMMIT
git checkout --detach "$MAIN_COMMIT"
git rev-parse HEAD
ls src/main/resources/db/migration/V47__unit_conversion_history.sql

sudo tee "$RELEASE_FILE" > /dev/null <<'EOF'
services:
  backend:
    image: tb-terminal-service:1.0.8
    environment:
      APP_VERSION: "1.0.8"
EOF

dc config --quiet && echo "compose OK"
dc build backend
dc up -d --no-deps backend
dc ps backend                            # tunggu "healthy"
dc restart gateway

curl -fsS "$BASE_URL/health"; echo       # "version":"1.0.8"
curl -fsS "$BASE_URL/ready";  echo       # HTTP 200
```

Image `tb-terminal-service:1.0.7` tetap ada untuk rollback D (`docker image ls tb-terminal-service`).

### D3. Migrasi V47

```bash
dc exec -T postgres psql -U tb_terminal -d tb_terminal -c "
SELECT installed_rank, version, description, success, installed_on
FROM system.flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
# harus ada: 47 | unit conversion history | t

dc exec -T postgres psql -U tb_terminal -d tb_terminal -At -c "
SELECT to_regclass('inventory.unit_conversion_history') IS NOT NULL,
       EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_unit_conversions_history');"
# diharapkan: t|t

dc logs --since 15m backend | grep -iE 'error|exception|flyway|migrat' || echo "tidak ada ERROR"
```

### D4. `GET /api/inventory/stock` memuat field satuan baru

Login sebagai owner/admin dari server (password tidak masuk riwayat shell; password yang memuat `"` atau `\`
perlu di-escape manual). Rate limit login: 5/menit/IP.

Kontrak yang dipakai (dicek di kode `main`):

- `POST /api/auth/login` menerima `{"username","password"}` (`system/SystemModel.kt:9-12`) dan membalas
  `ApiResponse<LoginResponse>`; access token ada di field **`data.token`**, bukan `accessToken`
  (`system/SystemModel.kt:36-40`: `token`, `refreshToken`, `user`; diisi di `system/SystemService.kt:57`, dikirim
  di `system/SystemRoutes.kt:26-41`). JSON dikirim ringkas tanpa spasi (`plugins/Serialization.kt:11-13`), jadi
  pola `"token":"` di bawah cocok, dan tidak tertukar dengan `"refreshToken":"` (huruf `T` besar).
- `GET /api/inventory/stock` (`inventory/InventoryRoutes.kt:16-20`, `inventory/InventoryStockRoutes.kt:19-25`)
  memakai paginasi **`page`** (mulai 1, bawaan 1) dan **`limit`** (bawaan 20, tidak ada batas atas;
  `inventory/InventoryService.kt:323-327`), plus `search` opsional. Bukan `size`/`perPage`/`offset`.
  Item ada di `data.data[]`, dengan `total`, `page`, `limit`, `totalPages` (`inventory/InventoryModels.kt:180-186`).
- Tiap item (`StockDetailResponse`, `inventory/InventoryModels.kt:244`) punya `productId` (`:245`). Field satuan: `baseUnitId`, `baseUnitName` (selalu ada), `secondaryUnitId`, `secondaryUnitName`,
  `secondaryUnitFactor` (`inventory/InventoryModels.kt:259-264`). Field sekunder **tidak muncul sama sekali** bila
  null (kotlinx `encodeDefaults` bawaan = false). Faktor dikirim sebagai string tanpa nol di belakang, mis. `"12"`
  (`shared/Extensions.kt:71-73`, `inventory/ProductUnitLookup.kt:47`).

```bash
read -rp 'Username: ' TB_USER; read -rsp 'Password: ' TB_PASS; echo
TOKEN=$(printf '{"username":"%s","password":"%s"}' "$TB_USER" "$TB_PASS" \
  | curl -fsS -H 'Content-Type: application/json' --data-binary @- "$BASE_URL/api/auth/login" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
unset TB_PASS
[ -n "$TOKEN" ] && echo "login OK" || echo "login GAGAL"

STOCK_JSON=$(mktemp)
curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/inventory/stock?page=1&limit=50" > "$STOCK_JSON"
grep -o '"productId":"[^"]*"' "$STOCK_JSON" | wc -l                 # jumlah item di halaman (maks 50)
grep -o '"baseUnitId":"[^"]*"' "$STOCK_JSON" | wc -l                # harus SAMA dengan baris di atas, > 0
grep -o '"baseUnitName":"[^"]*"' "$STOCK_JSON" | wc -l              # harus SAMA juga
grep -oE '"(secondaryUnitId|secondaryUnitName|secondaryUnitFactor)":"[^"]*"' "$STOCK_JSON" | head -12
grep -oE '"(total|totalPages)":[0-9]+' "$STOCK_JSON"                # bila totalPages > 1, ulangi dengan page=2, dst.
rm -f "$STOCK_JSON"; unset TOKEN STOCK_JSON
```

Kriteria lolos: setiap item punya `baseUnitId` dan `baseUnitName`; produk yang punya satuan sekunder juga punya
`secondaryUnitId`, `secondaryUnitName`, `secondaryUnitFactor` (string desimal, mis. `"12"`).

Uji di satu HP 1.0.8: checkout online produk bersatuan sekunder (mis. 1 dus) → stok berkurang sesuai faktor;
checkout offline satuan sekunder → sync berhasil. Lalu:

```bash
echo "D selesai $(date -Is), HEAD $(git rev-parse HEAD)" >> "$BACKUP_DIR/log-rilis.txt"
```

---

## E. Tag `v1.0.8` (pemilik repo / Antigravity, bukan agent)

Dilakukan setelah D lolos. Agent tidak membuat tag atau push. Contoh untuk pemilik:

```bash
# repo backend: tag commit yang benar-benar di-deploy di D2
git tag -a v1.0.8 <MAIN_COMMIT> -m "Release v1.0.8: unit data contract and factor history (5b, V47)"
git push origin v1.0.8

# repo Android: tag commit tempat APK 1.0.8 dibangun (mis. merge B1c)
git tag -a v1.0.8 <commit-apk-1.0.8> -m "Release v1.0.8: unit data, exact quantities, whole-rupiah input"
git push origin v1.0.8
```

---

## F. Salinan DB untuk deteksi data terdampak

Tujuan: menjalankan `docs/sql/detect_money_input_bug.sql` dan `docs/sql/detect_offline_secondary_unit.sql`
**tanpa menyentuh database production atau database development bersama**. Kedua skrip hanya baca, tetapi tetap
dijalankan di salinan agar tidak membebani production dan agar hasilnya bisa diulang.

Jalankan **di server** (laptop pemilik tidak punya Docker), di shell yang sudah menjalankan A1, dari folder repo yang
sudah di-checkout ke `$MAIN_COMMIT` (D2; skrip deteksi ada di `docs/sql/`). Salinan DB berjalan di container sekali
pakai: nama unik, `--network none` (tidak ada port, tidak bisa dihubungi dari mana pun), volume sementara sendiri, dan
dihapus setelah selesai. Hanya file CSV yang dibawa ke laptop, lewat `scp`.

Container ini memakai CPU dan disk server: jalankan di luar jam ramai, dan cek ruang disk seperti di A5.

### F1. Backup terbaru dari production

Ambil setelah D, supaya data sesudah HP pindah ke 1.0.8 ikut terperiksa dan tabel V47 tersedia untuk skrip satuan.

```bash
export TS=$(date +%Y%m%d-%H%M%S)
export BACKUP_F="$BACKUP_DIR/tb_terminal_detect_$TS.dump"
dc exec -T postgres pg_dump -U tb_terminal -d tb_terminal -Fc > "$BACKUP_F"; echo "exit pg_dump: $?"
chmod 600 "$BACKUP_F"; sha256sum "$BACKUP_F" | tee "$BACKUP_F.sha256"

export DETECT_NAME="tb-detect-$TS"                 # nama unik container + volume
export DETECT_OUT="$BACKUP_DIR/detect-$TS"         # CSV hasil, di folder backup yang privat (700)
mkdir -p "$DETECT_OUT" && chmod 700 "$DETECT_OUT"
ls docs/sql/detect_money_input_bug.sql docs/sql/detect_offline_secondary_unit.sql   # keduanya harus ada
df -h /var/lib/docker
```

### F2. Restore ke container sekali pakai

```bash
docker volume create "$DETECT_NAME"
docker run -d --rm --name "$DETECT_NAME" --network none \
  -e POSTGRES_PASSWORD="$(openssl rand -hex 16)" -e POSTGRES_DB=tb_detect \
  -v "$DETECT_NAME":/var/lib/postgresql/data \
  -v "$BACKUP_DIR":/b:ro \
  -v "$PWD/docs/sql":/sql:ro \
  postgres:16-alpine
until docker logs "$DETECT_NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 2; done
until docker exec "$DETECT_NAME" pg_isready -U postgres -d tb_detect >/dev/null 2>&1; do sleep 2; done

docker exec "$DETECT_NAME" pg_restore -U postgres -d tb_detect --no-owner --no-privileges --exit-on-error \
  "/b/$(basename "$BACKUP_F")"
echo "exit pg_restore: $?"     # harus 0
```

Pemeriksaan salinan (`LAST_MIGRATION_SQL` dan `COUNTS_SQL` dari A1; versi diurutkan per `installed_rank`, bukan
`version::int`, karena ada versi `16.1`):

```bash
docker exec "$DETECT_NAME" psql -U postgres -d tb_detect -At -c "$LAST_MIGRATION_SQL"     # 47
docker exec "$DETECT_NAME" psql -U postgres -d tb_detect -At -F ' ' -c "$COUNTS_SQL"
cat "$BACKUP_DIR"/tb_terminal_pre-1.0.7_*.counts                                          # pembanding dari A3
```

Jumlah baris harus sama dengan, atau lebih besar dari, `*.counts` di A3 (data bertambah sejak A).

### F3. Jalankan skrip dan simpan CSV

Isi `since` dengan tanggal APK v1.0.6 mulai dipasang di toko (lihat `MONEY_INPUT_BUG_DETECTION.md`).

```bash
export SINCE=2026-10-01      # GANTI: tanggal v1.0.6 mulai dipakai
export D=$(date +%F)

# Bug input uang v1.0.6 (-q wajib agar BEGIN/ROLLBACK tidak ikut ke CSV)
docker exec "$DETECT_NAME" psql -U postgres -d tb_detect -q --csv -v ON_ERROR_STOP=1 -v since="$SINCE" \
  -f /sql/detect_money_input_bug.sql > "$DETECT_OUT/laporan_bug_uang_$D.csv"
echo "exit: $?"              # harus 0

# Item offline satuan sekunder yang tersimpan dalam satuan salah
docker exec "$DETECT_NAME" psql -U postgres -d tb_detect -q --csv -v ON_ERROR_STOP=1 \
  -f /sql/detect_offline_secondary_unit.sql > "$DETECT_OUT/laporan_satuan_offline_$D.csv"
echo "exit: $?"              # harus 0

chmod 600 "$DETECT_OUT"/*.csv
sha256sum "$DETECT_OUT"/*.csv | tee "$DETECT_OUT/laporan_$D.sha256"
wc -l "$DETECT_OUT"/laporan_*_"$D".csv       # baris 1 = header
head -3 "$DETECT_OUT"/laporan_*_"$D".csv
```

Bila `exit` bukan 0 atau ada `ERROR:` di terminal, CSV bisa kosong atau terpotong; perbaiki lalu ulangi.

### F4. Salin CSV ke laptop dan bersihkan

Di laptop (tidak perlu Docker):

```bash
# di laptop — GANTI user@server dan <TS> (nama folder detect-<TS> dari F1)
mkdir -p ~/tb-detect-1.0.8 && chmod 700 ~/tb-detect-1.0.8
scp 'user@server:/var/backups/tb-terminal/release-1.0.8/detect-<TS>/laporan_*' ~/tb-detect-1.0.8/
cd ~/tb-detect-1.0.8 && sha256sum -c laporan_*.sha256
```

Kembali di server, hapus container, volume sementara, dan salinan yang tidak perlu:

```bash
docker stop "$DETECT_NAME"                         # --rm menghapus container
docker volume rm "$DETECT_NAME"
docker ps -a --filter "name=$DETECT_NAME" --format '{{.Names}}'; docker volume ls -q --filter "name=$DETECT_NAME"   # keduanya kosong
rm -f "$DETECT_OUT"/laporan_*.csv                  # setelah sha256sum -c di laptop OK
rm -f "$BACKUP_F" "$BACKUP_F.sha256"               # dump khusus deteksi; backup A3/D1 tetap disimpan
```

Bila sesi terputus di tengah: `docker ps -a --filter name=tb-detect-` lalu `docker rm -f <nama>; docker volume rm <nama>`.

- CSV memuat data bisnis (nama kasir, pelanggan, supplier, nominal). Simpan di folder privat, serahkan ke pemilik;
  jangan unggah ke layanan publik atau commit ke repo.
- Tinjau sesuai `MONEY_INPUT_BUG_DETECTION.md` (mulai dari `KUAT`) dan komentar di kepala
  `detect_offline_secondary_unit.sql` (mulai dari `PASTI`; perhatikan `opname_setelahnya`). **Koreksi data adalah
  pekerjaan terpisah** yang disetujui pemilik; jangan UPDATE production dari hasil ini.

---

## G. Rollback

Prinsip:

- **Rollback binary** (image + checkout lama) cepat, tetapi skema database tetap yang baru.
- **Rollback skema** hanya lewat **restore backup** (A3 untuk B, D1 untuk D). Semua data yang masuk sesudah backup
  itu (transaksi, sesi kas, stok) **hilang**. Karena itu putuskan rollback sebelum toko kembali bertransaksi.
  Bila transaksi sudah berjalan, utamakan perbaikan maju (fix-forward); rollback dengan restore hanya atas
  keputusan pemilik setelah data sejak backup dicatat.
- Flyway 10.4 mengabaikan migrasi "masa depan" secara bawaan, jadi binary lama **kemungkinan** tetap bisa start di
  skema baru. Ini belum diuji untuk pasangan v1.0.7 ↔ skema lama; perlakukan sebagai cara darurat, bukan rencana.

### Prosedur restore (dipakai G1 dan G2)

```bash
# 0. Pastikan file backup utuh
sha256sum -c "$BACKUP_X.sha256"         # BACKUP_X = $BACKUP_A (rollback B) atau $BACKUP_D (rollback D)

# 1. Hentikan akses klien
dc stop gateway backend

# 2. Safety dump keadaan saat ini (WAJIB, sebelum menimpa)
dc exec -T postgres pg_dump -U tb_terminal -d tb_terminal -Fc > "$BACKUP_DIR/tb_terminal_safety_$(date +%Y%m%d-%H%M%S).dump"
echo "exit: $?"

# 3. Kosongkan dan isi ulang database (destruktif)
dc exec -T postgres psql -U tb_terminal -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE tb_terminal WITH (FORCE);" \
  -c "CREATE DATABASE tb_terminal OWNER tb_terminal;"
dc exec -T postgres pg_restore -U tb_terminal -d tb_terminal --no-owner --role=tb_terminal --exit-on-error < "$BACKUP_X"
echo "exit pg_restore: $?"              # harus 0

# 4. Cek versi migrasi hasil restore
dc exec -T postgres psql -U tb_terminal -d tb_terminal -c "
SELECT version, description, success FROM system.flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
```

Restore lewat menu aplikasi (`BACKUP_RESTORE.md`) tidak dipakai di sini karena backend sengaja dimatikan; langkah di
atas adalah "prosedur PostgreSQL standar" yang disebut di dokumen itu.

### G1. Rollback tahap B (kembali ke versi sebelum v1.0.7)

**Kapan:** backend tidak `healthy` setelah ±3 menit dan log menunjukkan migrasi V45/V46 gagal; `/ready` tetap 503;
login atau checkout online gagal untuk semua user dan tidak bisa diperbaiki cepat.

```bash
# 1. Restore skema dan data ke keadaan A3
export BACKUP_X="$BACKUP_A"
#    → jalankan "Prosedur restore" di atas

# 2. Kembalikan kode ke commit sebelum B (lihat state-before-1.0.7.txt)
cat "$BACKUP_DIR/state-before-1.0.7.txt"
git checkout --detach <HEAD-dari-state-before-1.0.7>

# 3. Pakai image lama tanpa build ulang
sudo tee "$RELEASE_FILE" > /dev/null <<'EOF'
services:
  backend:
    image: tb-terminal-service:rollback-pre-1.0.7
EOF
dc up -d --no-build --no-deps backend
dc ps backend                            # tunggu "healthy"
dc up -d gateway
curl -fsS "$BASE_URL/health"; echo
curl -fsS "$BASE_URL/ready";  echo
```

Catatan: commit lama mungkin belum memuat baris `OFFLINE_MAX_SESSION_AGE_HOURS` di compose; nilai di file env
tidak berbahaya bagi binary lama. Setelah rollback, HP lama tetap memakai sesi yang ada; bila refresh token sempat
dirotasi oleh v1.0.7, user cukup login ulang.

### G2. Rollback tahap D (kembali ke v1.0.7)

**Kapan:** V47 gagal; backend tidak `healthy`; `GET /api/inventory/stock` error atau tanpa `baseUnitId`;
checkout/sync satuan sekunder salah hitung atau `OFFLINE_UNIT_CONFLICT` muncul untuk transaksi yang sah.

HP 1.0.8 kompatibel dengan v1.0.7, jadi HP **tidak** perlu diturunkan.

Pilihan 1 — binary saja (skema V47 tetap; V47 hanya menambah tabel riwayat dan trigger, tidak mengubah tabel lama):

```bash
git checkout --detach v1.0.7             # 14be847c...
sudo tee "$RELEASE_FILE" > /dev/null <<'EOF'
services:
  backend:
    image: tb-terminal-service:1.0.7
    environment:
      APP_VERSION: "1.0.7"
EOF
dc up -d --no-build --no-deps backend
dc ps backend                            # tunggu "healthy"
dc restart gateway
curl -fsS "$BASE_URL/health"; echo       # "version":"1.0.7"
```

Pilihan 2 — bila V47 sendiri gagal di tengah atau skema perlu kembali persis: jalankan "Prosedur restore" dengan
`BACKUP_X="$BACKUP_D"`, lalu langkah Pilihan 1. Jangan menghapus baris `flyway_schema_history` atau tabel V47 secara
manual.

Setelah rollback apa pun: catat waktu, alasan, dan hash di `$BACKUP_DIR/log-rilis.txt`, simpan log
(`dc logs --since 1h backend > "$BACKUP_DIR/backend-rollback.log"`), dan laporkan ke pemilik.
