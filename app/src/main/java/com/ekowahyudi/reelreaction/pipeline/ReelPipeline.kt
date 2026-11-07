package com.ekowahyudi.reelreaction.pipeline

import android.net.Uri
import com.ekowahyudi.reelreaction.ReelApp
import com.ekowahyudi.reelreaction.data.ReelSettings
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Stage(val label: String) {
    INFO("Membaca info video"),
    DOWNLOAD("Mengunduh video"),
    EDIT("Menyusun layout + watermark"),
    OUTRO("Menggabung video penutup"),
    SAVE("Menyimpan ke galeri"),
}

data class PipelineProgress(
    val stage: Stage,
    val progress: Float,
    val detail: String = "",
    val watermark: String? = null,
)

data class PipelineResult(
    val uri: Uri,
    val displayName: String,
    val watermark: String,
    val title: String?,
)

class PipelineException(message: String) : Exception(message)

/**
 * Alur kerja: URL -> unduh otomatis -> edit otomatis -> video final.
 *
 * Bobot progres keseluruhan:
 *  info 0-5% | unduh 5-40% | edit 40-85% | outro 85-97% | simpan 97-100%
 */
class ReelPipeline(private val app: ReelApp) {

    suspend fun run(
        rawInput: String,
        settings: ReelSettings,
        report: (PipelineProgress) -> Unit,
    ): PipelineResult {
        val url = UrlUtils.extractUrl(rawInput)
            ?: throw PipelineException("URL tidak valid. Tempel link video yang diawali http:// atau https://")
        val bottomImage = settings.bottomImagePath?.let(::File)?.takeIf { it.exists() }
            ?: throw PipelineException("Gambar bagian bawah belum dipilih. Buka Pengaturan untuk memilihnya.")
        val outro = settings.outroPath?.let(::File)?.takeIf { it.exists() }

        val workDir = File(app.cacheDir, "work/${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // 1) Info video -> teks watermark
            report(PipelineProgress(Stage.INFO, 0.01f, "Menyiapkan yt-dlp…"))
            app.ytReady.await().getOrThrow()
            runCatching { app.updateYtDlp() } // best-effort; offline/gagal tidak fatal

            report(PipelineProgress(Stage.INFO, 0.03f, "Membaca info video…"))
            val meta = fetchMetadata(url)
            val watermark = WatermarkText.build(meta.extractorKey, url, meta.uploaderId, meta.uploader)
            report(PipelineProgress(Stage.INFO, 0.05f, meta.title.orEmpty(), watermark))

            // 2) Unduh
            val source = download(url, workDir) { percent ->
                val p = 0.05f + 0.35f * (percent / 100f).coerceIn(0f, 1f)
                report(PipelineProgress(Stage.DOWNLOAD, p, "${percent.toInt().coerceAtLeast(0)}%", watermark))
            }

            // 3) Edit: layout + watermark
            report(PipelineProgress(Stage.EDIT, 0.40f, "Memeriksa file…", watermark))
            val sourceProbe = FfmpegRunner.probe(source.absolutePath)
            if (!sourceProbe.hasVideo) throw PipelineException("File hasil unduhan tidak berisi video.")

            val watermarkPng = File(workDir, "watermark.png")
            WatermarkRenderer.render(watermark, watermarkPng)

            val mainSegment = File(workDir, "main_segment.mp4")
            val editEnd = if (outro != null) 0.85f else 0.97f
            FfmpegRunner.run(
                app,
                FfmpegCommands.mainSegment(
                    mainVideo = source.absolutePath,
                    bottomImage = bottomImage.absolutePath,
                    watermarkPng = watermarkPng.absolutePath,
                    output = mainSegment.absolutePath,
                    fit = settings.videoFit,
                    mainHasAudio = sourceProbe.hasAudio,
                ),
                totalDurationMs = sourceProbe.durationMs,
            ) { frac ->
                report(
                    PipelineProgress(
                        Stage.EDIT, 0.40f + (editEnd - 0.40f) * frac,
                        "${(frac * 100).toInt()}%", watermark,
                    ),
                )
            }

            // 4) Gabungkan outro (opsional)
            val finalFile = if (outro != null) {
                report(PipelineProgress(Stage.OUTRO, 0.85f, "Menyiapkan video penutup…", watermark))
                val outroProbe = FfmpegRunner.probe(outro.absolutePath)
                if (!outroProbe.hasVideo) throw PipelineException("Video penutup tidak valid.")

                val outroSegment = File(workDir, "outro_segment.mp4")
                FfmpegRunner.run(
                    app,
                    FfmpegCommands.outroSegment(
                        outroVideo = outro.absolutePath,
                        output = outroSegment.absolutePath,
                        outroHasAudio = outroProbe.hasAudio,
                    ),
                    totalDurationMs = outroProbe.durationMs,
                ) { frac ->
                    report(
                        PipelineProgress(
                            Stage.OUTRO, 0.85f + 0.10f * frac,
                            "${(frac * 100).toInt()}%", watermark,
                        ),
                    )
                }

                report(PipelineProgress(Stage.OUTRO, 0.95f, "Menggabungkan…", watermark))
                val list = File(workDir, "concat.txt")
                list.writeText(
                    FfmpegCommands.concatListContent(
                        mainSegment.absolutePath,
                        outroSegment.absolutePath,
                    ),
                )
                val merged = File(workDir, "final.mp4")
                FfmpegRunner.run(
                    app,
                    FfmpegCommands.concat(list.absolutePath, merged.absolutePath),
                    totalDurationMs = 0,
                ) { }
                merged
            } else {
                mainSegment
            }

            // 5) Simpan ke galeri
            report(PipelineProgress(Stage.SAVE, 0.97f, "Menyimpan…", watermark))
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "reel_$stamp.mp4"
            val uri = withContext(Dispatchers.IO) { MediaStoreSaver.saveVideo(app, finalFile, name) }
            report(PipelineProgress(Stage.SAVE, 1f, "Selesai", watermark))
            return PipelineResult(uri, "${MediaStoreSaver.FOLDER}/$name", watermark, meta.title)
        } finally {
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
        }
    }

