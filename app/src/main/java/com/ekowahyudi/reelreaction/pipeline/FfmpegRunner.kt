package com.ekowahyudi.reelreaction.pipeline

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class MediaProbe(val durationMs: Long, val hasAudio: Boolean, val hasVideo: Boolean)

/**
 * Menjalankan FFmpeg yang sudah dibawa oleh youtubedl-android (libffmpeg.so + library pendukungnya)
 * sebagai proses terpisah. Ini jalur yang sama dengan yang dipakai yt-dlp untuk menggabung video+audio,
 * sehingga tidak ada library FFmpeg kedua di dalam proses aplikasi (sumber crash sebelumnya) dan
 * kegagalan FFmpeg tidak bisa menutup paksa aplikasi.
 */
object FfmpegRunner {

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
     * Pembatalan coroutine mematikan proses FFmpeg.
     */
    suspend fun run(
        context: Context,
        args: List<String>,
        totalDurationMs: Long,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        // Lokasi hasil ekstrak FFmpeg.init() milik youtubedl-android.
        val libDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        if (!binary.exists() || !libDir.exists()) {
            throw IOException("FFmpeg belum siap. Tutup aplikasi lalu buka lagi, kemudian coba kembali.")
        }

        val command = buildList {
            add(binary.absolutePath)
            addAll(listOf("-nostdin", "-nostats", "-loglevel", "error", "-progress", "pipe:1"))
            addAll(args)
        }
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] =
            libDir.absolutePath + ":" + (System.getenv("LD_LIBRARY_PATH") ?: "")

        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw IOException("FFmpeg tidak bisa dijalankan: ${e.message}", e)
        }

        val tail = ArrayDeque<String>()
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
                            if (tail.size > MAX_TAIL_LINES) tail.removeFirst()
                        }
                    }
                }
                val exit = process.waitFor()
                if (exit != 0) {
                    throw IOException("FFmpeg gagal (kode $exit).\n${tail.joinToString("\n")}")
                }
            } finally {
                killer.cancel()
                process.destroy()
            }
        }
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

    private const val MAX_TAIL_LINES = 25
    private val PROGRESS_KEYS = setOf(
        "frame", "fps", "stream_0_0_q", "stream_1_0_q", "bitrate", "total_size",
        "out_time_us", "out_time_ms", "out_time", "dup_frames", "drop_frames",
        "speed", "progress",
    )
}
