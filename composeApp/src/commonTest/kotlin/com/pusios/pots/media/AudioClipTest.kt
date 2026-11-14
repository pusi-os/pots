package com.pusios.pots.media

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audio-reactive clip core (spec §11): scene planning over energy peaks,
 * junction math on the shrunk visible timeline, deterministic zoom
 * expressions per style, the reactive overlay windows and the ffmpeg
 * command building. Everything here is pure common code.
 */
class AudioClipTest {

    private val rate = 44100

    /**
     * Four-second track: silence with two loud 90 Hz bursts (1.0 s and
     * 2.9 s). The quiet bed keeps the amplitude envelope normalized so the
     * bursts top the range.
     */
    private fun beatTrack(): AudioAnalysis {
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
        return analyzeAudio(AudioPcm(samples, rate, channels = 1), fps = 30)
    }

    private fun threeScenes(analysis: AudioAnalysis) = AudioClipAssemblyRequest(
        trackRelPath = "media/jobs/track.mp3",
        trackDurationMs = analysis.durationMs,
        scenes = listOf(
            AudioClipSceneSource("e1", "media/jobs/a.png", isVideo = false),
            AudioClipSceneSource("e2", "media/jobs/b.mp4", isVideo = true),
            AudioClipSceneSource("e3", "media/jobs/c.png", isVideo = false),
        ),
        style = AudioClipStyle.Pulse,
        width = 1280,
        height = 720,
        fps = 30,
    )

    @Test
    fun scenesSpanTheWholeTrackAndCutsAlignToPeaks() {
        val analysis = beatTrack()
        val plan = planAudioClip(threeScenes(analysis), analysis)
        assertEquals(3, plan.spans.size)
        assertEquals(0L, plan.spans.first().startMs)
        assertEquals(analysis.durationMs, plan.spans.last().endMs)
        // Spans are contiguous and sources follow the selection order.
        plan.spans.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertEquals(listOf(0, 1, 2), plan.spans.map { it.sourceIndex })
        // The two bursts sit at ~1.1 s and ~2.9 s — cuts land near them.
        val cut1 = plan.spans[0].endMs
        val cut2 = plan.spans[1].endMs
        assertTrue(kotlin.math.abs(cut1 - 1100) < 400, "cut 1 $cut1 near the first burst")
        assertTrue(kotlin.math.abs(cut2 - 2900) < 400, "cut 2 $cut2 near the second burst")
    }

    @Test
    fun emptySelectionFallsBackToAbstractBackground() {
        val analysis = beatTrack()
        val request = threeScenes(analysis).copy(scenes = emptyList())
        val plan = planAudioClip(request, analysis)
        assertEquals(1, plan.spans.size)
        assertEquals(-1, plan.spans[0].sourceIndex, "background scene has no source")
        assertEquals(analysis.durationMs, plan.spans[0].endMs)
        assertTrue(plan.junctions.isEmpty())
        assertEquals(1, plan.sceneZoomExprs.size, "background zooms too")
    }

    @Test
    fun uniformTrackStillGetsEvenlySpreadCuts() {
        // Pure 1 kHz tone: no real peaks, so the fallback spreads cuts evenly.
        val frames = rate * 4
        val samples = ShortArray(frames) { i -> (0.9 * Short.MAX_VALUE * sin(2 * PI * 1000.0 * i / rate)).toInt().toShort() }
        val analysis = analyzeAudio(AudioPcm(samples, rate, 1), fps = 30)
        val plan = planAudioClip(threeScenes(analysis), analysis)
        assertEquals(3, plan.spans.size)
        val duration = analysis.durationMs
        // Even spread: each span ~1/3 of the track, within half a second.
        plan.spans.forEach { span ->
            val spanMs = span.endMs - span.startMs
            assertTrue(kotlin.math.abs(spanMs - duration / 3) < 500, "span ${span.startMs}..${span.endMs} near a third of $duration")
        }
    }

    @Test
    fun junctionsFadeBlackOnTheShrunkTimeline() {
        val analysis = beatTrack()
        val plan = planAudioClip(threeScenes(analysis), analysis)
        assertEquals(2, plan.junctions.size)
        val fadeMs = AUDIO_CLIP_FADE_MS
        // The visible timeline shrinks by one overlap per fading junction.
        assertEquals(analysis.durationMs - 2 * fadeMs, plan.visibleDurationMs)
        assertEquals(plan.spans[0].endMs - fadeMs, plan.junctions[0].visibleOffsetMs, "first junction overlaps the end of scene 1")
        val secondCutTrack = plan.spans[1].endMs
        assertEquals(secondCutTrack - 2 * fadeMs, plan.junctions[1].visibleOffsetMs, "second offset counts both overlaps")
        assertTrue(plan.junctions.all { it.fadeBlack && it.durationMs == fadeMs })
    }

