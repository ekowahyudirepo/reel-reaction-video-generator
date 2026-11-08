package com.ekowahyudi.reelreaction

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ekowahyudi.reelreaction.data.AssetStore
import com.ekowahyudi.reelreaction.data.EnqueueResult
import com.ekowahyudi.reelreaction.data.JobStatus
import com.ekowahyudi.reelreaction.data.JobWithOutputs
import com.ekowahyudi.reelreaction.data.Layout
import com.ekowahyudi.reelreaction.pipeline.FfmpegRunner
import com.ekowahyudi.reelreaction.pipeline.RunningInfo
import com.ekowahyudi.reelreaction.pipeline.UrlUtils
import com.ekowahyudi.reelreaction.pipeline.VideoFit
import com.ekowahyudi.reelreaction.pipeline.WatermarkRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ReelApp
    private val repo = app.repo

    val jobs: StateFlow<List<JobWithOutputs>> =
        repo.observeJobs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val layouts: StateFlow<List<Layout>> =
        repo.observeLayouts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val running: StateFlow<RunningInfo?> = app.queue.running

    val videoFit: StateFlow<VideoFit> =
        app.settings.videoFit.stateIn(viewModelScope, SharingStarted.Eagerly, VideoFit.CROP)

    /** Teks di kolom link; boleh berisi banyak link (satu per baris). */
    var input by mutableStateOf("")

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    // ───────────────────────── Antrian ─────────────────────────

    /** Menambah semua link di [input] ke antrian; link yang pernah diunduh dilewati. */
    fun addToQueue() {
        val urls = UrlUtils.extractAll(input)
        if (urls.isEmpty()) {
            _message.value = "Tidak ada link yang valid. Tempel link yang diawali http:// atau https://"
            return
        }
        viewModelScope.launch {
            var added = 0
            val skipped = mutableListOf<String>()
            for (url in urls) {
                when (val result = repo.enqueue(url)) {
                    is EnqueueResult.Added -> added++
                    is EnqueueResult.Duplicate -> skipped += "• ${duplicateReason(result)}: $url"
                }
            }
            if (added > 0) {
                input = ""
                app.queue.kick()
            }
            _message.value = buildString {
                append(if (added > 0) "$added link ditambahkan ke antrian." else "Tidak ada link baru yang ditambahkan.")
                if (skipped.isNotEmpty()) {
                    append("\n${skipped.size} dilewati karena sudah ada:\n").append(skipped.joinToString("\n"))
                }
            }
        }
    }

    private fun duplicateReason(d: EnqueueResult.Duplicate): String = when (d.status) {
        JobStatus.DONE -> "Sudah pernah diunduh"
        JobStatus.QUEUED -> "Sudah ada di antrian"
        JobStatus.RUNNING -> "Sedang diproses"
        JobStatus.FAILED -> "Sudah ada di daftar (gagal, gunakan tombol Ulangi)"
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun retry(jobId: Long) {
        viewModelScope.launch { app.queue.retry(jobId) }
    }

    fun retryAllFailed() {
        viewModelScope.launch {
            val n = app.queue.retryAllFailed()
            _message.value = if (n > 0) "$n job gagal dikembalikan ke antrian." else "Tidak ada job yang gagal."
        }
    }

    fun cancel(jobId: Long) {
        viewModelScope.launch { app.queue.cancel(jobId) }
    }

    /** Menghapus data job. URL-nya boleh diunduh lagi sesudahnya; file video di galeri tidak disentuh. */
    fun deleteJob(jobId: Long) {
        viewModelScope.launch {
            if (running.value?.jobId == jobId) return@launch
            repo.deleteJob(jobId)
            File(app.filesDir, "sources/$jobId").deleteRecursively()
        }
    }

    // ───────────────────────── Layout ─────────────────────────

    fun addLayout() {
        viewModelScope.launch {
            val n = repo.layoutCount() + 1
            repo.addLayout(Layout(name = "Layout $n"))
        }
    }

    fun renameLayout(id: Long, name: String) {
        viewModelScope.launch { repo.layout(id)?.let { repo.updateLayout(it.copy(name = name)) } }
    }

    fun setLayoutActive(id: Long, active: Boolean) {
        viewModelScope.launch { repo.layout(id)?.let { repo.updateLayout(it.copy(active = active)) } }
    }

    fun deleteLayout(id: Long) {
        viewModelScope.launch {
            val layout = repo.layout(id) ?: return@launch
            repo.deleteLayout(id)
            AssetStore.delete(layout.bottomImagePath)
            AssetStore.delete(layout.outroPath)
        }
    }

    fun importLayoutImage(id: Long, uri: Uri) = runImport("Gambar") {
        val file = AssetStore.importImage(app, uri)
        val layout = repo.layout(id)
        if (layout == null) {
            file.delete()
        } else {
            repo.updateLayout(layout.copy(bottomImagePath = file.absolutePath))
            if (layout.bottomImagePath != file.absolutePath) AssetStore.delete(layout.bottomImagePath)
        }
    }

    fun importLayoutOutro(id: Long, uri: Uri) = runImport("Video penutup") {
        val (file, name) = AssetStore.importVideo(app, uri)
        val layout = repo.layout(id)
        if (layout == null) {
            file.delete()
        } else {
            repo.updateLayout(layout.copy(outroPath = file.absolutePath, outroName = name))
            AssetStore.delete(layout.outroPath)
        }
    }

    fun clearLayoutOutro(id: Long) {
        viewModelScope.launch {
            val layout = repo.layout(id) ?: return@launch
            repo.updateLayout(layout.copy(outroPath = null, outroName = null))
            AssetStore.delete(layout.outroPath)
        }
    }

    fun setVideoFit(fit: VideoFit) {
        viewModelScope.launch { app.settings.setVideoFit(fit) }
    }

    // ───────────────────────── Pengaturan lain ─────────────────────────

    fun updateYtDlp() {
        viewModelScope.launch {
            _settingsMessage.value = "Memperbarui yt-dlp…"
            _settingsMessage.value = try {
                val version = app.updateYtDlp(force = true)
                "yt-dlp terbaru: ${version ?: "-"}"
            } catch (e: Exception) {
                "Gagal memperbarui yt-dlp: ${e.message ?: "periksa koneksi internet"}"
            }
        }
    }

    /** Tes kecil untuk mengetahui bagian FFmpeg mana yang bermasalah di HP ini. */
    fun runDiagnostics() {
        viewModelScope.launch {
            _settingsMessage.value = "Menguji FFmpeg… (bisa sampai 1 menit)"
            _settingsMessage.value = try {
                app.ytReady.await().getOrThrow()
                val ready = repo.layouts().firstOrNull { it.isReady }
                val dir = File(app.cacheDir, "diag")
                // Watermark contoh dibuat dengan renderer yang sama dengan pipeline.
                val watermark = File(dir.apply { mkdirs() }, "watermark.png")
                WatermarkRenderer.render("Youtube @Contoh", watermark)
                "Hasil tes FFmpeg:\n" + FfmpegRunner.diagnose(
                    app,
                    video = ready?.outroPath,
                    image = ready?.bottomImagePath,
                    watermark = watermark.absolutePath,
                    workDir = File(dir, "work"),
                )
            } catch (e: Exception) {
                "Tes FFmpeg gagal dijalankan: ${e.message}"
            }
        }
    }

    private fun runImport(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            try {
                block()
                _settingsMessage.value = "$what berhasil disimpan."
            } catch (e: Exception) {
                _settingsMessage.value = "$what gagal diimpor: ${e.message ?: "file tidak bisa dibaca"}"
            } finally {
                _importing.value = false
            }
        }
    }
}
