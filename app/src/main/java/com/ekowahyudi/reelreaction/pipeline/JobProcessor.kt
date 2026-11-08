package com.ekowahyudi.reelreaction.pipeline

import android.content.Context
import com.ekowahyudi.reelreaction.ReelApp
import com.ekowahyudi.reelreaction.data.JobRow
import com.ekowahyudi.reelreaction.data.JobStatus
import com.ekowahyudi.reelreaction.data.Layout
import com.ekowahyudi.reelreaction.data.OutputStatus
import com.ekowahyudi.reelreaction.data.Repository
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
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

class PipelineException(message: String) : Exception(message)

/** Progres keseluruhan satu job (0..1) beserta keterangan tahap yang sedang berjalan. */
data class JobProgress(val fraction: Float, val label: String)

/** Mengingat cara edit yang berhasil di perangkat ini agar tidak mencoba cara yang gagal berulang kali. */
object PlanMemory {
    private const val PREFS = "ffmpeg_plan"
    private const val KEY = "label"

    fun order(context: Context, plans: List<EditPlan>): List<EditPlan> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val preferred = plans.firstOrNull { it.label == stored } ?: return plans
        return listOf(preferred) + plans.filter { it != preferred }
    }

    fun remember(context: Context, plan: EditPlan) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, plan.label).apply()
    }
}

/**
 * Mengerjakan satu job: unduh sekali, lalu hasilkan satu video untuk setiap layout aktif.
 *
 * Progres keseluruhan: persiapan+unduh 0–30%, sisanya dibagi rata untuk tiap output layout.
 * Output yang sudah selesai tidak dikerjakan ulang saat job diulang, dan video sumber disimpan
 * selama job belum selesai penuh agar pengulangan tidak perlu mengunduh lagi.
 */
class JobProcessor(private val app: ReelApp, private val repo: Repository) {

    private var cachedDiagnostics: String? = null

    suspend fun process(job: JobRow, onProgress: (JobProgress) -> Unit) {
        val workDir = File(app.cacheDir, "work/job${job.id}_${System.currentTimeMillis()}").apply { mkdirs() }
        val sourceDir = File(app.filesDir, "sources/${job.id}")
        try {
            repo.setJobStatus(job.id, JobStatus.RUNNING)
            runJob(job, workDir, sourceDir, onProgress)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { repo.setJobStatus(job.id, JobStatus.FAILED, "Dibatalkan.") }
            throw e
        } catch (e: Throwable) {
            repo.setJobStatus(job.id, JobStatus.FAILED, describe(e))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { workDir.deleteRecursively() }
        }
    }

