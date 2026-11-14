package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Video editing core logic: ffmpeg command building, trim/speed timecode
 * math and `-progress` parsing. Pure common code — no binary, no platform.
 */
class VideoEditTest {

    // -- Timecode formatting ------------------------------------------------

    @Test
    fun ffmpegSecondsFormatsMillisecondsWithThreeDecimals() {
        assertEquals("0.000", formatSecondsForFfmpeg(0L))
        assertEquals("2.500", formatSecondsForFfmpeg(2500L))
        assertEquals("61.040", formatSecondsForFfmpeg(61040L))
        assertEquals("0.001", formatSecondsForFfmpeg(1L))
    }

    // -- Trim math ----------------------------------------------------------

    @Test
    fun trimmedDurationIsEndMinusStart() {
        assertEquals(4500L, trimmedDurationMs(2500L, 7000L))
        assertEquals(1000L, trimmedDurationMs(0L, 1000L))
    }

    @Test
    fun trimmedDurationRefusesInvertedOrEmptyRanges() {
        assertFailsWith<VideoEditException.EmptyRange> { trimmedDurationMs(5000L, 5000L) }
        assertFailsWith<VideoEditException.EmptyRange> { trimmedDurationMs(7000L, 2500L) }
        assertFailsWith<VideoEditException.EmptyRange> { trimmedDurationMs(-1L, 500L) }
    }

    @Test
    fun trimCommandSeeksBeforeInputAndLimitsDuration() {
        val args = ffmpegTrimArgs("/in.mp4", "/out.mp4", 2500L, 7000L, hasAudio = true)
        assertEquals("ffmpeg", args.first())
        assertTrue(args.contains("-y"))
        // Seek belongs before -i (fast seek), duration after.
        assertTrue(args.indexOf("-ss") < args.indexOf("-i"))
        assertEquals("2.500", args[args.indexOf("-ss") + 1])
        assertEquals("-t", args[args.indexOf("-i") + 2])
        assertEquals("4.500", args[args.indexOf("-t") + 1])
        assertEquals(listOf("-i", "/in.mp4"), args.subList(args.indexOf("-i"), args.indexOf("-i") + 2))
        assertEquals("/out.mp4", args.last())
        // Re-encode for frame accuracy.
        assertTrue(args.containsAll(listOf("-c:v", "libx264", "-pix_fmt", "yuv420p")))
        assertTrue(args.containsAll(listOf("-c:a", "aac")))
    }

    @Test
    fun trimCommandWithoutAudioEncodesNoAudioStream() {
        val args = ffmpegTrimArgs("/in.mp4", "/out.mp4", 0L, 1000L, hasAudio = false)
        assertTrue(args.contains("-an"))
        assertTrue(!args.contains("-c:a"))
    }

    // -- Speed math ---------------------------------------------------------

    @Test
    fun speedDurationScalesByFactor() {
        assertEquals(5000L, speedDurationMs(10000L, 2f))
        assertEquals(20000L, speedDurationMs(10000L, 0.5f))
        assertEquals(6667L, speedDurationMs(10000L, 1.5f))
    }

    @Test
    fun speedFactorOutsideRangeRefused() {
        assertFailsWith<VideoEditException.BadSpeed> { speedDurationMs(10000L, 0.24f) }
        assertFailsWith<VideoEditException.BadSpeed> { speedDurationMs(10000L, 4.01f) }
        assertFailsWith<VideoEditException.BadSpeed> { speedDurationMs(10000L, Float.NaN) }
    }

    @Test
    fun atempoChainKeepsEveryStageInsideFfmpegRange() {
        assertEquals(listOf(1.5f), atempoChain(1.5f))
        assertEquals(listOf(2f), atempoChain(2f))
        assertEquals(listOf(2f, 2f), atempoChain(4f))
        assertEquals(listOf(0.5f, 0.5f), atempoChain(0.25f))
        assertEquals(listOf(2f, 1.5f), atempoChain(3f))
        assertEquals(listOf(0.5f, 0.6f), atempoChain(0.3f))
        for (factor in floatArrayOf(0.25f, 0.3f, 0.77f, 1.3f, 2.5f, 3f, 4f)) {
            val chain = atempoChain(factor)
            assertTrue(chain.all { it in 0.5f..2f }, "chain $chain out of range")
            val product = chain.fold(1f) { acc, v -> acc * v }
            assertTrue(kotlin.math.abs(product - factor) < 0.02f, "chain $chain product $product != $factor")
        }
    }

    @Test
    fun speedCommandStretchesVideoAndAudioTogether() {
        val args = ffmpegSpeedArgs("/in.mp4", "/out.mp4", 2f, hasAudio = true)
        val filter = args[args.indexOf("-filter_complex") + 1]
        assertTrue("setpts=PTS/2.0" in filter)
        assertTrue("[0:a]atempo=2.0[a]" in filter)
        assertTrue(args.containsAll(listOf("-map", "[v]", "-map", "[a]")))
        assertEquals("/out.mp4", args.last())
    }

