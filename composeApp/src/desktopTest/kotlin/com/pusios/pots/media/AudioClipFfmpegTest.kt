package com.pusios.pots.media

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration of the audio-reactive clip (spec §11) with the real desktop
 * toolchain: a synthesized beat track (two loud 90 Hz bursts) drives scene
 * splices, zoom pulses and warm overlays; the result is probed for duration
 * and sound. Skips cleanly when ffmpeg is absent — users then see the §8
 * card. Hermetic: no network, fixtures live in a temp app-storage home.
 */
class AudioClipFfmpegTest {

    private lateinit var savedHome: String
    private lateinit var fakeHome: File
    private var ffmpegBin: String? = null
    private val processor = createVideoProcessor()

    private val imageARel = "media/tests/clip-a.png"
    private val imageBRel = "media/tests/clip-b.png"
    private val videoRel = "media/tests/clip-v.mp4"
    private val trackRel = "media/tests/clip-track.wav"

    @BeforeTest
    fun setUp() {
        savedHome = System.getProperty("user.home")
        fakeHome = kotlin.io.path.createTempDirectory("pots-audioclip-test").toFile()
        System.setProperty("user.home", fakeHome.absolutePath)

        ffmpegBin = listOf("ffmpeg", "/usr/bin/ffmpeg", "/usr/sbin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { binary ->
            runCatching {
                val process = ProcessBuilder(binary, "-version").redirectErrorStream(true).start()
                process.inputStream.readBytes()
                process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
        }
        Assume.assumeTrue("ffmpeg not installed — skipping integration", ffmpegBin != null)

        fixture(
            imageARel,
            listOf("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24", "-frames:v", "1"),
            emptyList(),
        )
        fixture(
            imageBRel,
            listOf("-f", "lavfi", "-i", "color=c=0x336699:size=320x240:rate=24", "-frames:v", "1"),
            emptyList(),
        )
        fixture(
            videoRel,
            listOf("-f", "lavfi", "-i", "testsrc2=duration=1.5:size=320x240:rate=24"),
            listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-an"),
        )
        // Four seconds: silence with two loud 90 Hz bursts at 1.0 s and 2.65 s.
        val rate = 44100
        val segments = listOf(
            Triple(0.0, 1.00, 0.0),
            Triple(90.0, 0.25, 0.9),
            Triple(0.0, 1.40, 0.0),
            Triple(90.0, 0.25, 0.9),
            Triple(0.0, 1.10, 0.0),
        )
        val total = (segments.sumOf { it.second } * rate).toInt()
        val samples = ShortArray(total)
        var offset = 0
        for ((frequency, seconds, amplitude) in segments) {
            val frames = (seconds * rate).toInt()
            if (frequency > 0) {
                for (i in 0 until frames) {
                    samples[offset + i] = (amplitude * Short.MAX_VALUE * sin(2 * PI * frequency * (offset + i) / rate)).toInt().toShort()
                }
            }
            offset += frames
        }
        val track = File(fakeHome, ".pots/$trackRel")
        track.parentFile.mkdirs()
        track.writeBytes(pcm16ToWav(samples, rate, channels = 1))
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", savedHome)
        fakeHome.deleteRecursively()
    }

    private fun fixture(relPath: String, sourceArgs: List<String>, encodeArgs: List<String>) {
        val file = File(fakeHome, ".pots/$relPath")
        file.parentFile.mkdirs()
        val process = ProcessBuilder(listOf(ffmpegBin!!) + sourceArgs + encodeArgs + listOf("-y", file.path))
            .redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "ffmpeg hung: $relPath")
        assertEquals(0, process.exitValue(), "ffmpeg failed for $relPath: $log")
    }

    private fun probe(relPath: String): VideoFileInfo = runBlocking { processor.probe(relPath) }

    private fun trackAnalysis(fps: Int): AudioAnalysis {
        val bytes = File(fakeHome, ".pots/$trackRel").readBytes()
        return analyzeAudio(decodeAudioToPcm(bytes), fps = fps)
    }

