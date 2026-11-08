package com.ekowahyudi.reelreaction

import android.app.Application
import com.ekowahyudi.reelreaction.data.Repository
import com.ekowahyudi.reelreaction.data.SettingsRepository
import com.ekowahyudi.reelreaction.pipeline.QueueManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReelApp : Application() {

    /** Scope seumur aplikasi: antrian tetap berjalan walau layar/Activity ditutup. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repo: Repository by lazy { Repository(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    lateinit var queue: QueueManager
        private set

    /** Inisialisasi yt-dlp (ekstrak binary) berjalan di latar belakang saat aplikasi dibuka. */
    lateinit var ytReady: Deferred<Result<Unit>>
        private set

    private val updateMutex = Mutex()
    private var updateAttempted = false

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        ytReady = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            runCatching {
                YoutubeDL.init(this@ReelApp)
                FFmpeg.init(this@ReelApp)
            }
        }
        queue = QueueManager(this, repo, appScope)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                // Pengguna versi lama: gambar/outro tunggal dipindah menjadi "Layout 1".
                if (repo.layoutCount() == 0) settings.takeLegacyLayout()?.let { repo.addLayout(it) }
                // Job yang terputus karena aplikasi ditutup dijadikan gagal agar bisa diulang.
                repo.recoverInterrupted()
            }
            // Antrian yang belum sempat berjalan dilanjutkan otomatis.
            queue.kick()
        }
    }

    /**
     * Memperbarui yt-dlp ke rilis stabil terbaru. Situs seperti YouTube sering berubah sehingga yt-dlp
     * bawaan APK cepat usang. Tanpa [force] hanya dicoba sekali per sesi (agar antrian banyak URL tidak
     * menunggu timeout berulang saat offline); kegagalan tidak fatal.
     */
    suspend fun updateYtDlp(force: Boolean = false): String? = updateMutex.withLock {
        if (updateAttempted && !force) return@withLock YoutubeDL.version(this)
        updateAttempted = true
        ytReady.await().getOrThrow()
        withContext(Dispatchers.IO) {
            YoutubeDL.updateYoutubeDL(this@ReelApp, YoutubeDL.UpdateChannel.STABLE)
        }
        YoutubeDL.version(this)
    }
}