    @Test
    fun speedCommandChainsAtempoForWideFactors() {
        val args = ffmpegSpeedArgs("/in.mp4", "/out.mp4", 4f, hasAudio = true)
        val filter = args[args.indexOf("-filter_complex") + 1]
        assertTrue("atempo=2.0,atempo=2.0" in filter)
    }

    @Test
    fun speedCommandWithoutAudioKeepsVideoSilent() {
        val args = ffmpegSpeedArgs("/in.mp4", "/out.mp4", 1.5f, hasAudio = false)
        assertTrue(args.contains("-an"))
        assertTrue(!args.contains("-filter_complex"))
        assertTrue(args.contains("setpts=PTS/1.5"))
    }

    // -- Audio track operations ---------------------------------------------

    @Test
    fun removeAudioCommandCopiesVideoStream() {
        val args = ffmpegRemoveAudioArgs("/in.mp4", "/out.mp4")
        assertTrue(args.containsAll(listOf("-map", "0:v:0")))
        assertTrue(args.contains("-an"))
        assertTrue(args.containsAll(listOf("-c:v", "copy")))
        assertTrue(!args.contains("-c:a"))
        assertEquals("/out.mp4", args.last())
    }

    @Test
    fun replaceAudioCommandKeepsVideoAndPadsShortTrack() {
        val args = ffmpegReplaceAudioArgs("/video.mp4", "/audio.mp3", "/out.mp4", videoDurationMs = 8000L)
        assertEquals(2, args.count { it == "-i" })
        assertTrue(args.containsAll(listOf("-map", "0:v:0", "-map", "1:a:0")))
        assertTrue(args.containsAll(listOf("-c:v", "copy", "-c:a", "aac")))
        // A short track is padded with silence, the output never outlives the video.
        assertTrue(args.containsAll(listOf("-af", "apad")))
        assertEquals("-t", args[args.indexOf("-t")])
        assertEquals("8.000", args[args.indexOf("-t") + 1])
        assertEquals("/out.mp4", args.last())
    }

    // -- Probing and frame grabs --------------------------------------------

    @Test
    fun probeCommandsAskFfprobeOnly() {
        assertEquals("ffprobe", ffmpegProbeDurationArgs("/in.mp4").first())
        assertEquals("ffprobe", ffmpegProbeAudioArgs("/in.mp4").first())
        assertTrue(ffmpegProbeDurationArgs("/in.mp4").contains("format=duration"))
        assertTrue(ffmpegProbeAudioArgs("/in.mp4").containsAll(listOf("-select_streams", "a")))
    }

    @Test
    fun frameGrabSeeksAndEmitsOnePngToStdout() {
        val args = ffmpegFrameArgs("/in.mp4", 1500L)
        assertEquals("ffmpeg", args.first())
        assertEquals("1.500", args[args.indexOf("-ss") + 1])
        assertTrue(args.indexOf("-ss") < args.indexOf("-i"))
        assertTrue(args.containsAll(listOf("-frames:v", "1")))
        assertEquals("pipe:1", args.last())
    }

    // -- Progress parsing -----------------------------------------------------

    @Test
    fun progressLinesMapToFractionsOfExpectedDuration() {
        assertEquals(0.5f, videoProgressFraction("out_time_us=1500000", 3000L))
        // Despite the name, out_time_ms carries microseconds too.
        assertEquals(0.25f, videoProgressFraction("out_time_ms=750000", 3000L))
        assertEquals(1f, videoProgressFraction("progress=end", 3000L))
        assertEquals(1f, videoProgressFraction("out_time_us=9000000", 3000L))
        assertNull(videoProgressFraction("frame=12", 3000L))
        assertNull(videoProgressFraction("progress=continue", 3000L))
        assertNull(videoProgressFraction("out_time_us=1000", 0L))
    }

    // -- Errors (§8 shape: Russian title, human detail, rescue) ---------------

    @Test
    fun toolMissingErrorNamesFfmpegAndInstallOptions() {
        val error = VideoEditException.ToolMissing()
        assertEquals("ffmpeg не найден", error.title)
        assertTrue("ffmpeg" in error.detail)
        assertTrue("pacman -S ffmpeg" in error.detail)
        assertTrue("apt install ffmpeg" in error.detail)
        assertTrue(error.fix.isNotBlank())
    }

    @Test
    fun renderErrorFollowsSpecTable() {
        val error = VideoEditException.ExportFailed("нет места на диске")
        assertEquals("Не удалось собрать видео", error.title)
        assertTrue("нет места на диске" in error.detail)
        assertTrue(error.fix.isNotBlank())
        val cancelled = VideoEditException.Cancelled()
        assertEquals("Обработка остановлена", cancelled.title)
    }
}
