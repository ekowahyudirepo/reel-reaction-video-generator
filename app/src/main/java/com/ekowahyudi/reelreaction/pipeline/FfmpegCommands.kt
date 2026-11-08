package com.ekowahyudi.reelreaction.pipeline

/** Cara menampilkan video utama di bagian atas bingkai. */
enum class VideoFit {
    /** Isi penuh area atas, bagian yang berlebih dipotong (seperti contoh layout). */
    CROP,

    /** Video utuh tanpa dipotong, sisa area diisi bar hitam. */
    FIT,
}

/** Mode encoder video. */
enum class EncodeProfile(val label: String) {
    /** H.264 (libx264): kualitas & ukuran terbaik. */
    X264("libx264"),

    /** H.264 dengan semua thread dibatasi satu. */
    X264_SINGLE_THREAD("libx264 1-thread"),

    /** MPEG-4 Part 2: tanpa libx264, pilihan terakhir (ukuran file lebih besar). */
    MPEG4("mpeg4"),
}

/**
 * Cara menyusun filtergraph. FFmpeg bawaan yt-dlp (build Android) crash di sebagian HP pada salah satu
 * filter, jadi tersedia lebih dari satu susunan yang menghasilkan gambar identik.
 */
enum class FilterVariant(val label: String) {
    /** vstack (atas+bawah) lalu overlay watermark. */
    STANDARD("vstack"),

    /** Kanvas hitam 1080x1920 lalu overlay berturut-turut; tanpa vstack/pad/fps/setsar. */
    CANVAS("kanvas"),
}

/** Satu cara lengkap menjalankan tahap edit. Dicoba berurutan sampai ada yang berhasil. */
data class EditPlan(val variant: FilterVariant, val profile: EncodeProfile) {
    val label: String get() = "${variant.label} + ${profile.label}"
}

