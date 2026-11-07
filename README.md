# Reel Reaction Video Generator

Aplikasi Android untuk membuat video reel 9:16 (1080×1920) bergaya *reaction* secara otomatis:

**Tempel URL → unduh otomatis → edit otomatis → video final di galeri.**

```
┌────────────────────────┐
│                        │
│  Video utama           │  ← diunduh dari URL (YouTube, TikTok, Instagram, dll.)
│  (2/3 tinggi)          │
│     Youtube @Ekoyaki   │  ← watermark transparan di tengah: "{platform} {akun sumber}"
├────────────────────────┤
│  Gambar kamu          │  ← dipilih di Pengaturan (1/3 tinggi)
└────────────────────────┘
        + video penutup (outro) digabung di akhir (opsional, dipilih di Pengaturan)
```

## Cara pakai

1. Buka **Pengaturan** → pilih **gambar bagian bawah** (wajib) dan **video penutup** (opsional).
2. Tempel link video, atau bagikan link langsung dari YouTube/TikTok lewat menu **Bagikan → Reel Reaction**.
3. Tekan **Buat Video**. Biarkan aplikasi terbuka sampai selesai.
4. Hasil tersimpan di galeri: `Movies/ReelReaction/`.

Teks watermark dibuat otomatis dari metadata video, mis. `Youtube @Ekoyaki`.

## Cara kerja

| Tahap | Teknologi |
|---|---|
| Unduh + info akun sumber | [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (yt-dlp), diperbarui otomatis saat dipakai |
| Layout, watermark, gabung outro | FFmpeg bawaan youtubedl-android (sudah termasuk libx264), dijalankan sebagai proses terpisah |
| Watermark | Digambar dengan Canvas Android menjadi PNG transparan, lalu di-*overlay* |
| UI | Kotlin + Jetpack Compose |

Perintah FFmpeg ada di `pipeline/FfmpegCommands.kt` (sudah di-unit-test), alur utama di `pipeline/ReelPipeline.kt`.

## Build otomatis menjadi APK (CI)

Workflow `.github/workflows/build-apk.yml` berjalan di setiap push ke `main`:

1. Menjalankan unit test.
2. Build APK rilis.
3. Mengunggah APK sebagai *artifact* **dan** menerbitkan **GitHub Release** (`v1.0.<nomor-run>`) berisi APK siap unduh.

Unduh APK terbaru di tab **Releases** repo ini, lalu pasang di HP (izinkan "install dari sumber tidak dikenal").

### Tanda tangan tetap (disarankan)

Tanpa konfigurasi, APK ditandatangani dengan *debug key* sementara, sehingga setiap versi baru harus
meng-uninstall versi lama dulu. Agar bisa update langsung, buat keystore sekali lalu simpan sebagai secret:

```bash
keytool -genkeypair -v -keystore release.jks -alias reel -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 release.jks > keystore.b64
```

Lalu di GitHub → **Settings → Secrets and variables → Actions**, tambahkan:
`KEYSTORE_BASE64` (isi `keystore.b64`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`reel`), `KEY_PASSWORD`.

## Build lokal

```bash
./gradlew assembleRelease     # APK di app/build/outputs/apk/release/
./gradlew testDebugUnitTest   # unit test
```

Butuh JDK 17 dan Android SDK (compileSdk 35).

## Batasan

- Hanya **arm64-v8a** (hampir semua HP Android modern), Android 10+ (minSdk 29).
- Proses edit memakai CPU, jadi video panjang butuh waktu; layar dijaga tetap menyala selama proses.
- Video privat / butuh login tidak bisa diunduh. Jika unduhan tiba-tiba gagal, coba **Pengaturan → Perbarui yt-dlp**.
- Pastikan kamu punya hak/izin memakai konten sumber.