    private data class Metadata(
        val extractorKey: String?,
        val uploaderId: String?,
        val uploader: String?,
        val title: String?,
    )

    private suspend fun fetchMetadata(url: String): Metadata {
        val processId = UUID.randomUUID().toString()
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--skip-download")
        }
        val out = runCancellable(onCancel = { YoutubeDL.destroyProcessById(processId) }) {
            YoutubeDL.execute(request, processId).out
        }
        val json = try {
            JSONObject(out.trim())
        } catch (e: Exception) {
            throw PipelineException("Info video tidak bisa dibaca. Pastikan link-nya benar dan publik.")
        }
        fun JSONObject.str(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
        return Metadata(
            extractorKey = json.str("extractor_key") ?: json.str("extractor"),
            uploaderId = json.str("uploader_id"),
            uploader = json.str("uploader") ?: json.str("channel"),
            title = json.str("title"),
        )
    }

    private suspend fun download(url: String, dir: File, onPercent: (Float) -> Unit): File {
        val processId = UUID.randomUUID().toString()
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            // Utamakan H.264 (avc1) agar mudah didecode FFmpeg; maksimal 1080p supaya proses edit tetap cepat.
            addOption(
                "-f",
                "bv*[vcodec^=avc1][height<=1080]+ba[ext=m4a]/b[vcodec^=avc1][height<=1080]/" +
                    "bv*[height<=1080]+ba/b[height<=1080]/b",
            )
            addOption("--merge-output-format", "mp4")
            addOption("--no-mtime")
            addOption("--retries", "3")
            addOption("-N", "4")
            addOption("-o", File(dir, "source.%(ext)s").absolutePath)
        }
        runCancellable(onCancel = { YoutubeDL.destroyProcessById(processId) }) {
            YoutubeDL.execute(request, processId) { progress, _, _ -> onPercent(progress) }
        }
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith("source.") &&
                !f.name.endsWith(".part") && !f.name.endsWith(".ytdl")
        }?.maxByOrNull { it.length() }
            ?: throw IOException("Unduhan selesai tetapi file video tidak ditemukan.")
    }

    /**
     * Menjalankan pekerjaan blocking di thread sendiri dan memanggil [onCancel] bila coroutine dibatalkan
     * (untuk mematikan proses yt-dlp yang sedang berjalan).
     */
    private suspend fun <T> runCancellable(onCancel: () -> Unit, block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            thread(name = "ytdlp-worker") {
                try {
                    cont.resume(block())
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(translate(e))
                }
            }
            cont.invokeOnCancellation { onCancel() }
        }

    private fun translate(e: Throwable): Throwable {
        val msg = e.message.orEmpty()
        return when {
            e is YoutubeDL.CanceledException -> e
            msg.contains("Unsupported URL", ignoreCase = true) ->
                PipelineException("Link ini tidak didukung.")
            msg.contains("Private video", ignoreCase = true) || msg.contains("Sign in", ignoreCase = true) ->
                PipelineException("Video ini privat atau butuh login, jadi tidak bisa diunduh.")
            msg.contains("HTTP Error 429", ignoreCase = true) ->
                PipelineException("Terlalu banyak permintaan ke situs sumber. Coba lagi beberapa menit lagi.")
            else -> PipelineException("Gagal mengunduh: ${msg.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()}")
        }
    }
}
