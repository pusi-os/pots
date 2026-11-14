package com.pusios.pots.media

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audio analysis core (spec §11): the per-frame band envelopes are pure
 * deterministic sample math, verified here on synthetic signals — bass,
 * mid and treble dominance, the amplitude envelope, frame mapping and
 * the energy-peak detector that aligns scene cuts.
 */
class AudioAnalysisTest {

    private val rate = 44100

    private fun sinePcm(frequency: Double, seconds: Double, amplitude: Double = 0.9, sampleRate: Int = rate): AudioPcm {
        val frames = (seconds * sampleRate).toInt()
        val samples = ShortArray(frames) { i ->
            (amplitude * Short.MAX_VALUE * sin(2 * PI * frequency * i / sampleRate)).toInt().toShort()
        }
        return AudioPcm(samples, sampleRate, channels = 1)
    }

    private fun pcmOf(vararg segments: Pair<Double, Double>, sampleRate: Int = rate): AudioPcm {
        // (frequency, seconds) segments; frequency 0 = silence.
        val total = segments.sumOf { (it.second * sampleRate).toInt() }
        val samples = ShortArray(total)
        var offset = 0
        for ((frequency, seconds) in segments) {
            val frames = (seconds * sampleRate).toInt()
            for (i in 0 until frames) {
                samples[offset + i] = if (frequency == 0.0) {
                    0
                } else {
                    (0.9 * Short.MAX_VALUE * sin(2 * PI * frequency * (offset + i).toDouble() / sampleRate)).toInt().toShort()
                }
            }
            offset += frames
        }
        return AudioPcm(samples, sampleRate, channels = 1)
    }

    // Band dominance is a property of the raw filter bank: the public
    // analysis normalizes every band independently (hyperframes pattern),
    // which intentionally erases cross-band level differences.
    @Test
    fun bassDominatesForLowSine() {
        val raw = splitBandEnergies(sinePcm(60.0, 0.5), fps = 25)
        assertTrue(raw.frameCount() > 0)
        val bass = raw.bass.average()
        val mid = raw.mid.average()
        val treble = raw.treble.average()
        assertTrue(bass > mid * 2, "bass $bass should dominate mid $mid for a 60 Hz sine")
        assertTrue(bass > treble * 2, "bass $bass should dominate treble $treble for a 60 Hz sine")
    }

    @Test
    fun midDominatesForMidSine() {
        val raw = splitBandEnergies(sinePcm(1000.0, 0.5), fps = 25)
        val mid = raw.mid.average()
        val bass = raw.bass.average()
        val treble = raw.treble.average()
        assertTrue(mid > bass * 2, "mid $mid should dominate bass $bass for a 1 kHz sine")
        assertTrue(mid > treble, "mid $mid should dominate treble $treble for a 1 kHz sine")
    }

    @Test
    fun trebleDominatesForHighSine() {
        val raw = splitBandEnergies(sinePcm(6000.0, 0.5), fps = 25)
        val treble = raw.treble.average()
        val bass = raw.bass.average()
        val mid = raw.mid.average()
        assertTrue(treble > bass * 2, "treble $treble should dominate bass $bass for a 6 kHz sine")
        assertTrue(treble > mid * 2, "treble $treble should dominate mid $mid for a 6 kHz sine")
    }

    @Test
    fun bandsNormalizeIndependently() {
        // A quiet 60 Hz sine next to a loud 6 kHz sine: each band still
        // reaches its own 1.0, so quiet material drives the visuals too.
        val analysis = analyzeAudio(sinePcm(60.0, 0.5, amplitude = 0.05), fps = 25)
        assertEquals(1f, analysis.bass.max(), 0.01f)
        val loud = analyzeAudio(sinePcm(6000.0, 0.5, amplitude = 0.9), fps = 25)
        assertEquals(1f, loud.treble.max(), 0.01f)
    }

    private fun RawBandEnergies.frameCount(): Int = amplitude.size

    @Test
    fun stereoMixesDownLikeMono() {
        val mono = analyzeAudio(sinePcm(60.0, 0.3), fps = 25)
        val frames = (0.3 * rate).toInt()
        val stereo = ShortArray(frames * 2) { i -> sinePcm(60.0, 0.3).samples[i / 2] }
        val mixed = analyzeAudio(AudioPcm(stereo, rate, channels = 2), fps = 25)
        assertEquals(mono.frameCount, mixed.frameCount)
        for (i in mono.bass.indices) {
            assertEquals(mono.bass[i], mixed.bass[i], 0.01f, "frame $i differs after stereo mixdown")
        }
    }

