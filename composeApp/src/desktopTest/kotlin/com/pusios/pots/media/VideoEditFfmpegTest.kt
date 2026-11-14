package com.pusios.pots.media

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration with the real desktop toolchain: generates a synthetic clip
 * with lavfi and drives every processor operation against it. Skips cleanly
 * when ffmpeg is absent (the runtime path users see is the §8 card, covered
 * by [VideoEditTest]). Hermetic: no network, fixtures live in a temp
 * app-storage home.
 */
class VideoEditFfmpegTest {

    private lateinit var savedHome: String
    private lateinit var fakeHome: File
    private var ffmpegBin: String? = null
    private val processor = createVideoProcessor()

    /** App-storage-relative path of the 2 s test clip (video + sine audio). */
    private val fixtureRel = "media/tests/fixture.mp4"
    private val shortAudioRel = "media/tests/short.wav"

    @BeforeTest
    fun setUp() {
        savedHome = System.getProperty("user.home")
        fakeHome = kotlin.io.path.createTempDirectory("pots-video-test").toFile()
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
        runFfmpeg(
            "-f", "lavfi", "-i", "testsrc2=duration=2:size=320x240:rate=24",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-shortest", "-y", fixture.path,
        )
        val shortAudio = File(fakeHome, ".pots/$shortAudioRel")
        shortAudio.parentFile.mkdirs()
        runFfmpeg("-f", "lavfi", "-i", "sine=frequency=330:duration=1", "-ar", "44100", "-y", shortAudio.path)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", savedHome)
        fakeHome.deleteRecursively()
    }

    private fun runFfmpeg(vararg args: String) {
        val process = ProcessBuilder(listOf(ffmpegBin!!) + args).redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "ffmpeg hung: ${args.joinToString(" ")}")
        assertEquals(0, process.exitValue(), "ffmpeg failed: $log")
    }

    private fun probe(relPath: String): VideoFileInfo = runBlocking { processor.probe(relPath) }

    @Test
    fun probeReadsDurationAndAudioPresence() {
        val info = probe(fixtureRel)
        assertTrue(info.durationMs in 1800..2300, "duration ${info.durationMs}")
        assertTrue(info.hasAudio)
    }

    @Test
    fun trimRendersRequestedRangeOnly() = runBlocking {
        val output = processor.trim(fixtureRel, 500L, 1500L, probe(fixtureRel)) { true }
        assertTrue(output.startsWith("media/edits/") && output.endsWith(".mp4"))
        val result = probe(output)
        assertTrue(result.durationMs in 850..1200, "trimmed duration ${result.durationMs}")
        // The source survives untouched.
        assertTrue(File(fakeHome, ".pots/$fixtureRel").length() > 0)
    }

    @Test
    fun speedTwoHalvesDuration() = runBlocking {
        val output = processor.changeSpeed(fixtureRel, 2f, probe(fixtureRel)) { true }
        val result = probe(output)
        // setpts + atempo plus encoder padding land within ~1.0–1.2 s.
        assertTrue(result.durationMs in 800..1400, "sped duration ${result.durationMs}")
        assertTrue(result.hasAudio)
    }

    @Test
    fun removeAudioLeavesSilentVideoOfSameLength() = runBlocking {
        val output = processor.removeAudio(fixtureRel, probe(fixtureRel)) { true }
        val result = probe(output)
        assertTrue(!result.hasAudio)
        assertTrue(result.durationMs in 1800..2300, "muted duration ${result.durationMs}")
    }

    @Test
    fun replaceAudioPadsShortTrackToVideoLength() = runBlocking {
        val output = processor.replaceAudio(fixtureRel, shortAudioRel, probe(fixtureRel)) { true }
        val result = probe(output)
        assertTrue(result.hasAudio)
        // A 1 s track must not shorten the 2 s picture.
        assertTrue(result.durationMs in 1800..2300, "replaced duration ${result.durationMs}")
    }

    @Test
    fun progressReportsGrowToCompletion() = runBlocking {
        val fractions = mutableListOf<Float>()
        processor.trim(fixtureRel, 0L, 1900L, probe(fixtureRel)) { fraction ->
            fractions.add(fraction)
            true
        }
        assertTrue(fractions.isNotEmpty())
        assertTrue(fractions.all { it in 0f..1f })
        assertEquals(1f, fractions.last())
        fractions.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "progress went backwards: $a -> $b") }
    }

    @Test
    fun cancelStopsTheRender() {
        runBlocking {
            assertFailsWith<VideoEditException.Cancelled> {
                processor.trim(fixtureRel, 0L, 1900L, probe(fixtureRel)) { false }
            }
        }
    }

    @Test
    fun frameGrabReturnsPngBytes() = runBlocking {
        val png = processor.grabFramePng(fixtureRel, 1000L)
        assertTrue(png != null && png.size > 8, "no frame grabbed")
        assertEquals(0x89.toByte(), png!![0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
    }
}
