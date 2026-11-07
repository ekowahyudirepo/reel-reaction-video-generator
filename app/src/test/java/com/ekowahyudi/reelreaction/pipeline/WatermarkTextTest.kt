package com.ekowahyudi.reelreaction.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatermarkTextTest {

    @Test
    fun youtubeHandle() {
        val text = WatermarkText.build("Youtube", "https://youtu.be/abc", "@Ekoyaki", "Ekoyaki")
        assertEquals("Youtube @Ekoyaki", text)
    }

    @Test
    fun youtubeTabKeyStillYoutube() {
        assertEquals("Youtube", WatermarkText.platformName("YoutubeTab", "https://youtube.com/x"))
    }

    @Test
    fun tiktokNumericIdFallsBackToUploaderName() {
        val text = WatermarkText.build("TikTok", "https://tiktok.com/@a/video/1", "6812345678901", "some_user")
        assertEquals("TikTok @some_user", text)
    }

    @Test
    fun instagramUsernameGetsAtSign() {
        assertEquals(
            "Instagram @natgeo",
            WatermarkText.build("Instagram", "https://instagram.com/reel/x", "natgeo", "Nat Geo"),
        )
    }

    @Test
    fun youtubeChannelIdUsesUploaderName() {
        val id = "UC" + "a".repeat(22)
        assertEquals("@Eko Yaki".replace(" ", ""), WatermarkText.accountName(id, "Eko Yaki"))
    }

    @Test
    fun genericExtractorFallsBackToHost() {
        assertEquals("Youtube", WatermarkText.platformName("Generic", "https://www.youtube.com/watch?v=1"))
        assertEquals("Youtube", WatermarkText.platformName(null, "https://youtu.be/abc"))
        assertEquals("Example", WatermarkText.platformName(null, "https://www.example.com/v/1"))
    }

    @Test
    fun missingAccountDoesNotLeaveTrailingSpace() {
        assertEquals("Youtube", WatermarkText.build("Youtube", "https://youtu.be/abc", null, null))
    }

    @Test
    fun extractUrlFromSharedText() {
        assertEquals(
            "https://youtu.be/abc?si=1",
            UrlUtils.extractUrl("Lihat ini https://youtu.be/abc?si=1 keren banget"),
        )
        assertNull(UrlUtils.extractUrl("tidak ada link"))
    }
}
