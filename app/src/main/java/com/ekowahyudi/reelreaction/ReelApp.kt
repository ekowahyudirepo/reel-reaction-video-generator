package com.ekowahyudi.reelreaction

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReelApp : Application() {

    /** Inisialisasi yt-dlp (ekstrak binary) berjalan di latar belakang saat aplikasi dibuka. */
    lateinit var ytReady: Deferred<Result<Unit>>
        private set

    private val updateMutex = Mutex()
    private var updatedThisSession = false

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        ytReady = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            runCatching {
                YoutubeDL.init(this@ReelApp)
                FFmpeg.init(this@ReelApp)
            }
        }
    }

    /**
     * Memperbarui yt-dlp ke rilis stabil terbaru. Situs seperti YouTube sering berubah sehingga yt-dlp
     * bawaan APK cepat usang. Dipanggil sekali per sesi; kegagalan (mis. offline) tidak fatal.
     */
    suspend fun updateYtDlp(force: Boolean = false): String? = updateMutex.withLock {
        if (updatedThisSession && !force) return@withLock YoutubeDL.version(this)
        ytReady.await().getOrThrow()
        withContext(Dispatchers.IO) {
            YoutubeDL.updateYoutubeDL(this@ReelApp, YoutubeDL.UpdateChannel.STABLE)
        }
        updatedThisSession = true
        YoutubeDL.version(this)
    }
}
