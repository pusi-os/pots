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
 * Integration of sequence assembly with the real desktop toolchain: builds
 * synthetic clips (lavfi), assembles them with transitions, an audio track
 * and burned subtitles, then probes the result. Skips cleanly when ffmpeg is
 * absent — users then see the §8 card, exactly like the video editor.
 * Hermetic: no network, fixtures live in a temp app-storage home.
 */
class VideoSequenceFfmpegTest {

    private lateinit var savedHome: String
    private lateinit var fakeHome: File
    private var ffmpegBin: String? = null
    private val processor = createVideoProcessor()

    private val clipARel = "media/tests/seq-a.mp4"
    private val clipBRel = "media/tests/seq-b.mp4"
    private val imageRel = "media/tests/seq-img.png"
    private val trackRel = "media/tests/seq-track.wav"

    @BeforeTest
    fun setUp() {
        savedHome = System.getProperty("user.home")
        fakeHome = kotlin.io.path.createTempDirectory("pots-sequence-test").toFile()
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
            clipARel,
            listOf("-f", "lavfi", "-i", "testsrc2=duration=2:size=320x240:rate=24", "-f", "lavfi", "-i", "sine=frequency=440:duration=2"),
            listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest"),
        )
        fixture(
            clipBRel,
            listOf("-f", "lavfi", "-i", "testsrc2=duration=1.5:size=320x240:rate=24", "-f", "lavfi", "-i", "sine=frequency=330:duration=1.5"),
            listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest"),
        )
        // PNG and WAV are picked by extension — forcing codecs would corrupt them.
        fixture(imageRel, listOf("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24", "-frames:v", "1"), emptyList())
        fixture(trackRel, listOf("-f", "lavfi", "-i", "sine=frequency=220:duration=1", "-ar", "44100"), emptyList())
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

    private fun request(
        clips: List<SequenceClip>,
        transitions: List<SequenceTransition>,
        audio: String? = trackRel,
        subtitleText: String? = null,
    ) = SequenceAssemblyRequest(
        clips = clips,
        transitions = transitions,
        width = 320,
        height = 240,
        fps = 24,
        audioRelPath = audio,
        subtitleText = subtitleText,
    )

    @Test
    fun assemblesMixedClipsWithTransitionsAudioAndSubtitles() = runBlocking {
        val clipA = SequenceClip("a", clipARel, isVideo = true, durationMs = probe(clipARel).durationMs)
        val image = SequenceClip("img", imageRel, isVideo = false, durationMs = 1500)
        val clipB = SequenceClip("b", clipBRel, isVideo = true, durationMs = probe(clipBRel).durationMs)
        val expected = sequenceDurationMs(
            listOf(clipA, image, clipB),
            listOf(SequenceTransition.Crossfade, SequenceTransition.Wipe),
        )

        var lastProgress = 0f
        val output = processor.assembleSequence(
            request(
                clips = listOf(clipA, image, clipB),
                transitions = listOf(SequenceTransition.Crossfade, SequenceTransition.Wipe),
                subtitleText = "Привет мир. Это тест субтитров.",
            ),
        ) { fraction ->
            lastProgress = fraction
            true
        }

        assertTrue(output.startsWith("media/edits/") && output.endsWith(".mp4"), output)
        val result = probe(output)
        // Two 0.5 s overlaps: 2 + 1.5 + 1.5 - 1 = 4 s (encoder padding allowed).
        assertTrue(result.durationMs in expected - 300..expected + 400, "duration ${result.durationMs} vs expected $expected")
        assertTrue(result.hasAudio, "picked track must land in the result")
        assertEquals(1f, lastProgress)
        // The middle of the timeline decodes (a frame from the image clip span).
        val midFrame = processor.grabFramePng(output, expected / 2)
        assertTrue(midFrame != null && midFrame.size > 8, "no frame from the assembled clip")
    }

    @Test
    fun singleImageClipsRendersSilentStill() = runBlocking {
        val image = SequenceClip("img", imageRel, isVideo = false, durationMs = 1500)
        val output = processor.assembleSequence(request(listOf(image), emptyList(), audio = null)) { true }
        val result = probe(output)
        assertTrue(!result.hasAudio)
        assertTrue(result.durationMs in 1200..1800, "still duration ${result.durationMs}")
    }

    @Test
    fun shortAudioTrackLoopsToCoverTheResult() = runBlocking {
        // A 1 s track under a 2 s clip must not shorten the picture.
        val clipA = SequenceClip("a", clipARel, isVideo = true, durationMs = probe(clipARel).durationMs)
        val output = processor.assembleSequence(request(listOf(clipA), emptyList())) { true }
        val result = probe(output)
        assertTrue(result.hasAudio)
        assertTrue(result.durationMs in 1700..2400, "looped duration ${result.durationMs}")
    }

    @Test
    fun progressReportsGrowToCompletion() = runBlocking {
        val clipA = SequenceClip("a", clipARel, isVideo = true, durationMs = probe(clipARel).durationMs)
        val fractions = mutableListOf<Float>()
        processor.assembleSequence(request(listOf(clipA), emptyList(), audio = null)) { fraction ->
            fractions.add(fraction)
            true
        }
        assertTrue(fractions.isNotEmpty())
        assertTrue(fractions.all { it in 0f..1f })
        assertEquals(1f, fractions.last())
        fractions.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "progress went backwards: $a -> $b") }
    }

    @Test
    fun cancelStopsTheAssembly() {
        val clipA = SequenceClip("a", clipARel, isVideo = true, durationMs = runBlocking { probe(clipARel).durationMs })
        runBlocking {
            assertFailsWith<VideoEditException.Cancelled> {
                processor.assembleSequence(request(listOf(clipA), emptyList(), audio = null)) { false }
            }
        }
    }

    @Test
    fun emptyRequestRefusesWithSequenceError() {
        runBlocking {
            assertFailsWith<VideoEditException.EmptySequence> {
                processor.assembleSequence(request(emptyList(), emptyList())) { true }
            }
        }
    }

    @Test
    fun missingSourceRefusesWithSourceError() {
        runBlocking {
            assertFailsWith<VideoEditException.SourceMissing> {
                processor.assembleSequence(
                    request(listOf(SequenceClip("ghost", "media/tests/ghost.mp4", isVideo = true, durationMs = 1000)), emptyList()),
                ) { true }
            }
        }
    }
}
