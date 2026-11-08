package com.ekowahyudi.reelreaction.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandsTest {

    private fun main(fit: VideoFit = VideoFit.CROP, hasAudio: Boolean = true) =
        FfmpegCommands.mainSegment("in.mp4", "bottom.jpg", "wm.png", "out.mp4", fit, hasAudio)

    private fun filterOf(args: List<String>) = args[args.indexOf("-filter_complex") + 1]

    @Test
    fun layoutIsTwoThirdsVideoOneThirdImage() {
        assertEquals(1280, FfmpegCommands.TOP_HEIGHT)
        assertEquals(640, FfmpegCommands.BOTTOM_HEIGHT)
        assertEquals(FfmpegCommands.HEIGHT, FfmpegCommands.TOP_HEIGHT + FfmpegCommands.BOTTOM_HEIGHT)
    }

    @Test
    fun watermarkIsCenteredVertically() {
        assertEquals(880, FfmpegCommands.WATERMARK_Y)
        assertTrue(filterOf(main()).contains("overlay=0:880:shortest=1"))
    }

    @Test
    fun cropModeFillsTopArea() {
        val f = filterOf(main(VideoFit.CROP))
        assertTrue(f.contains("force_original_aspect_ratio=increase,crop=1080:1280"))
    }

    @Test
    fun fitModePadsTopArea() {
        val f = filterOf(main(VideoFit.FIT))
        assertTrue(f.contains("force_original_aspect_ratio=decrease,pad=1080:1280"))
        assertFalse(f.contains("crop=1080:1280"))
    }

    @Test
    fun silentTrackAddedOnlyWhenSourceHasNoAudio() {
        val withAudio = main(hasAudio = true)
        assertFalse(withAudio.contains("lavfi"))
        assertEquals("0:a:0", withAudio[withAudio.indexOf("-map") + 3])

        val silent = main(hasAudio = false)
        assertTrue(silent.contains("lavfi"))
        assertTrue(silent.contains("3:a:0"))
    }

    @Test
    fun outroIsNormalizedToFullFrame() {
        val args = FfmpegCommands.outroSegment("outro.mp4", "seg2.mp4", outroHasAudio = false)
        assertTrue(filterOf(args).contains("scale=1080:1920"))
        assertTrue(args.contains("1:a:0"))
        assertEquals("seg2.mp4", args.last())
    }

    @Test
    fun defaultProfileUsesMultithreadedX264() {
        val args = main()
        assertTrue(args.containsAll(listOf("-c:v", "libx264")))
        assertFalse(args.contains("-threads"))
        assertFalse(args.contains("-filter_threads"))
    }

    @Test
    fun singleThreadProfileLimitsEveryThreadPool() {
        val args = FfmpegCommands.mainSegment(
            "in.mp4", "b.jpg", "wm.png", "out.mp4", VideoFit.CROP, true,
            profile = EncodeProfile.X264_SINGLE_THREAD,
        )
        assertTrue(args.containsAll(listOf("-c:v", "libx264", "-filter_threads", "1")))
        // satu "-threads 1" sebelum tiap dari 3 input + satu untuk encoder
        assertEquals(4, args.count { it == "-threads" })
        assertTrue(args.contains("threads=1:lookahead-threads=1"))
    }

    @Test
    fun mpeg4ProfileAvoidsX264() {
        val main = FfmpegCommands.mainSegment(
            "in.mp4", "b.jpg", "wm.png", "out.mp4", VideoFit.CROP, true,
            profile = EncodeProfile.MPEG4,
        )
        val outro = FfmpegCommands.outroSegment("o.mp4", "o2.mp4", true, profile = EncodeProfile.MPEG4)
        for (args in listOf(main, outro)) {
            assertTrue(args.containsAll(listOf("-c:v", "mpeg4")))
            assertFalse(args.contains("libx264"))
        }
    }

    @Test
    fun outputIsAlwaysTheLastArgument() {
        for (p in EncodeProfile.entries) {
            val main = FfmpegCommands.mainSegment("i", "b", "w", "OUT", VideoFit.FIT, false, profile = p)
            val outro = FfmpegCommands.outroSegment("i", "OUT", false, profile = p)
            assertEquals("OUT", main.last())
            assertEquals("OUT", outro.last())
        }
    }

    @Test
    fun concatCopiesStreams() {
        val args = FfmpegCommands.concat("list.txt", "final.mp4")
        assertTrue(args.containsAll(listOf("-f", "concat", "-c", "copy")))
        assertEquals("final.mp4", args.last())
    }

    @Test
    fun concatListEscapesQuotes() {
        val content = FfmpegCommands.concatListContent("/a/b.mp4", "/c/it's.mp4")
        assertEquals("file '/a/b.mp4'\nfile '/c/it'\\''s.mp4'\n", content)
    }
}
