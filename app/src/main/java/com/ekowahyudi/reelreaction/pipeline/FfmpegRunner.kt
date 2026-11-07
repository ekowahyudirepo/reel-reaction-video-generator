package com.ekowahyudi.reelreaction.pipeline

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MediaProbe(val durationMs: Long, val hasAudio: Boolean, val hasVideo: Boolean)

object FfmpegRunner {

    /** Membaca durasi dan keberadaan track audio/video dari sebuah file. */
    suspend fun probe(path: String): MediaProbe = withContext(Dispatchers.IO) {
        val session = FFprobeKit.getMediaInformation(path)
        val info = session.mediaInformation
            ?: throw IOException("File media tidak bisa dibaca: $path")
        val streams = info.streams.orEmpty()
        val seconds = info.duration?.toDoubleOrNull() ?: 0.0
        MediaProbe(
            durationMs = (seconds * 1000).toLong(),
            hasAudio = streams.any { it.type == "audio" },
            hasVideo = streams.any { it.type == "video" },
        )
    }

    /**
     * Menjalankan FFmpeg dan melaporkan progres 0..1 berdasarkan waktu output terhadap [totalDurationMs].
     * Pembatalan coroutine akan menghentikan proses FFmpeg.
     */
    suspend fun run(
        args: List<String>,
        totalDurationMs: Long,
        onProgress: (Float) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            FFmpegSessionCompleteCallback { finished ->
                val code = finished.returnCode
                when {
                    ReturnCode.isSuccess(code) -> if (cont.isActive) cont.resume(Unit)
                    ReturnCode.isCancel(code) -> Unit // coroutine sudah dibatalkan
                    else -> if (cont.isActive) {
                        val tail = finished.allLogsAsString.orEmpty().takeLast(1200)
                        cont.resumeWithException(IOException("FFmpeg gagal (kode $code).\n$tail"))
                    }
                }
            },
            LogCallback { },
            StatisticsCallback { stats ->
                if (totalDurationMs > 0) {
                    onProgress((stats.time.toFloat() / totalDurationMs).coerceIn(0f, 1f))
                }
            },
        )
        cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }
}
