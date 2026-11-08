package com.ekowahyudi.reelreaction.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UrlNormalizerTest {

    private fun key(url: String) = UrlNormalizer.key(url)

    @Test
    fun allYoutubeFormsOfSameVideoShareOneKey() {
        val expected = "youtube:avfaaZWRzEw"
        assertEquals(expected, key("https://youtube.com/shorts/avfaaZWRzEw?si=VxYz123"))
        assertEquals(expected, key("https://www.youtube.com/shorts/avfaaZWRzEw"))
        assertEquals(expected, key("https://youtu.be/avfaaZWRzEw?si=abc&t=10"))
        assertEquals(expected, key("https://m.youtube.com/watch?v=avfaaZWRzEw&feature=share"))
        assertEquals(expected, key("https://www.youtube.com/watch?si=zzz&v=avfaaZWRzEw"))
        assertEquals(expected, key("  https://youtube.com/embed/avfaaZWRzEw  "))
    }

    @Test
    fun youtubeVideoIdIsCaseSensitive() {
        assertNotEquals(key("https://youtu.be/AbCdEfGhIjK"), key("https://youtu.be/abcdefghijk"))
    }

    @Test
    fun differentYoutubeVideosDiffer() {
        assertNotEquals(key("https://youtu.be/aaaaaaaaaaa"), key("https://youtu.be/bbbbbbbbbbb"))
    }

    @Test
    fun tiktokIgnoresUserNameAndTrackingParams() {
        val a = key("https://www.tiktok.com/@someone/video/7123456789012345678?is_from_webapp=1&sender_device=pc")
        val b = key("https://tiktok.com/@someone/video/7123456789012345678")
        assertEquals("tiktok:7123456789012345678", a)
        assertEquals(a, b)
    }

    @Test
    fun instagramReelAndPostShortcodes() {
        assertEquals("instagram:Cxyz123", key("https://www.instagram.com/reel/Cxyz123/?igsh=abc"))
        assertEquals("instagram:Cxyz123", key("https://instagram.com/reel/Cxyz123"))
    }

    @Test
    fun genericUrlsDropTrackingAndSortParams() {
        assertEquals("example.com/v/1?a=1&b=2", key("https://www.example.com/v/1/?b=2&utm_source=x&a=1#frag"))
        assertEquals(key("http://example.com/v/1?a=1&b=2"), key("https://www.example.com/v/1?b=2&a=1"))
    }

    @Test
    fun genericDifferentPathsDiffer() {
        assertNotEquals(key("https://example.com/v/1"), key("https://example.com/v/2"))
    }

    @Test
    fun unparsableUrlStillGetsStableKey() {
        val a = key("https://example.com/path with space/")
        val b = key("https://www.example.com/path with space")
        assertEquals(a, b)
    }

    @Test
    fun extractAllFindsEveryLinkInOrderWithoutDuplicates() {
        val text = """
            Cek ini https://youtu.be/aaaaaaaaaaa, lalu
            https://www.tiktok.com/@x/video/123.
            (https://example.com/v/1) dan lagi https://youtu.be/aaaaaaaaaaa
        """.trimIndent()
        assertEquals(
            listOf("https://youtu.be/aaaaaaaaaaa", "https://www.tiktok.com/@x/video/123", "https://example.com/v/1"),
            UrlUtils.extractAll(text),
        )
    }

    @Test
    fun extractAllHandlesNullAndEmpty() {
        assertEquals(emptyList<String>(), UrlUtils.extractAll(null))
        assertEquals(emptyList<String>(), UrlUtils.extractAll("tidak ada link"))
    }
}