    @Test
    fun shortNeighbourDegradesJunctionToCut() {
        val analysis = beatTrack()
        val request = threeScenes(analysis).copy(scenes = threeScenes(analysis).scenes + AudioClipSceneSource("e4", "media/jobs/d.png", false))
        // Force a tiny middle span via a custom cut list.
        val cuts = listOf(500L, 700L, 2500L)
        val spans = spansFromCuts(cuts, analysis.durationMs, request.scenes.size)
        val junctions = resolveAudioClipJunctions(spans)
        assertEquals(3, junctions.size)
        assertTrue(junctions[0] is AudioClipJunction.Cut, "span 2 (200 ms) cannot host a fade on its left")
        assertTrue(junctions[1] is AudioClipJunction.Cut, "span 2 (200 ms) cannot host a fade on its right")
        assertTrue(junctions[2] is AudioClipJunction.FadeBlack)
        val visible = audioClipVisibleDurationMs(spans, junctions)
        assertEquals(analysis.durationMs - AUDIO_CLIP_FADE_MS, visible, "only one overlap survives")
    }

    @Test
    fun pulseZoomExpressionCarriesBeatPulses() {
        val analysis = beatTrack()
        val expr = sceneZoomExpression(AudioClipStyle.Pulse, sceneStartMs = 0, sceneEndMs = 1500, fps = 30, analysis = analysis)
        assertTrue(expr.startsWith("min(1.06,1+0.06*("), expr)
        assertTrue("exp(-7*max(0,on/30-" in expr, "decaying pulse terms: $expr")
        assertTrue("*gte(on/30," in expr, "gated pulse terms: $expr")
    }

    @Test
    fun quietSceneGetsFlatZoom() {
        val analysis = beatTrack()
        // The final second of the test track is quiet — no beats, flat zoom.
        val expr = sceneZoomExpression(AudioClipStyle.Pulse, sceneStartMs = 3100, sceneEndMs = 4000, fps = 30, analysis = analysis)
        assertEquals("1", expr)
    }

    @Test
    fun cinemaZoomRampsAlternateDirection() {
        val analysis = beatTrack()
        val out = sceneZoomExpression(AudioClipStyle.Cinema, sceneStartMs = 0, sceneEndMs = 2000, fps = 30, sceneIndex = 0, sceneCount = 2, analysis = analysis)
        val back = sceneZoomExpression(AudioClipStyle.Cinema, sceneStartMs = 0, sceneEndMs = 2000, fps = 30, sceneIndex = 1, sceneCount = 2, analysis = analysis)
        assertEquals("min(1.05,1+0.05*(on/60))", out, "even scenes zoom in over their frames")
        assertEquals("min(1.05,1+0.05*(1-(on/60)))", back, "odd scenes zoom back out")
    }

    @Test
    fun glowStyleAddsTrebleFlashWindows() {
        // A 6 kHz burst at 1.0 s becomes a white flash around it.
        val frames = rate * 2
        val samples = ShortArray(frames) { i ->
            if (i in (rate)..(rate + rate / 4)) {
                (0.9 * Short.MAX_VALUE * sin(2 * PI * 6000.0 * i / rate)).toInt().toShort()
            } else {
                0
            }
        }
        val analysis = analyzeAudio(AudioPcm(samples, rate, 1), fps = 30)
        val windows = audioClipFlashWindows(analysis, fadeShiftMs = 0, style = AudioClipStyle.Glow)
        val flashes = windows.filter { it.colorArgb == AUDIO_CLIP_FLASH_WHITE }
        assertTrue(flashes.isNotEmpty(), "treble burst produces a flash: $windows")
        val flash = flashes.first()
        assertTrue(flash.startMs in 800..1250, "flash starts near the burst: ${flash.startMs}")
        assertTrue(flash.endMs > flash.startMs + 100, "flash lasts over 100 ms")
        assertTrue(flash.alpha in 0.15f..0.45f, "flash alpha ${flash.alpha}")
    }

    @Test
    fun loudAmplitudeAddsWarmTintWindows() {
        val analysis = beatTrack()
        val windows = audioClipFlashWindows(analysis, fadeShiftMs = 0, style = AudioClipStyle.Pulse)
        val warm = windows.filter { it.colorArgb == AUDIO_CLIP_WARM_TINT }
        assertTrue(warm.isNotEmpty(), "loud bursts produce warm windows: $windows")
        assertTrue(warm.all { it.alpha == AUDIO_CLIP_WARM_ALPHA })
    }