    @Test
    fun amplitudeEnvelopeFollowsLoudness() {
        val analysis = analyzeAudio(pcmOf(0.0 to 0.4, 300.0 to 0.4), fps = 25)
        val silentFrames = analysis.amplitude.take(8)
        val loudFrames = analysis.amplitude.takeLast(8)
        assertTrue(silentFrames.all { it < 0.15f }, "silent head must stay low: $silentFrames")
        assertTrue(loudFrames.all { it > 0.5f }, "loud tail must stay high: $loudFrames")
        assertEquals(1f, analysis.amplitude.max(), 0.01f)
    }

    @Test
    fun analysisIsDeterministic() {
        val pcm = pcmOf(80.0 to 0.3, 0.0 to 0.2, 3000.0 to 0.3)
        val first = analyzeAudio(pcm, fps = 30)
        val second = analyzeAudio(pcm, fps = 30)
        assertTrue(first.bass.contentEquals(second.bass))
        assertTrue(first.mid.contentEquals(second.mid))
        assertTrue(first.treble.contentEquals(second.treble))
        assertTrue(first.amplitude.contentEquals(second.amplitude))
    }

    @Test
    fun frameMappingRoundTrips() {
        val analysis = analyzeAudio(sinePcm(440.0, 1.0), fps = 25)
        assertEquals(1000L, analysis.durationMs, "duration follows the PCM")
        assertEquals(25, analysis.frameCount, "one second at 25 fps")
        assertEquals(0, analysis.frameAtMs(0))
        assertEquals(12, analysis.frameAtMs(500))
        assertEquals(24, analysis.frameAtMs(5000), "clamped to the last frame")
        assertEquals(40L, analysis.msAtFrame(1))
    }

    @Test
    fun emptyPcmAnalyzesToEmpty() {
        val analysis = analyzeAudio(AudioPcm(ShortArray(0), rate, channels = 1), fps = 25)
        assertEquals(0, analysis.frameCount)
        assertEquals(0L, analysis.durationMs)
        assertTrue(energyCutPeaks(analysis, count = 3).isEmpty())
    }

    @Test
    fun peaksFindLoudBursts() {
        // Two 90 Hz bursts at 1.0 s and 3.0 s inside a quiet 1 kHz bed.
        val pcm = pcmOf(1000.0 to 0.02, 0.0 to 0.98, 90.0 to 0.25, 0.0 to 1.52, 90.0 to 0.25, 0.0 to 0.98, 1000.0 to 0.02)
        val analysis = analyzeAudio(pcm, fps = 25)
        val peaks = energyCutPeaks(analysis, count = 2, minGapMs = 500, edgeGuardMs = 300)
        assertEquals(2, peaks.size, "both bursts surface as peaks: $peaks")
        assertTrue(kotlin.math.abs(peaks[0] - 1100) < 250, "first peak ${peaks[0]} near 1.1 s")
        assertTrue(kotlin.math.abs(peaks[1] - 3000) < 250, "second peak ${peaks[1]} near 3.0 s")
        assertTrue(peaks[0] < peaks[1], "peaks come back in time order")
    }

    @Test
    fun peaksRespectEdgeGuardAndCount() {
        val pcm = pcmOf(0.0 to 0.05, 90.0 to 0.2, 0.0 to 1.55, 90.0 to 0.2, 0.0 to 0.6)
        val analysis = analyzeAudio(pcm, fps = 25)
        // The first burst sits inside the edge guard and must be skipped.
        val peaks = energyCutPeaks(analysis, count = 3, minGapMs = 300, edgeGuardMs = 400)
        assertEquals(1, peaks.size, "edge burst excluded: $peaks")
        assertTrue(peaks[0] > 1000, "kept peak ${peaks[0]} is the late burst")
    }

    @Test
    fun peaksMinGapKeepsNeighborsApart() {
        val pcm = pcmOf(0.0 to 0.5, 90.0 to 0.15, 0.0 to 0.2, 90.0 to 0.15, 0.0 to 0.5)
        val analysis = analyzeAudio(pcm, fps = 25)
        val peaks = energyCutPeaks(analysis, count = 2, minGapMs = 600, edgeGuardMs = 100)
        assertEquals(1, peaks.size, "two bursts 0.35 s apart collapse under a 600 ms gap: $peaks")
    }
}
