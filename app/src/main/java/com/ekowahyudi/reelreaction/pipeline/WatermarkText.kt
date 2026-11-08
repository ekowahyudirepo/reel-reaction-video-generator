package com.ekowahyudi.reelreaction.pipeline

import java.net.URI

/** Membentuk teks watermark: "{platform download} {nama akun sumber}", mis. "Youtube @Ekoyaki". */
object WatermarkText {

    private val knownPlatforms = mapOf(
        "youtube" to "Youtube",
        "tiktok" to "TikTok",
        "instagram" to "Instagram",
        "facebook" to "Facebook",
        "twitter" to "X",
        "x" to "X",
        "vimeo" to "Vimeo",
        "dailymotion" to "Dailymotion",
        "reddit" to "Reddit",
        "twitch" to "Twitch",
    )

    /** Mengambil nama platform dari kunci extractor yt-dlp (mis. "Youtube"), dengan fallback ke host URL. */
    fun platformName(extractorKey: String?, url: String): String {
        val key = extractorKey?.trim().orEmpty()
        if (key.isNotEmpty() && !key.equals("Generic", ignoreCase = true)) {
            // Contoh kunci turunan: "YoutubeTab", "TikTokUser", "InstagramStory".
            val lower = key.lowercase()
            knownPlatforms.entries
                .filter { lower == it.key || (it.key.length > 1 && lower.startsWith(it.key)) }
                .maxByOrNull { it.key.length }
                ?.let { return it.value }
            return key.replaceFirstChar { it.uppercase() }
        }
        return platformFromHost(url)
    }

    private fun platformFromHost(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase().orEmpty()
            .removePrefix("www.").removePrefix("m.")
        val label = host.split('.').let { parts ->
            if (parts.size >= 2) parts[parts.size - 2] else parts.firstOrNull().orEmpty()
        }
        if (label.isEmpty()) return "Video"
        if (label == "youtu") return "Youtube"
        return knownPlatforms[label] ?: label.replaceFirstChar { it.uppercase() }
    }

    /** Nama akun diawali "@". YouTube sudah memberi handle (@Ekoyaki); platform lain dinormalisasi. */
    fun accountName(uploaderId: String?, uploader: String?): String {
        val id = uploaderId?.trim().orEmpty()
        val name = uploader?.trim().orEmpty()
        return when {
            id.startsWith("@") -> id
            // ID murni angka (mis. TikTok) bukan nama yang berguna; pakai uploader sebagai gantinya.
            id.isNotEmpty() && !id.all { it.isDigit() } && !isYoutubeChannelId(id) -> "@$id"
            name.isNotEmpty() -> "@" + name.replace(Regex("\\s+"), "")
            id.isNotEmpty() -> "@$id"
            else -> ""
        }
    }

    /** ID channel YouTube mentah (UC + 22 karakter) bukan nama yang layak ditampilkan. */
    private fun isYoutubeChannelId(id: String): Boolean = id.length == 24 && id.startsWith("UC")

    fun build(
        extractorKey: String?,
        url: String,
        uploaderId: String?,
        uploader: String?,
    ): String = "${platformName(extractorKey, url)} ${accountName(uploaderId, uploader)}".trim()
}

object UrlUtils {
    private val urlRegex = Regex("""https?://[^\s<>"']+""")

    /** Mengambil URL pertama dari teks (berguna saat memakai "Bagikan" dari aplikasi lain). */
    fun extractUrl(text: String?): String? = extractAll(text).firstOrNull()

    /** Mengambil semua URL dari teks (satu atau banyak, dipisah baris/spasi), tanpa duplikat persis. */
    fun extractAll(text: String?): List<String> =
        text?.let { t ->
            urlRegex.findAll(t).map { it.value.trimEnd('.', ',', ')', ']', ';') }.filter { it.length > 8 }.distinct().toList()
        }.orEmpty()
}