    @Test
    fun flashWindowsShiftByFadeOverlaps() {
        val analysis = beatTrack()
        val shifted = audioClipFlashWindows(analysis, fadeShiftMs = AUDIO_CLIP_FADE_MS, style = AudioClipStyle.Glow)
        val base = audioClipFlashWindows(analysis, fadeShiftMs = 0, style = AudioClipStyle.Glow)
        assertEquals(base.size, shifted.size)
        base.zip(shifted).forEach { (a, b) ->
            assertEquals(a.startMs - AUDIO_CLIP_FADE_MS, b.startMs, "windows track the shrunk timeline")
        }
    }

    @Test
    fun gradientColorsAreDeterministicPerSeed() {
        val seedA = "track-one".encodeToByteArray()
        val seedB = "track-two".encodeToByteArray()
        assertEquals(audioClipGradientColors(seedA), audioClipGradientColors(seedA))
        val a = audioClipGradientColors(seedA)
        val b = audioClipGradientColors(seedB)
        assertTrue(a != b, "different tracks get different palettes")
        assertEquals(2, a.size)
        a.forEach { color -> assertEquals(0xFF000000L.toInt(), color and 0xFF000000L.toInt(), "opaque pixels") }
    }

    @Test
    fun ffmpegArgsChainScenesFlashesAndAudio() {
        val analysis = beatTrack()
        val request = threeScenes(analysis)
        val plan = planAudioClip(request, analysis)
        val spec = AudioClipRenderSpec(
            inputs = plan.spans.map { span ->
                SequenceInput("/abs/${request.scenes[span.sourceIndex].relPath}", request.scenes[span.sourceIndex].isVideo, span.endMs - span.startMs)
            },
            junctions = plan.junctions,
            visibleDurationMs = plan.visibleDurationMs,
            renderDurationMs = plan.visibleDurationMs,
            width = 1280,
            height = 720,
            fps = 30,
            audioPath = "/abs/track.mp3",
            zoomExprs = plan.sceneZoomExprs,
            flashes = plan.flashWindows,
            vignettePath = null,
        )
        val args = ffmpegAudioClipArgs(spec, "/abs/out.mp4")

        assertEquals("ffmpeg", args.first())
        assertTrue(args.contains("-progress"), "progress reporting")
        val inputCount = args.count { it == "-i" }
        assertEquals(3 + 1 + plan.flashWindows.size, inputCount, "scenes + track + one lavfi input per flash")
        val graph = args[args.indexOf("-filter_complex") + 1]
        // Normalize per scene; zoompan only for scenes with a real pulse.
        val nonFlatScenes = plan.sceneZoomExprs.count { it != "1" }
        assertTrue(nonFlatScenes >= 1, "at least one scene carries beats")
        assertEquals(nonFlatScenes, Regex("zoompan=z=").findAll(graph).count(), "one zoompan per non-flat scene")
        // Junction chain.
        assertEquals(2, Regex("xfade=transition=fadeblack:duration=0.300:offset=").findAll(graph).count())
        // Flashes overlay with timeline gating.
        assertEquals(plan.flashWindows.size, Regex("overlay=0:0:enable='between\\(t,").findAll(graph).count())
        assertTrue("format=rgba,fade=t=in" in graph, "flashes fade their alpha in")
        assertTrue("fade=t=out" in graph, "flashes fade out")
        // Audio: the track itself, mapped right after the picture label.
        val audioInputIndex = args.indexOf("/abs/track.mp3")
        assertEquals("-i", args[audioInputIndex - 1])
        val mapVout = args.indexOf("[vout]")
        assertTrue(mapVout > 0 && args[mapVout - 1] == "-map", "picture maps the filtergraph output")
        assertEquals("-map", args[mapVout + 1])
        assertEquals("3:a:0", args[mapVout + 2], "the track input is the soundtrack")
        assertTrue(args.any { it.startsWith("afade=t=out") }, "sound fades out")
        assertEquals("/abs/out.mp4", args.last())
        assertTrue(args.contains("-t"), "output is capped to the render duration")
    }

    @Test
    fun previewCapsTheRenderDuration() {
        val analysis = beatTrack()
        val request = threeScenes(analysis).copy(previewLimitMs = 1000)
        val plan = planAudioClip(request, analysis)
        assertEquals(1000L, plan.renderDurationMs, "preview renders only the first second")
        assertTrue(plan.visibleDurationMs > 1000, "the full clip stays longer")
    }

    @Test
    fun vignetteRidesCinemaOnly() {
        val analysis = beatTrack()
        val pulse = planAudioClip(threeScenes(analysis), analysis)
        val cinema = planAudioClip(threeScenes(analysis).copy(style = AudioClipStyle.Cinema), analysis)
        assertFalse(pulse.needsVignette)
        assertTrue(cinema.needsVignette)
    }
}
