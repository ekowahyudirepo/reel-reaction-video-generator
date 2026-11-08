# Reel Reaction Video Generator

Aplikasi Android untuk membuat video reel 9:16 (1080×1920) bergaya *reaction* secara otomatis:

**Tempel URL (satu atau banyak) → antrian → unduh otomatis → edit otomatis → video final per layout di galeri.**

```
┌────────────────────────┐
│                        │
│  Video utama           │  ← diunduh dari URL (YouTube, TikTok, Instagram, dll.)
│  (2/3 tinggi)          │
│     Youtube @Ekoyaki   │  ← watermark transparan di tengah: "{platform} {akun sumber}"
├────────────────────────┤
│  Gambar layout         │  ← gambar bawah milik layout (1/3 tinggi)
└────────────────────────┘
        + video penutup (outro) milik layout, digabung di akhir (opsional)
```

## Fitur

- **Multi layout.** Di Pengaturan kamu membuat beberapa layout; tiap layout punya *gambar bawah* dan *video penutup*
  sendiri. Satu link hanya **diunduh sekali** tetapi menghasilkan **satu video per layout aktif**.
- **Antrian serial.** Tempel banyak link sekaligus (satu per baris) atau bagikan dari YouTube/TikTok berkali-kali.
  Link dikerjakan satu per satu. Yang gagal tidak menghentikan antrian dan bisa **diulang** per item atau
  sekaligus (*Ulangi semua gagal*). Saat diulang, hanya output yang gagal yang dikerjakan lagi dan video sumber
  yang sudah terunduh dipakai ulang.
- **Anti-duplikat.** Link yang pernah masuk (selesai, gagal, atau sedang antri) tidak bisa ditambahkan lagi.
  Link dikenali dari kunci kanoniknya, jadi `youtu.be/ID`, `youtube.com/shorts/ID?si=…` dan `watch?v=ID`
  dianggap sama. Catatan baru hilang jika kamu memilih **Hapus data**; menghapus file video di galeri
  **tidak** menghapus catatan, sehingga link itu tetap dianggap sudah diunduh.
- **Database SQLite** (`reel.db`): layout, antrian/riwayat, dan hasil per layout.

## Cara pakai

1. **Pengaturan** → buat layout, pilih gambar bagian bawah (wajib) dan video penutup (opsional) untuk tiap layout.
2. Tempel link (satu atau banyak) lalu **Tambah ke Antrian**.
3. Biarkan aplikasi terbuka sampai antrian selesai. Hasil di galeri: `Movies/ReelReaction/`.

> Pengguna versi lama: gambar/outro yang sudah dipilih otomatis menjadi "Layout 1".

## Cara kerja

| Tahap | Teknologi |
|---|---|
| Unduh + info akun sumber | [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (yt-dlp), diperbarui otomatis |
| Layout, watermark, gabung outro | FFmpeg bawaan youtubedl-android (libx264), dijalankan sebagai proses terpisah |
| Watermark | Digambar dengan Canvas Android menjadi PNG transparan, lalu di-*overlay* |
| Data | SQLite (`SQLiteOpenHelper`) |
| UI | Kotlin + Jetpack Compose |

Kode utama: `pipeline/JobProcessor.kt` (satu job → banyak output), `pipeline/QueueManager.kt` (antrian serial),
`pipeline/FfmpegCommands.kt` (perintah FFmpeg, di-unit-test), `data/Repository.kt` (database),
`pipeline/UrlNormalizer.kt` (kunci anti-duplikat).

FFmpeg bawaan yt-dlp di sebagian HP crash pada filter tertentu, jadi tahap edit mencoba beberapa susunan
berurutan dan mengingat yang berhasil. Jika gagal semua, kartu error menampilkan hasil diagnostik
(juga tersedia lewat **Pengaturan → Tes FFmpeg**).

## Build otomatis menjadi APK (CI)

Workflow `.github/workflows/build-apk.yml` berjalan di setiap push ke `main`: unit test → build APK rilis →
unggah *artifact* dan terbitkan **GitHub Release** (`v1.0.<nomor-run>`). Unduh APK terbaru di tab **Releases**.

### Tanda tangan tetap (disarankan)

Tanpa konfigurasi, APK ditandatangani dengan *debug key* sementara. Agar bisa update langsung di atas versi lama:

```bash
keytool -genkeypair -v -keystore release.jks -alias reel -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 release.jks > keystore.b64
```

Lalu di GitHub → **Settings → Secrets and variables → Actions** tambahkan `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` (`reel`), `KEY_PASSWORD`.

## Build lokal

```bash
./gradlew assembleRelease     # APK di app/build/outputs/apk/release/
./gradlew testDebugUnitTest   # unit test
```

Butuh JDK 17 dan Android SDK (compileSdk 35).

## Batasan

- Hanya **arm64-v8a**, Android 10+ (minSdk 29).
- Saat antrian berjalan, aplikasi memakai **layanan latar depan** (notifikasi progres + wake lock), jadi proses
  tetap jalan walau layar mati atau aplikasi ditinggal; notifikasi "Antrian selesai" muncul di akhir.
  Izinkan notifikasi (Android 13+) dan, bila perlu, **Pengaturan → Jalan di latar belakang → Izinkan tanpa batas
  baterai**. Sebagian merek HP (Xiaomi, Oppo, Vivo, dll.) butuh izin "Mulai otomatis"/"Aplikasi tidur" tambahan.
- Android 15 membatasi layanan jenis *dataSync* sekitar 6 jam per 24 jam; antrian yang sangat panjang bisa
  terhenti lalu dilanjutkan saat aplikasi dibuka lagi.
- Jika aplikasi ditutup paksa, job yang sedang berjalan ditandai gagal dan bisa diulang (antrian yang belum
  jalan dilanjutkan saat aplikasi dibuka lagi).
- Layar dijaga menyala selama aplikasi terbuka dan antrian berjalan.
- Video privat / butuh login tidak bisa diunduh. Jika unduhan tiba-tiba gagal, coba **Pengaturan → Perbarui yt-dlp**.
- Pastikan kamu punya hak/izin memakai konten sumber.
