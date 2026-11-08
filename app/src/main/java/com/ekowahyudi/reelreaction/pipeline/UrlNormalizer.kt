package com.ekowahyudi.reelreaction.pipeline

import java.net.URI
import java.net.URLDecoder

/**
 * Mengubah URL menjadi kunci kanonis supaya link yang berbeda penulisannya tetapi menunjuk ke video yang
 * sama dianggap sama (mis. youtu.be/ID, youtube.com/watch?v=ID&si=…, youtube.com/shorts/ID).
 */
object UrlNormalizer {

    private val trackingParams = setOf(
        "si", "feature", "pp", "fbclid", "gclid", "igshid", "igsh", "ref", "ref_src", "ref_url",
        "share_id", "is_from_webapp", "sender_device", "is_copy_url", "mibextid",
    )

    private val youtubeHosts = setOf("youtube.com", "music.youtube.com", "youtube-nocookie.com", "gaming.youtube.com")

    fun key(rawUrl: String): String {
        val url = rawUrl.trim()
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("mobile.")
            ?: return fallbackKey(url)
        val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val query = parseQuery(uri.rawQuery)

        // YouTube
        if (host == "youtu.be") segments.firstOrNull()?.let { return "youtube:$it" }
        if (host in youtubeHosts) {
            query["v"]?.takeIf { it.isNotBlank() }?.let { return "youtube:$it" }
            if (segments.size >= 2 && segments[0] in setOf("shorts", "embed", "live", "v")) {
                return "youtube:${segments[1]}"
            }
        }
        // TikTok: .../video/<id>
        if (host.endsWith("tiktok.com")) {
            val i = segments.indexOf("video")
            if (i >= 0 && i + 1 < segments.size) return "tiktok:${segments[i + 1]}"
        }
        // Instagram: /reel/<code>, /reels/<code>, /p/<code>, /tv/<code>
        if (host.endsWith("instagram.com") && segments.size >= 2 && segments[0] in setOf("reel", "reels", "p", "tv")) {
            return "instagram:${segments[1]}"
        }

        val path = segments.joinToString("/")
        val params = query.filterKeys { it !in trackingParams && !it.startsWith("utm_") }
            .toSortedMap()
            .entries.joinToString("&") { "${it.key}=${it.value}" }
        return buildString {
            append(host)
            if (path.isNotEmpty()) append('/').append(path)
            if (params.isNotEmpty()) append('?').append(params)
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val name = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            if (name.isNotEmpty() && name !in result) result[name] = value
        }
        return result
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Untuk URL yang tidak bisa di-parse sebagai URI (mis. mengandung karakter terlarang). */
    private fun fallbackKey(url: String): String =
        url.lowercase()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('#')
            .trimEnd('/')
}
