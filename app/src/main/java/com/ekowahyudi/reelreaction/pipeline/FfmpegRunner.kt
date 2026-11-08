package com.ekowahyudi.reelreaction.pipeline

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

data class MediaProbe(val durationMs: Long, val hasAudio: Boolean, val hasVideo: Boolean)

/** FFmpeg berjalan tetapi berakhir dengan kode error (mis. 139 = crash / SIGSEGV). */
class FfmpegException(val exitCode: Int, val tail: String) : IOException(describe(exitCode, tail)) {
    private companion object {
        fun describe(code: Int, tail: String): String {
            val signal = if (code > 128) " (sinyal ${code - 128})" else ""
            return "FFmpeg gagal (kode $code)$signal." + if (tail.isNotBlank()) "\n$tail" else ""
        }
    }
}

/**
 * Menjalankan FFmpeg yang sudah dibawa oleh youtubedl-android (libffmpeg.so + library pendukungnya)
 * sebagai proses terpisah. Ini jalur yang sama dengan yang dipakai yt-dlp untuk menggabung video+audio,
 * sehingga tidak ada library FFmpeg kedua di dalam proses aplikasi dan kegagalan FFmpeg tidak bisa
 * menutup paksa aplikasi.
 */
object FfmpegRunner {

    data class ExecResult(val exitCode: Int, val output: List<String>)