    private suspend fun runJob(job: JobRow, workDir: File, sourceDir: File, onProgress: (JobProgress) -> Unit) {
        val layouts = repo.layouts().filter { it.isReady }
        if (layouts.isEmpty()) {
            throw PipelineException("Belum ada layout aktif yang punya gambar bagian bawah. Atur di Pengaturan.")
        }
        repo.ensureOutputs(job.id, layouts)
        val todo = repo.outputs(job.id).filter { it.status != OutputStatus.DONE }
        if (todo.isEmpty()) {
            sourceDir.deleteRecursively()
            repo.setJobStatus(job.id, JobStatus.DONE)
            return
        }

        // 1) Info video + unduh (dilewati bila sumber & info sudah ada dari percobaan sebelumnya)
        var current = repo.job(job.id) ?: return
        var source = findSource(sourceDir)
        if (source == null || current.watermark == null) {
            onProgress(JobProgress(0.01f, "Menyiapkan yt-dlp…"))
            app.ytReady.await().getOrThrow()
            bestEffort { app.updateYtDlp() } // gagal (mis. offline) tidak fatal
            if (current.watermark == null) {
                onProgress(JobProgress(0.03f, "Membaca info video…"))
                val meta = fetchMetadata(job.url)
                val text = WatermarkText.build(meta.extractorKey, job.url, meta.uploaderId, meta.uploader)
                repo.setJobMeta(job.id, meta.title, text)
                current = current.copy(title = meta.title, watermark = text)
            }
            if (source == null) {
                sourceDir.mkdirs()
                source = download(job.url, sourceDir) { percent ->
                    val p = 0.05f + 0.25f * (percent / 100f).coerceIn(0f, 1f)
                    onProgress(JobProgress(p, "Mengunduh ${percent.toInt().coerceAtLeast(0)}%"))
                }
            }
        }
        val sourceFile: File = source ?: throw IOException("File video sumber tidak ditemukan.")
        val watermarkText = requireNotNull(current.watermark)

        val probe = FfmpegRunner.probe(sourceFile.absolutePath)
        if (!probe.hasVideo) {
            sourceDir.deleteRecursively() // berkas rusak: paksa unduh ulang saat diulang
            throw PipelineException("File hasil unduhan tidak berisi video.")
        }
        val watermarkPng = File(workDir, "watermark.png")
        WatermarkRenderer.render(watermarkText, watermarkPng)
        val fit = app.settings.videoFit.first()

        // 2) Satu output per layout
        val share = 0.70f / todo.size
        var failures = 0
        todo.forEachIndexed { index, output ->
            val base = 0.30f + share * index
            try {
                val layout = layouts.firstOrNull { it.id == output.layoutId }
                    ?: throw PipelineException("Layout sudah dihapus atau dinonaktifkan.")
                val finalFile = renderLayout(sourceFile, probe, watermarkPng, layout, fit, workDir) { frac, label ->
                    onProgress(JobProgress(base + share * 0.95f * frac, "${layout.name}: $label"))
                }
                onProgress(JobProgress(base + share * 0.95f, "${layout.name}: menyimpan ke galeri…"))
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "reel_${job.id}_${slug(layout.name)}_$stamp.mp4"
                val uri = withContext(Dispatchers.IO) { MediaStoreSaver.saveVideo(app, finalFile, name) }
                finalFile.delete()
                repo.setOutput(output.id, OutputStatus.DONE, uri.toString(), "${MediaStoreSaver.FOLDER}/$name")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures++
                repo.setOutput(output.id, OutputStatus.FAILED, error = describe(e))
            }
            onProgress(JobProgress(base + share, "${output.layoutName}: selesai diproses"))
        }

        if (failures == 0) {
            sourceDir.deleteRecursively()
            repo.setJobStatus(job.id, JobStatus.DONE)
        } else {
            repo.setJobStatus(
                job.id,
                JobStatus.FAILED,
                "$failures dari ${todo.size} output gagal. Tekan Ulangi untuk mengerjakan yang gagal saja.",
            )
        }
    }

    /** Membuat satu video final untuk [layout]: segmen utama, lalu (opsional) outro dan penggabungan. */
    private suspend fun renderLayout(
        source: File,
        probe: MediaProbe,
        watermarkPng: File,
        layout: Layout,
        fit: VideoFit,
        workDir: File,
        onProgress: (Float, String) -> Unit,
    ): File {
        val image = File(requireNotNull(layout.bottomImagePath))
        val outro = layout.outroPath?.let(::File)?.takeIf { it.exists() }
        val main = File(workDir, "main_${layout.id}.mp4")
        val mainEnd = if (outro != null) 0.70f else 1f

        val plan = encodeWithFallback(
            plans = PlanMemory.order(app, MAIN_PLANS),
            totalDurationMs = probe.durationMs,
            build = { p ->
                FfmpegCommands.mainSegment(
                    mainVideo = source.absolutePath,
                    bottomImage = image.absolutePath,
                    watermarkPng = watermarkPng.absolutePath,
                    output = main.absolutePath,
                    fit = fit,
                    mainHasAudio = probe.hasAudio,
                    profile = p.profile,
                    variant = p.variant,
                    faststart = false,
                )
            },
            diagnose = {
                cachedDiagnostics ?: FfmpegRunner.diagnose(
                    app,
                    video = source.absolutePath,
                    image = image.absolutePath,
                    watermark = watermarkPng.absolutePath,
                    workDir = File(workDir, "diag"),
                ).also { cachedDiagnostics = it }
            },
        ) { frac, label -> onProgress(mainEnd * frac, "menyusun layout ${(frac * 100).toInt()}% ($label)") }
        PlanMemory.remember(app, plan)

        if (outro == null) return main

        val outroProbe = FfmpegRunner.probe(outro.absolutePath)
        if (!outroProbe.hasVideo) throw PipelineException("Video penutup layout \"${layout.name}\" tidak valid.")
        val outroSegment = File(workDir, "outro_${layout.id}.mp4")
        // Outro harus memakai encoder yang sama dengan segmen utama agar bisa digabung tanpa re-encode.
        encodeWithFallback(
            plans = listOf(plan),
            totalDurationMs = outroProbe.durationMs,
            build = { p ->
                FfmpegCommands.outroSegment(
                    outroVideo = outro.absolutePath,
                    output = outroSegment.absolutePath,
                    outroHasAudio = outroProbe.hasAudio,
                    profile = p.profile,
                    faststart = false,
                )
            },
            diagnose = {
                FfmpegRunner.diagnose(
                    app,
                    video = outro.absolutePath,
                    image = null,
                    watermark = null,
                    workDir = File(workDir, "diag"),
                )
            },
        ) { frac, _ -> onProgress(0.70f + 0.20f * frac, "menyiapkan video penutup ${(frac * 100).toInt()}%") }

        onProgress(0.92f, "menggabungkan video penutup…")
        val list = File(workDir, "concat_${layout.id}.txt")
        list.writeText(FfmpegCommands.concatListContent(main.absolutePath, outroSegment.absolutePath))
        val merged = File(workDir, "final_${layout.id}.mp4")
        FfmpegRunner.run(
            app,
            FfmpegCommands.concat(list.absolutePath, merged.absolutePath, faststart = false),
            totalDurationMs = 0,
        ) { }
        main.delete()
        outroSegment.delete()
        return merged
    }

