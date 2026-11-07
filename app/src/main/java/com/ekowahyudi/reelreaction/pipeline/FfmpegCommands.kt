package com.ekowahyudi.reelreaction.pipeline

/** Cara menampilkan video utama di bagian atas bingkai. */
enum class VideoFit {
    /** Isi penuh area atas, bagian yang berlebih dipotong (seperti contoh layout). */
    CROP,

    /** Video utuh tanpa dipotong, sisa area diisi bar hitam. */
    FIT,
}

/**
 * Pembuat argumen FFmpeg. Murni Kotlin (tanpa API Android) supaya bisa di-unit-test.
 *
 * Layout bingkai 1080x1920 (9:16):
 *  - 0..1280     : video utama (2/3 tinggi)
 *  - 1280..1920  : gambar (1/3 tinggi)
 *  - tengah      : watermark transparan (PNG dari [WatermarkText])
 * Setelah itu outro dinormalisasi ke ukuran yang sama lalu digabung (concat tanpa re-encode).
 */
object FfmpegCommands {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val TOP_HEIGHT = 1280
    const val BOTTOM_HEIGHT = HEIGHT - TOP_HEIGHT
    const val WATERMARK_HEIGHT = 160
    const val FPS = 30
    const val SAMPLE_RATE = 44100

    /** Posisi Y overlay watermark supaya tepat di tengah bingkai. */
    const val WATERMARK_Y = (HEIGHT - WATERMARK_HEIGHT) / 2

    private const val SILENCE = "anullsrc=channel_layout=stereo:sample_rate=$SAMPLE_RATE"

    private fun encodeArgs(crf: Int): List<String> = listOf(
        "-c:v", "libx264", "-preset", "veryfast", "-crf", crf.toString(),
        "-r", FPS.toString(),
        "-c:a", "aac", "-b:a", "192k", "-ar", SAMPLE_RATE.toString(), "-ac", "2",
    )

    /** Segmen utama: video di atas, gambar di bawah, watermark di tengah. */
    fun mainSegment(
        mainVideo: String,
        bottomImage: String,
        watermarkPng: String,
        output: String,
        fit: VideoFit,
        mainHasAudio: Boolean,
        crf: Int = 22,
    ): List<String> {
        val topFilter = when (fit) {
            VideoFit.CROP ->
                "scale=$WIDTH:$TOP_HEIGHT:force_original_aspect_ratio=increase," +
                    "crop=$WIDTH:$TOP_HEIGHT,setsar=1,fps=$FPS"
            VideoFit.FIT ->
                "scale=$WIDTH:$TOP_HEIGHT:force_original_aspect_ratio=decrease," +
                    "pad=$WIDTH:$TOP_HEIGHT:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=$FPS"
        }
        val filter = listOf(
            "[0:v]$topFilter[top]",
            "[1:v]scale=$WIDTH:$BOTTOM_HEIGHT:force_original_aspect_ratio=decrease," +
                "pad=$WIDTH:$BOTTOM_HEIGHT:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=$FPS[bot]",
            "[top][bot]vstack=inputs=2:shortest=1[stack]",
            "[stack][2:v]overlay=0:$WATERMARK_Y:shortest=1,format=yuv420p[v]",
        ).joinToString(";")

        val args = mutableListOf(
            "-hide_banner", "-y",
            "-i", mainVideo,
            "-loop", "1", "-framerate", FPS.toString(), "-i", bottomImage,
            "-loop", "1", "-framerate", FPS.toString(), "-i", watermarkPng,
        )
        if (!mainHasAudio) args += listOf("-f", "lavfi", "-i", SILENCE)
        args += listOf(
            "-filter_complex", filter,
            "-map", "[v]",
            "-map", if (mainHasAudio) "0:a:0" else "3:a:0",
        )
        args += encodeArgs(crf)
        args += listOf("-shortest", "-movflags", "+faststart", output)
        return args
    }

    /** Outro dinormalisasi ke 1080x1920 / 30 fps / AAC stereo agar bisa di-concat tanpa re-encode. */
    fun outroSegment(
        outroVideo: String,
        output: String,
        outroHasAudio: Boolean,
        crf: Int = 22,
    ): List<String> {
        val filter = "[0:v]scale=$WIDTH:$HEIGHT:force_original_aspect_ratio=decrease," +
            "pad=$WIDTH:$HEIGHT:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=$FPS,format=yuv420p[v]"

        val args = mutableListOf("-hide_banner", "-y", "-i", outroVideo)
        if (!outroHasAudio) args += listOf("-f", "lavfi", "-i", SILENCE)
        args += listOf(
            "-filter_complex", filter,
            "-map", "[v]",
            "-map", if (outroHasAudio) "0:a:0" else "1:a:0",
        )
        args += encodeArgs(crf)
        args += listOf("-shortest", "-movflags", "+faststart", output)
        return args
    }

    /** Gabungkan segmen yang parameternya identik tanpa re-encode. */
    fun concat(listFile: String, output: String): List<String> = listOf(
        "-hide_banner", "-y",
        "-f", "concat", "-safe", "0", "-i", listFile,
        "-c", "copy", "-movflags", "+faststart", output,
    )

    /** Isi file daftar untuk concat demuxer. */
    fun concatListContent(vararg files: String): String =
        files.joinToString("\n", postfix = "\n") { "file '${it.replace("'", "'\\''")}'" }
}
