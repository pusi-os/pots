package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sequence assembly core (timeline over canvas elements): junction
 * resolution with transition clamping, total duration math, subtitle
 * grouping/timecodes, SRT rendering and the ffmpeg command building.
 */
class VideoSequenceTest {

    private fun clip(durationMs: Long, isVideo: Boolean = false, id: String = "e$durationMs") = SequenceClip(
        elementId = id,
        relPath = "media/tests/$id",
        isVideo = isVideo,
        durationMs = durationMs,
    )

    // -- Junction resolution ------------------------------------------------

    @Test
    fun crossfadeBetweenEqualClipsOverlapsByTransitionDuration() {
        val junctions = resolveSequenceJunctions(listOf(clip(3000), clip(3000)), listOf(SequenceTransition.Crossfade))
        assertEquals(1, junctions.size)
        val xfade = junctions.single() as SequenceJunction.XFade
        assertEquals(SequenceTransition.Crossfade, xfade.transition)
        assertEquals(SEQUENCE_TRANSITION_MS, xfade.durationMs)
        assertEquals(2500L, xfade.offsetMs)
    }

    @Test
    fun totalDurationSubtractsEveryTransitionOverlap() {
        val clips = listOf(clip(2000), clip(2000), clip(2000))
        val transitions = listOf(SequenceTransition.Crossfade, SequenceTransition.Cut)
        val total = sequenceDurationMs(clips, transitions)
        // Only the crossfade overlaps: 6000 - 500.
        assertEquals(5500L, total)
    }

    @Test
    fun cutJunctionKeepsClipsBackToBack() {
        val junctions = resolveSequenceJunctions(listOf(clip(1000), clip(1000)), listOf(SequenceTransition.Cut))
        assertTrue(junctions.single() is SequenceJunction.Cut)
        assertEquals(2000L, sequenceDurationMs(listOf(clip(1000), clip(1000)), listOf(SequenceTransition.Cut)))
    }

    @Test
    fun shortLeftClipDowngradesTransitionToCut() {
        val junctions = resolveSequenceJunctions(listOf(clip(400), clip(3000)), listOf(SequenceTransition.Slide))
        assertTrue(junctions.single() is SequenceJunction.Cut)
    }

    @Test
    fun shortRightClipDowngradesTransitionToCut() {
        val junctions = resolveSequenceJunctions(listOf(clip(3000), clip(400)), listOf(SequenceTransition.Wipe))
        assertTrue(junctions.single() is SequenceJunction.Cut)
    }

    @Test
    fun chainedTransitionsTrackShrunkVisibleLength() {
        val clips = listOf(clip(800), clip(800), clip(3000))
        val transitions = listOf(SequenceTransition.Crossfade, SequenceTransition.Crossfade)
        val junctions = resolveSequenceJunctions(clips, transitions)
        val first = junctions[0] as SequenceJunction.XFade
        val second = junctions[1] as SequenceJunction.XFade
        assertEquals(300L, first.offsetMs)
        // After the first overlap the visible timeline ends at 1100 ms.
        assertEquals(600L, second.offsetMs)
        assertEquals(3600L, sequenceDurationMs(clips, transitions))
    }

    @Test
    fun singleClipHasNoJunctionsAndKeepsDuration() {
        val junctions = resolveSequenceJunctions(listOf(clip(1500)), emptyList())
        assertTrue(junctions.isEmpty())
        assertEquals(1500L, sequenceDurationMs(listOf(clip(1500)), emptyList()))
    }

    @Test
    fun junctionCountMustMatchClipGaps() {
        assertFailsWith<IllegalArgumentException> {
            resolveSequenceJunctions(listOf(clip(1000), clip(1000)), emptyList())
        }
    }

    @Test
    fun emptySequenceRefusesWithDurationError() {
        assertFailsWith<VideoEditException.EmptySequence> {
            sequenceDurationMs(emptyList(), emptyList())
        }
    }

    // -- Subtitle grouping and timecodes ------------------------------------

    @Test
    fun blankTextProducesNoCues() {
        assertTrue(splitSubtitleCues("   ", 5000).isEmpty())
        assertTrue(splitSubtitleCues("текст", 0).isEmpty())
    }

    @Test
    fun wordsSplitIntoGroupsOfAtMostFour() {
        val cues = splitSubtitleCues("один два три четыре пять шесть", 6000)
        assertEquals(2, cues.size)
        assertEquals("один два три четыре", cues[0].text)
        assertEquals("пять шесть", cues[1].text)
    }

    @Test
    fun sentenceBoundaryClosesGroupEarly() {
        val cues = splitSubtitleCues("Привет мир. Как дела", 4000)
        assertEquals(2, cues.size)
        assertEquals("Привет мир.", cues[0].text)
        assertEquals("Как дела", cues[1].text)
    }