    /**
     * Menjalankan FFmpeg dengan tiap [EditPlan] di [plans] sampai ada yang berhasil dan mengembalikan
     * plan itu. Bila semuanya gagal, menjalankan diagnostik lalu melempar [PipelineException] berisi ringkasan.
     */
    private suspend fun encodeWithFallback(
        plans: List<EditPlan>,
        totalDurationMs: Long,
        build: (EditPlan) -> List<String>,
        diagnose: suspend () -> String,
        onProgress: (Float, String) -> Unit,
    ): EditPlan {
        val failures = mutableListOf<String>()
        for (plan in plans) {
            try {
                FfmpegRunner.run(app, build(plan), totalDurationMs) { onProgress(it, plan.label) }
                return plan
            } catch (e: FfmpegException) {
                val lastLine = e.tail.lineSequence().lastOrNull { it.isNotBlank() }
                val signal = if (e.exitCode > 128) " sinyal ${e.exitCode - 128}" else ""
                failures += "• ${plan.label}: kode ${e.exitCode}$signal" + (lastLine?.let { " — $it" } ?: "")
            }
        }
        throw PipelineException(
            "Proses edit video gagal di semua cara.\n${failures.joinToString("\n")}\n\nDiagnostik FFmpeg:\n${diagnose()}",
        )
    }

    private suspend inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is PipelineException, is FfmpegException -> e.message.orEmpty()
        else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}".trim().trimEnd(':')
    }

    private fun slug(name: String): String =
        name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(20).ifEmpty { "layout" }

    /** Mencari video sumber hasil unduhan sebelumnya; berkas parsial/sementara diabaikan. */
    private fun findSource(dir: File): File? =
        dir.listFiles { f ->
            f.isFile && f.name.startsWith("source.") && f.length() > 0 &&
                !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") && !f.name.contains(".temp.") &&
                !Regex("""source\.f[\w-]+\.""").containsMatchIn(f.name)
        }?.maxByOrNull { it.length() }

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
        return findSource(dir) ?: throw IOException("Unduhan selesai tetapi file video tidak ditemukan.")
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

    private companion object {
        /** Urutan cara menjalankan tahap edit; yang pertama berhasil dipakai. */
        val MAIN_PLANS = listOf(
            EditPlan(FilterVariant.STANDARD, EncodeProfile.X264),
            EditPlan(FilterVariant.CANVAS, EncodeProfile.X264),
            EditPlan(FilterVariant.CANVAS, EncodeProfile.MPEG4),
        )
    }
}