    private fun request(
        analysis: AudioAnalysis,
        scenes: List<AudioClipSceneSource>,
        style: AudioClipStyle,
        previewLimitMs: Long? = null,
    ) = AudioClipAssemblyRequest(
        trackRelPath = trackRel,
        trackDurationMs = analysis.durationMs,
        scenes = scenes,
        style = style,
        width = 320,
        height = 240,
        fps = 24,
        previewLimitMs = previewLimitMs,
    )

    private val threeScenes = listOf(
        AudioClipSceneSource("a", imageARel, isVideo = false),
        AudioClipSceneSource("v", videoRel, isVideo = true),
        AudioClipSceneSource("b", imageBRel, isVideo = false),
    )

    @Test
    fun assemblesBeatDrivenClipWithSplicesAndSound() = runBlocking {
        val analysis = trackAnalysis(fps = 24)
        var lastProgress = 0f
        val output = processor.assembleAudioClip(request(analysis, threeScenes, AudioClipStyle.Pulse), analysis) { fraction ->
            lastProgress = fraction
            true
        }

        assertTrue(output.startsWith("media/edits/") && output.endsWith(".mp4"), output)
        val result = probe(output)
        // Two 0.3 s overlaps: 4000 - 600 = 3400 ms (encoder padding allowed).
        assertTrue(result.durationMs in 3100..3900, "duration ${result.durationMs}")
        assertTrue(result.hasAudio, "the track must be the soundtrack")
        assertEquals(1f, lastProgress)
        val midFrame = processor.grabFramePng(output, result.durationMs / 2)
        assertTrue(midFrame != null && midFrame.size > 8, "no frame from the assembled clip")
    }

    @Test
    fun cinemaStyleRendersWithVignette() = runBlocking {
        val analysis = trackAnalysis(fps = 24)
        val output = processor.assembleAudioClip(request(analysis, threeScenes, AudioClipStyle.Cinema), analysis) { true }
        val result = probe(output)
        assertTrue(result.durationMs in 3100..3900, "duration ${result.durationMs}")
        assertTrue(result.hasAudio)
    }

    @Test
    fun emptySelectionRendersAbstractBackground() = runBlocking {
        val analysis = trackAnalysis(fps = 24)
        val output = processor.assembleAudioClip(request(analysis, emptyList(), AudioClipStyle.Glow), analysis) { true }
        val result = probe(output)
        // No junctions: the visible timeline is the whole track.
        assertTrue(result.durationMs in 3700..4400, "background clip duration ${result.durationMs}")
        assertTrue(result.hasAudio)
    }

    @Test
    fun previewLimitsTheRenderToTenSecondsOrLess() = runBlocking {
        val analysis = trackAnalysis(fps = 24)
        val output = processor.assembleAudioClip(
            request(analysis, threeScenes, AudioClipStyle.Pulse, previewLimitMs = 1000),
            analysis,
        ) { true }
        val result = probe(output)
        assertTrue(result.durationMs in 700..1400, "preview duration ${result.durationMs}")
    }

    @Test
    fun cancelStopsTheClipRender() {
        val analysis = trackAnalysis(fps = 24)
        runBlocking {
            assertFailsWith<VideoEditException.Cancelled> {
                processor.assembleAudioClip(request(analysis, threeScenes, AudioClipStyle.Pulse), analysis) { false }
            }
        }
    }

    @Test
    fun missingTrackRefusesWithSourceError() {
        val analysis = trackAnalysis(fps = 24)
        runBlocking {
            assertFailsWith<VideoEditException.SourceMissing> {
                processor.assembleAudioClip(
                    request(analysis, threeScenes, AudioClipStyle.Pulse).copy(trackRelPath = "media/tests/ghost-track.wav"),
                    analysis,
                ) { true }
            }
        }
    }
}