    /** Mengambil durasi dan keberadaan track audio/video lewat API bawaan Android. */
    suspend fun probe(path: String): MediaProbe = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            fun meta(key: Int) = retriever.extractMetadata(key)
            MediaProbe(
                durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                hasVideo = meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
            )
        } catch (e: Exception) {
            throw IOException("File media tidak bisa dibaca: ${File(path).name}", e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Menjalankan FFmpeg dan melaporkan progres 0..1 berdasarkan waktu output terhadap [totalDurationMs].
     * Melempar [FfmpegException] bila kode keluar bukan 0. Pembatalan coroutine mematikan proses FFmpeg.
     */
    suspend fun run(
        context: Context,
        args: List<String>,
        totalDurationMs: Long,
        onProgress: (Float) -> Unit,
    ) {
        val result = exec(context, args, totalDurationMs, onProgress, maxLines = 25)
        if (result.exitCode != 0) {
            throw FfmpegException(result.exitCode, result.output.joinToString("\n"))
        }
    }

    /** Seperti [run] tetapi tidak melempar saat kode keluar != 0; hasilnya dikembalikan apa adanya. */
    suspend fun exec(
        context: Context,
        args: List<String>,
        totalDurationMs: Long = 0,
        onProgress: (Float) -> Unit = {},
        maxLines: Int = 25,
    ): ExecResult = withContext(Dispatchers.IO) {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        // Lokasi hasil ekstrak YoutubeDL.init() / FFmpeg.init() milik youtubedl-android.
        // Urutan path sama persis dengan yang dipakai yt-dlp: folder python memuat libc++_shared.so
        // yang dibutuhkan beberapa library FFmpeg (mis. librubberband.so).
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val pythonLib = File(packages, "python/usr/lib")
        val ffmpegLib = File(packages, "ffmpeg/usr/lib")
        val aria2cLib = File(packages, "aria2c/usr/lib")
        if (!binary.exists() || !ffmpegLib.exists() || !pythonLib.exists()) {
            throw IOException("FFmpeg belum siap. Tutup aplikasi lalu buka lagi, kemudian coba kembali.")
        }

        val command = buildList {
            add(binary.absolutePath)
            addAll(listOf("-nostdin", "-nostats", "-loglevel", "error", "-progress", "pipe:1"))
            addAll(args)
        }
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().apply {
            this["LD_LIBRARY_PATH"] = listOf(pythonLib, ffmpegLib, aria2cLib).joinToString(":") { it.absolutePath }
            this["TMPDIR"] = context.cacheDir.absolutePath
        }

        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw IOException("FFmpeg tidak bisa dijalankan: ${e.message}", e)
        }

        val tail = ArrayDeque<String>()
        var exit = -1
        coroutineScope {
            // Bila coroutine dibatalkan, proses dimatikan sehingga pembacaan output di bawah berhenti.
            val killer = launch {
                try {
                    awaitCancellation()
                } finally {
                    process.destroyForcibly()
                }
            }
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val us = progressMicros(line)
                        if (us != null) {
                            if (totalDurationMs > 0) {
                                onProgress((us / 1000f / totalDurationMs).coerceIn(0f, 1f))
                            }
                        } else if (!isProgressNoise(line)) {
                            tail.addLast(line)
                            if (tail.size > maxLines) tail.removeFirst()
                        }
                    }
                }
                exit = process.waitFor()
            } finally {
                killer.cancel()
                process.destroy()
            }
        }
        ExecResult(exit, tail.toList())
    }

    /**
     * Menjalankan serangkaian tes kecil, satu proses FFmpeg per tes, untuk melihat bagian FFmpeg mana
     * yang crash di perangkat ini. Tes memakai sumber sintetis (lavfi) dan, bila ada, file asli.
     */
    suspend fun diagnose(
        context: Context,
        video: String?,
        image: String?,
        watermark: String?,
        workDir: File,
    ): String {
        workDir.mkdirs()
        val report = StringBuilder()

        suspend fun step(name: String, args: List<String>, showFirstLine: Boolean = false) {
            val outcome = try {
                val r = withTimeout(STEP_TIMEOUT_MS) { exec(context, listOf("-hide_banner", "-y") + args, maxLines = 400) }
                if (r.exitCode == 0) {
                    "OK" + if (showFirstLine) ": ${r.output.firstOrNull().orEmpty()}" else ""
                } else {
                    val signal = if (r.exitCode > 128) " sinyal ${r.exitCode - 128}" else ""
                    "GAGAL (kode ${r.exitCode}$signal) ${r.output.lastOrNull().orEmpty()}".trim()
                }
            } catch (e: TimeoutCancellationException) {
                "TIMEOUT"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            report.append("• ").append(name).append(": ").append(outcome).append('\n')
        }

        val synthetic = listOf("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=1")
        val toNull = listOf("-f", "null", "-")
        val outA = File(workDir, "diag_a.mp4").absolutePath
        val outB = File(workDir, "diag_b.mp4").absolutePath

        try {
            step("versi", listOf("-version"), showFirstLine = true)
            step("mux mp4", synthetic + listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", outA))
            step("mux mp4 faststart", synthetic + listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", outB))
            step(
                "filter scale+crop",
                synthetic + listOf("-vf", "scale=1080:1280:force_original_aspect_ratio=increase,crop=1080:1280") + toNull,
            )
            step(
                "filter pad+setsar+fps",
                synthetic + listOf(
                    "-vf",
                    "scale=1080:640:force_original_aspect_ratio=decrease,pad=1080:640:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=30",
                ) + toNull,
            )
            step(
                "filter vstack",
                listOf(
                    "-f", "lavfi", "-i", "color=c=red:s=320x200:r=30:d=1",
                    "-f", "lavfi", "-i", "color=c=blue:s=320x200:r=30:d=1",
                    "-filter_complex", "[0:v][1:v]vstack=inputs=2[v]", "-map", "[v]",
                ) + toNull,
            )
            if (watermark != null) {
                step(
                    "filter overlay PNG transparan",
                    listOf(
                        "-f", "lavfi", "-i", "color=c=green:s=1080x400:r=30:d=1",
                        "-i", watermark,
                        "-filter_complex", "[0:v][1:v]overlay=0:100,format=yuv420p[v]", "-map", "[v]", "-t", "1",
                    ) + toNull,
                )
            }
            step(
                "audio resample 48k->44.1k + aac",
                listOf(
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1",
                    "-ar", "44100", "-ac", "2", "-c:a", "aac",
                ) + toNull,
            )
            if (image != null) {
                step("gambar bawah (-loop)", listOf("-loop", "1", "-framerate", "30", "-t", "1", "-i", image) + toNull)
            }
            if (video != null) {
                step(
                    "video asli: scale+crop",
                    listOf("-t", "1", "-i", video, "-an", "-vf", "scale=1080:1280:force_original_aspect_ratio=increase,crop=1080:1280") + toNull,
                )
                step(
                    "video asli: audio resample + aac",
                    listOf("-t", "2", "-i", video, "-vn", "-ar", "44100", "-ac", "2", "-c:a", "aac") + toNull,
                )
            }
            if (video != null && image != null && watermark != null) {
                for (variant in FilterVariant.entries) {
                    val full = FfmpegCommands.mainSegment(
                        video, image, watermark, "-", VideoFit.CROP, mainHasAudio = true,
                        variant = variant, faststart = false,
                    )
                    // Ganti output file dengan keluaran null dan batasi 1 detik.
                    step("susunan penuh ${variant.label} (tanpa file)", full.dropLast(1) + listOf("-t", "1") + toNull)
                }
                val full = FfmpegCommands.mainSegment(
                    video, image, watermark, outA, VideoFit.CROP, mainHasAudio = true,
                    variant = FilterVariant.STANDARD, faststart = false,
                )
                step("susunan penuh vstack -> mp4", full.dropLast(1) + listOf("-t", "1", outA))
            }
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
        return report.toString().trimEnd()
    }

    private fun progressMicros(line: String): Long? = when {
        line.startsWith("out_time_us=") -> line.substringAfter('=').trim().toLongOrNull()
        else -> null
    }

    /** Baris "kunci=nilai" dari -progress yang bukan pesan error. */
    private fun isProgressNoise(line: String): Boolean {
        val key = line.substringBefore('=', missingDelimiterValue = "")
        return key in PROGRESS_KEYS
    }

    private const val STEP_TIMEOUT_MS = 40_000L
    private val PROGRESS_KEYS = setOf(
        "frame", "fps", "stream_0_0_q", "stream_1_0_q", "bitrate", "total_size",
        "out_time_us", "out_time_ms", "out_time", "dup_frames", "drop_frames",
        "speed", "progress",
    )
}