/**
 * Pembuat argumen FFmpeg. Murni Kotlin (tanpa API Android) supaya bisa di-unit-test.
 *
 * Layout bingkai 1080x1920 (9:16):
 *  - 0..1280     : video utama (2/3 tinggi)
 *  - 1280..1920  : gambar (1/3 tinggi)
 *  - tengah      : watermark transparan (PNG dari [WatermarkText])
 * Setelah itu outro dinormalisasi ke ukuran yang sama lalu digabung (concat tanpa re-encode),
 * sehingga outro WAJIB memakai [EncodeProfile] yang sama dengan segmen utama.
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
    private const val BLACK_CANVAS = "color=c=black:s=${WIDTH}x${HEIGHT}:r=$FPS"

    private fun videoEncodeArgs(crf: Int, profile: EncodeProfile): List<String> = when (profile) {
        EncodeProfile.X264 ->
            listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", crf.toString())
        EncodeProfile.X264_SINGLE_THREAD ->
            listOf(
                "-c:v", "libx264", "-preset", "veryfast", "-crf", crf.toString(),
                "-threads", "1", "-x264-params", "threads=1:lookahead-threads=1",
            )
        EncodeProfile.MPEG4 ->
            listOf("-c:v", "mpeg4", "-q:v", "3", "-threads", "1")
    }

    private fun encodeArgs(crf: Int, profile: EncodeProfile): List<String> =
        videoEncodeArgs(crf, profile) + listOf(
            "-r", FPS.toString(),
            "-c:a", "aac", "-b:a", "192k", "-ar", SAMPLE_RATE.toString(), "-ac", "2",
        )

    /** Opsi global (sebelum input) untuk mode aman: batasi thread filter. */
    private fun safeGlobalArgs(profile: EncodeProfile): List<String> =
        if (profile == EncodeProfile.X264) emptyList()
        else listOf("-filter_threads", "1", "-filter_complex_threads", "1")

    /** Opsi sebelum tiap `-i`: batasi thread decoder pada mode aman. */
    private fun inputThreads(profile: EncodeProfile): List<String> =
        if (profile == EncodeProfile.X264) emptyList() else listOf("-threads", "1")

    private fun tailArgs(faststart: Boolean, output: String): List<String> =
        listOf("-shortest") + (if (faststart) listOf("-movflags", "+faststart") else emptyList()) + output

    /** Segmen utama: video di atas, gambar di bawah, watermark di tengah. */
    fun mainSegment(
        mainVideo: String,
        bottomImage: String,
        watermarkPng: String,
        output: String,
        fit: VideoFit,
        mainHasAudio: Boolean,
        crf: Int = 22,
        profile: EncodeProfile = EncodeProfile.X264,
        variant: FilterVariant = FilterVariant.STANDARD,
        faststart: Boolean = true,
    ): List<String> {
        val filter = when (variant) {
            FilterVariant.STANDARD -> standardFilter(fit)
            FilterVariant.CANVAS -> canvasFilter(fit)
        }

        val t = inputThreads(profile)
        val args = mutableListOf("-hide_banner", "-y")
        args += safeGlobalArgs(profile)
        args += t + listOf("-i", mainVideo)
        args += t + listOf("-loop", "1", "-framerate", FPS.toString(), "-i", bottomImage)
        args += t + listOf("-loop", "1", "-framerate", FPS.toString(), "-i", watermarkPng)
        var nextInput = 3
        if (variant == FilterVariant.CANVAS) {
            args += listOf("-f", "lavfi", "-i", BLACK_CANVAS)
            nextInput++
        }
        val silenceInput = nextInput
        if (!mainHasAudio) args += listOf("-f", "lavfi", "-i", SILENCE)
        args += listOf(
            "-filter_complex", filter,
            "-map", "[v]",
            "-map", if (mainHasAudio) "0:a:0" else "$silenceInput:a:0",
        )
        args += encodeArgs(crf, profile)
        args += tailArgs(faststart, output)
        return args
    }

    private fun standardFilter(fit: VideoFit): String {
        val topFilter = when (fit) {
            VideoFit.CROP ->
                "scale=$WIDTH:$TOP_HEIGHT:force_original_aspect_ratio=increase," +
                    "crop=$WIDTH:$TOP_HEIGHT,setsar=1,fps=$FPS"
            VideoFit.FIT ->
                "scale=$WIDTH:$TOP_HEIGHT:force_original_aspect_ratio=decrease," +
                    "pad=$WIDTH:$TOP_HEIGHT:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=$FPS"
        }
        return listOf(
            "[0:v]$topFilter[top]",
            "[1:v]scale=$WIDTH:$BOTTOM_HEIGHT:force_original_aspect_ratio=decrease," +
                "pad=$WIDTH:$BOTTOM_HEIGHT:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=$FPS[bot]",
            "[top][bot]vstack=inputs=2:shortest=1[stack]",
            "[stack][2:v]overlay=0:$WATERMARK_Y:shortest=1,format=yuv420p[v]",
        ).joinToString(";")
    }

    /** Input: 0 = video, 1 = gambar, 2 = watermark, 3 = kanvas hitam. */
    private fun canvasFilter(fit: VideoFit): String {
        val top = when (fit) {
            VideoFit.CROP ->
                "[0:v]scale=$WIDTH:$TOP_HEIGHT:force_original_aspect_ratio=increase," +
                    "crop=$WIDTH:$TOP_HEIGHT[top]"
            VideoFit.FIT ->
                "[0:v]scale=$WIDTH:$TOP_HEIGHT:force_original_aspect_ratio=decrease[top]"
        }
        val topPosition = if (fit == VideoFit.CROP) "0:0" else "(W-w)/2:($TOP_HEIGHT-h)/2"
        return listOf(
            top,
            "[1:v]scale=$WIDTH:$BOTTOM_HEIGHT:force_original_aspect_ratio=decrease[bot]",
            "[3:v][top]overlay=$topPosition:shortest=1[c1]",
            "[c1][bot]overlay=(W-w)/2:$TOP_HEIGHT+($BOTTOM_HEIGHT-h)/2:shortest=1[c2]",
            "[c2][2:v]overlay=0:$WATERMARK_Y:shortest=1,format=yuv420p[v]",
        ).joinToString(";")
    }

    /** Outro dinormalisasi ke 1080x1920 / 30 fps / AAC stereo agar bisa di-concat tanpa re-encode. */
    fun outroSegment(
        outroVideo: String,
        output: String,
        outroHasAudio: Boolean,
        crf: Int = 22,
        profile: EncodeProfile = EncodeProfile.X264,
        faststart: Boolean = true,
    ): List<String> {
        val filter = "[0:v]scale=$WIDTH:$HEIGHT:force_original_aspect_ratio=decrease," +
            "pad=$WIDTH:$HEIGHT:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=$FPS,format=yuv420p[v]"

        val args = mutableListOf("-hide_banner", "-y")
        args += safeGlobalArgs(profile)
        args += inputThreads(profile) + listOf("-i", outroVideo)
        if (!outroHasAudio) args += listOf("-f", "lavfi", "-i", SILENCE)
        args += listOf(
            "-filter_complex", filter,
            "-map", "[v]",
            "-map", if (outroHasAudio) "0:a:0" else "1:a:0",
        )
        args += encodeArgs(crf, profile)
        args += tailArgs(faststart, output)
        return args
    }

    /** Gabungkan segmen yang parameternya identik tanpa re-encode. */
    fun concat(listFile: String, output: String, faststart: Boolean = true): List<String> =
        listOf("-hide_banner", "-y", "-f", "concat", "-safe", "0", "-i", listFile, "-c", "copy") +
            (if (faststart) listOf("-movflags", "+faststart") else emptyList()) + output

    /** Isi file daftar untuk concat demuxer. */
    fun concatListContent(vararg files: String): String =
        files.joinToString("\n", postfix = "\n") { "file '${it.replace("'", "'\\''")}'" }
}