    @Test
    fun evenTimingCoversWholeDurationWithoutGaps() {
        val cues = splitSubtitleCues("а б в г д е ж з", 8000)
        assertEquals(0L, cues.first().startMs)
        assertEquals(8000L, cues.last().endMs)
        cues.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs, "gap between cues") }
        cues.forEach { cue -> assertTrue(cue.endMs > cue.startMs) }
    }

    @Test
    fun singleWordCoversWholeDuration() {
        val cues = splitSubtitleCues("привет", 3000)
        assertEquals(1, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
    }

    // -- SRT rendering -------------------------------------------------------

    @Test
    fun srtTimestampFormatsHoursMinutesSecondsMillis() {
        assertEquals("01:01:01,500", formatSrtTimestamp(3_661_500))
        assertEquals("00:00:00,000", formatSrtTimestamp(0))
    }

    @Test
    fun srtBlocksCarryIndexTimestampsAndText() {
        val srt = subtitleCuesToSrt(
            listOf(
                SubtitleCue(0, 1500, "Первая строка"),
                SubtitleCue(1500, 4000, "Вторая строка"),
            ),
        )
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,500\nПервая строка\n\n" +
                "2\n00:00:01,500 --> 00:00:04,000\nВторая строка\n",
            srt,
        )
    }

    @Test
    fun subtitleStyleScalesWithFrameHeight() {
        val fullHd = subtitleForceStyle(1080)
        assertTrue("FontSize=49" in fullHd, fullHd)
        assertTrue("MarginV=54" in fullHd, fullHd)
        assertTrue("BorderStyle=4" in fullHd && "Alignment=2" in fullHd, fullHd)
        val hd = subtitleForceStyle(720)
        assertTrue("FontSize=32" in hd && "MarginV=36" in hd, hd)
    }

    // -- ffmpeg command building ---------------------------------------------

    private fun spec(
        inputs: List<SequenceInput>,
        junctions: List<SequenceJunction>,
        totalMs: Long,
        audioPath: String? = null,
        srtPath: String? = null,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
    ) = SequenceRenderSpec(
        inputs = inputs,
        junctions = junctions,
        totalDurationMs = totalMs,
        width = width,
        height = height,
        fps = fps,
        audioPath = audioPath,
        srtPath = srtPath,
    )

    private val twoClipsWithFade = spec(
        inputs = listOf(
            SequenceInput("/tmp/a.png", isVideo = false, durationMs = 3000),
            SequenceInput("/tmp/b.mp4", isVideo = true, durationMs = 2000),
        ),
        junctions = listOf(SequenceJunction.XFade(SequenceTransition.Crossfade, 500, 2500)),
        totalMs = 4500,
        audioPath = "/tmp/track.mp3",
        srtPath = "/tmp/subs.srt",
    )

    private fun filterComplex(args: List<String>): String {
        val index = args.indexOf("-filter_complex")
        assertTrue(index >= 0, "no -filter_complex in $args")
        return args[index + 1]
    }

    @Test
    fun imageInputLoopsForItsDuration() {
        val args = ffmpegSequenceArgs(twoClipsWithFade, "/tmp/out.mp4")
        val loopIndex = args.indexOf("-loop")
        assertTrue(loopIndex >= 0)
        assertEquals("1", args[loopIndex + 1])
        assertEquals("-t", args[loopIndex + 2])
        assertEquals("3.000", args[loopIndex + 3])
        assertEquals("-i", args[loopIndex + 4])
        assertEquals("/tmp/a.png", args[loopIndex + 5])
    }

    @Test
    fun videoInputIsBoundedByItsDuration() {
        val args = ffmpegSequenceArgs(twoClipsWithFade, "/tmp/out.mp4")
        // -t before the video input keeps the chain deterministic.
        val inputs = args.indicesOf("-i")
        assertEquals(3, inputs.size, "two clips + one audio input expected: $args")
        val videoT = args.subList(0, inputs[1])
        assertTrue(videoT.contains("-t") && videoT.contains("2.000"), "video input not bounded: $args")
    }

    @Test
    fun normalizeChainScalesPadsAndSyncsEveryInput() {
        val graph = filterComplex(ffmpegSequenceArgs(twoClipsWithFade, "/tmp/out.mp4"))
        assertTrue("[0:v]scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,fps=30,setsar=1,format=yuv420p[v0]" in graph, graph)
        assertTrue("[1:v]scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,fps=30,setsar=1,format=yuv420p[v1]" in graph, graph)
    }

    @Test
    fun xfadeJunctionCarriesTransitionDurationAndOffset() {
        val args = ffmpegSequenceArgs(twoClipsWithFade, "/tmp/out.mp4")
        val graph = filterComplex(args)
        assertTrue("[v0][v1]xfade=transition=fade:duration=0.500:offset=2.500" in graph, graph)
        // The mapped label is the graph's last output.
        val label = args[args.indicesOf("-map").first() + 1]
        assertEquals("[vout]", label)
        assertTrue(graph.trimEnd().endsWith(label), graph)
    }

    @Test
    fun cutJunctionConcatsWithoutOverlap() {
        val args = ffmpegSequenceArgs(
            spec(
                inputs = listOf(
                    SequenceInput("/tmp/a.png", isVideo = false, durationMs = 1000),
                    SequenceInput("/tmp/b.mp4", isVideo = true, durationMs = 1000),
                ),
                junctions = listOf(SequenceJunction.Cut),
                totalMs = 2000,
            ),
            "/tmp/out.mp4",
        )
        val graph = filterComplex(args)
        assertTrue("[v0][v1]concat=n=2:v=1:a=0" in graph, graph)
        assertTrue("xfade" !in graph, graph)
    }

    @Test
    fun audioTrackLoopsAndFadesOutAtVideoEnd() {
        val args = ffmpegSequenceArgs(twoClipsWithFade, "/tmp/out.mp4")
        val loopIndex = args.indexOf("-stream_loop")
        assertTrue(loopIndex >= 0, "no -stream_loop: $args")
        assertEquals("-1", args[loopIndex + 1])
        assertEquals("-i", args[loopIndex + 2])
        assertEquals("/tmp/track.mp3", args[loopIndex + 3])
        assertTrue(args.contains("-af"), "no -af: $args")
        assertEquals("afade=t=out:st=4.000:d=0.500", args[args.indexOf("-af") + 1])
        // The soundtrack is mapped from the third input (after the two clips).
        val mapIndices = args.indicesOf("-map")
        assertTrue(mapIndices.any { args[it + 1] == "2:a:0" }, "audio map missing: $args")
        assertTrue(args.contains("-t") && args.contains("4.500"), "output not bounded: $args")
    }

    @Test
    fun noAudioTrackMeansSilentOutput() {
        val args = ffmpegSequenceArgs(twoClipsWithFade.copy(audioPath = null), "/tmp/out.mp4")
        assertTrue(args.contains("-an"), "no -an: $args")
        assertTrue("-stream_loop" !in args, args.toString())
        assertTrue("-af" !in args, args.toString())
        assertTrue("-c:a" !in args, "audio codec flags without audio: $args")
    }

    @Test
    fun subtitlesFilterBurnsSrtWithForceStyle() {
        val graph = filterComplex(ffmpegSequenceArgs(twoClipsWithFade, "/tmp/out.mp4"))
        assertTrue("subtitles=filename='/tmp/subs.srt':force_style='" in graph, graph)
        assertTrue("FontSize=32" in graph, graph)
        assertTrue("xfade" in graph && graph.indexOf("subtitles") > graph.indexOf("xfade"), "subtitles must ride after the junctions: $graph")
    }

    @Test
    fun noSubtitlesMeansNoSubtitleFilter() {
        val graph = filterComplex(ffmpegSequenceArgs(twoClipsWithFade.copy(srtPath = null), "/tmp/out.mp4"))
        assertTrue("subtitles" !in graph, graph)
    }

    @Test
    fun srtPathEscapesColonInsideFilterQuoting() {
        val graph = filterComplex(ffmpegSequenceArgs(twoClipsWithFade.copy(srtPath = "/a b/c:d.srt"), "/tmp/out.mp4"))
        assertTrue("subtitles=filename='/a b/c\\:d.srt'" in graph, graph)
    }

    @Test
    fun singleClipRendersNormalizeChainOnly() {
        val args = ffmpegSequenceArgs(
            spec(
                inputs = listOf(SequenceInput("/tmp/a.png", isVideo = false, durationMs = 1500)),
                junctions = emptyList(),
                totalMs = 1500,
            ),
            "/tmp/out.mp4",
        )
        val graph = filterComplex(args)
        assertTrue("xfade" !in graph && "concat" !in graph, graph)
        // No junctions and no subtitles: the normalized clip is mapped directly.
        val label = args[args.indicesOf("-map").first() + 1]
        assertEquals("[v0]", label)
        assertTrue(graph.trimEnd().endsWith(label), graph)
        // -t appears on the looping image input and on the output; both are the clip length.
        val outputT = args.indicesOf("-t").last()
        assertEquals("1.500", args[outputT + 1])
    }

    @Test
    fun outputUsesSharedEncodeFlagsAndProgress() {
        val args = ffmpegSequenceArgs(twoClipsWithFade, "/tmp/out.mp4")
        assertTrue(args.take(6) == listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostats", "-y"), args.toString())
        assertTrue("-progress" in args && "pipe:1" in args, args.toString())
        assertTrue(listOf("-c:v", "libx264") in args.windows(2).toList(), args.toString())
        assertTrue(listOf("-c:a", "aac") in args.windows(2).toList(), args.toString())
        assertTrue(listOf("-movflags", "+faststart") in args.windows(2).toList(), args.toString())
        assertEquals("/tmp/out.mp4", args.last())
    }
}

private fun List<String>.indicesOf(value: String): List<Int> = indices.filter { this[it] == value }

private fun <T> List<T>.windows(size: Int): Sequence<List<T>> = sequence {
    for (i in 0..this@windows.size - size) yield(subList(i, i + size))
}
