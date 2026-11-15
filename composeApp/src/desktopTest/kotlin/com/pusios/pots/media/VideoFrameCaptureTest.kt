package com.pusios.pots.media

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Block 7: the video still for a pencil capture comes off the video track at
 * the frame's native resolution (spec §5 — «стоп-кадр из видео-дорожки»).
 * Real desktop toolchain: a synthetic lavfi clip; skips cleanly when ffmpeg
 * is absent (the honest degradation is null → the pencil chip stays hidden).
 */
class VideoFrameCaptureTest {

    private lateinit var savedHome: String
    private lateinit var fakeHome: File
    private var ffmpegBin: String? = null

    private val fixtureRel = "media/tests/frame-capture.mp4"

    @BeforeTest
    fun setUp() {
        savedHome = System.getProperty("user.home")
        fakeHome = kotlin.io.path.createTempDirectory("pots-frame-capture").toFile()
        System.setProperty("user.home", fakeHome.absolutePath)

        ffmpegBin = listOf("ffmpeg", "/usr/bin/ffmpeg", "/usr/sbin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { binary ->
            runCatching {
                val process = ProcessBuilder(binary, "-version").redirectErrorStream(true).start()
                process.inputStream.readBytes()
                process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
        }
        Assume.assumeTrue("ffmpeg not installed — skipping integration", ffmpegBin != null)

        val fixture = File(fakeHome, ".pots/$fixtureRel")
        fixture.parentFile.mkdirs()
        val process = ProcessBuilder(
            ffmpegBin!!,
            "-f", "lavfi", "-i", "testsrc2=duration=1:size=320x240:rate=24",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
            "-y", fixture.path,
        ).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fixture clip synthesis timed out")
        assertEquals(0, process.exitValue(), "fixture clip synthesis failed")
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", savedHome)
        fakeHome.deleteRecursively()
    }

    @Test
    fun stillFrameCapturesAtNativeResolution() = runBlocking {
        val bytes = captureVideoFrame(fixtureRel)
        assertNotNull(bytes, "a readable clip must yield a still frame")

        val grid = decodeImageToGrid(bytes)
        assertEquals(320, grid.width, "the still must keep the track's native width")
        assertEquals(240, grid.height, "the still must keep the track's native height")
    }

    @Test
    fun missingSourceDegradesToNull() = runBlocking {
        assertNull(captureVideoFrame("media/tests/does-not-exist.mp4"))
    }
}
