package com.pusios.pots.media

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

// Audio analysis for the audio-reactive clip (spec §11): band envelopes
// (bass / mid / treble) and the overall amplitude, one value per render
// frame. Pure deterministic sample math in commonMain — no FFT, no LLM,
// no network. The band split is a pair of cascaded one-pole low-pass
// filters (150 Hz / 3 kHz, −40 dB/dec). Bands are compared in the energy
// domain (mean absolute per window), because subtracting the filtered
// signals sample-by-sample leaks low frequencies through phase shift.
// Each band keeps its own envelope follower (instant attack, ~150 ms
// decay) and is normalized independently, so quiet tracks drive the
// visuals just as strongly as loud ones.

/** Per-frame band envelopes in 0..1, normalized independently per band. */
class AudioAnalysis(
    val fps: Int,
    val bass: FloatArray,
    val mid: FloatArray,
    val treble: FloatArray,
    val amplitude: FloatArray,
    val durationMs: Long,
) {
    init {
        require(fps > 0) { "fps must be positive" }
        require(bass.size == amplitude.size && mid.size == amplitude.size && treble.size == amplitude.size) {
            "all bands must hold one value per frame"
        }
    }

    val frameCount: Int get() = amplitude.size

    /** Frame index for a track position; clamps into the frame range. */
    fun frameAtMs(ms: Long): Int {
        if (frameCount == 0) return 0
        return (ms * fps / 1000L).toInt().coerceIn(0, frameCount - 1)
    }

    /** Track position of a frame start. */
    fun msAtFrame(frame: Int): Long = frame * 1000L / fps
}

private const val BASS_CUTOFF_HZ = 150.0
private const val MID_CUTOFF_HZ = 3000.0

/** Envelope follower release: fast attack, ~150 ms decay for a beat feel. */
private const val FOLLOWER_TAU_SECONDS = 0.15

/** Raw per-frame mean absolute band energies, before normalization. */
internal class RawBandEnergies(
    val bass: FloatArray,
    val mid: FloatArray,
    val treble: FloatArray,
    val amplitude: FloatArray,
)

/**
 * The one-pole filter bank: one mean-absolute energy value per render frame
 * for each band and for the whole signal. Exposed for tests — [analyzeAudio]
 * adds the envelope follower and the per-band normalization on top.
 */
internal fun splitBandEnergies(pcm: AudioPcm, fps: Int): RawBandEnergies {
    require(fps > 0) { "fps must be positive" }
    if (pcm.frameCount == 0) {
        return RawBandEnergies(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
    }

    val samples = pcm.samples
    val channels = pcm.channels
    val windowSamples = (pcm.sampleRate / fps).coerceAtLeast(1)
    val frameCount = pcm.frameCount / windowSamples

    // Two cascaded one-pole low-passes per cutoff (−40 dB/dec) keep the
    // bands meaningfully apart without an FFT.
    val alphaBass = (1 - exp(-2 * kotlin.math.PI * BASS_CUTOFF_HZ / pcm.sampleRate)).toFloat()
    val alphaMid = (1 - exp(-2 * kotlin.math.PI * MID_CUTOFF_HZ / pcm.sampleRate)).toFloat()

    val bass = FloatArray(frameCount)
    val mid = FloatArray(frameCount)
    val treble = FloatArray(frameCount)
    val amplitude = FloatArray(frameCount)

    var lpBass1 = 0f
    var lpBass2 = 0f
    var lpMid1 = 0f
    var lpMid2 = 0f
    var frame = 0
    var accBass = 0f
    var accMidPath = 0f
    var accAmp = 0f
    var inWindow = 0

    for (index in 0 until samples.size step channels) {
        // Mix down to one mono sample in [-1, 1].
        var sum = 0
        for (c in 0 until channels) sum += samples[index + c].toInt()
        val x = sum / channels / 32768f

        lpBass1 += alphaBass * (x - lpBass1)
        lpBass2 += alphaBass * (lpBass1 - lpBass2)
        lpMid1 += alphaMid * (x - lpMid1)
        lpMid2 += alphaMid * (lpMid1 - lpMid2)
        accBass += abs(lpBass2)
        accMidPath += abs(lpMid2)
        accAmp += abs(x)
        inWindow++

        if (inWindow >= windowSamples && frame < frameCount) {
            val norm = 1f / inWindow
            val bassEnergy = accBass * norm
            val midPathEnergy = accMidPath * norm
            val ampEnergy = accAmp * norm
            // Energy-domain subtraction: mid is what the 3 kHz path carries
            // beyond bass; treble is what the full signal carries beyond the
            // 3 kHz path. Both clamp at zero.
            bass[frame] = bassEnergy
            mid[frame] = maxOf(0f, midPathEnergy - bassEnergy)
            treble[frame] = maxOf(0f, ampEnergy - midPathEnergy)
            amplitude[frame] = ampEnergy
            accBass = 0f
            accMidPath = 0f
            accAmp = 0f
            inWindow = 0
            frame++
        }
    }
    return RawBandEnergies(bass, mid, treble, amplitude)
}

/**
 * Analyzes decoded PCM into per-frame envelopes at [fps]. Progress reports
 * 0..1; returning false cancels with [AudioEditException.Cancelled].
 */
fun analyzeAudio(pcm: AudioPcm, fps: Int, onProgress: AudioEditProgress? = null): AudioAnalysis {
    require(fps > 0) { "fps must be positive" }
    if (pcm.frameCount == 0) {
        return AudioAnalysis(fps, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), 0L)
    }

    val raw = splitBandEnergies(pcm, fps)
    val bass = raw.bass
    val mid = raw.mid
    val treble = raw.treble
    val amplitude = raw.amplitude

    // Cancellation checks ride the coarse frame grid — analysis is fast and
    // the caller's coroutine owns the real lifetime.
    if (onProgress != null) {
        val step = (amplitude.size / 20).coerceAtLeast(1)
        for (i in amplitude.indices step step) {
            if (!onProgress(i.toFloat() / amplitude.size)) throw AudioEditException.Cancelled()
        }
    }

    followAndNormalize(bass, fps)
    followAndNormalize(mid, fps)
    followAndNormalize(treble, fps)
    followAndNormalize(amplitude, fps)
    onProgress?.let { if (!it(1f)) throw AudioEditException.Cancelled() }

    return AudioAnalysis(fps, bass, mid, treble, amplitude, pcm.durationMs)
}

/** Instant-attack / exp-decay follower, then independent 0..1 normalization. */
private fun followAndNormalize(values: FloatArray, fps: Int) {
    if (values.isEmpty()) return
    val decay = exp(-(1.0 / fps) / FOLLOWER_TAU_SECONDS).toFloat()
    var env = 0f
    var peak = 0f
    for (i in values.indices) {
        env = max(values[i], env * decay)
        values[i] = env
        if (env > peak) peak = env
    }
    if (peak > 1e-6f) {
        for (i in values.indices) values[i] = (values[i] / peak).coerceIn(0f, 1f)
    }
}

/**
 * The strongest energy moments of the track, for aligning scene cuts
 * (spec §11: cuts line up with energy peaks). Local maxima of a
 * bass-weighted composite energy are picked greedily by strength with a
 * minimum gap, skipping the head/tail guard so cuts never land at the very
 * edges. Deterministic: equal energies resolve to the earlier frame.
 */
fun energyCutPeaks(analysis: AudioAnalysis, count: Int, minGapMs: Long = 600, edgeGuardMs: Long = 400): List<Long> {
    if (count <= 0 || analysis.frameCount == 0) return emptyList()
    val n = analysis.frameCount
    val energy = FloatArray(n) { i -> 0.6f * analysis.amplitude[i] + 0.4f * analysis.bass[i] }
    var sum = 0f
    for (value in energy) sum += value
    val meanEnergy = sum / n

    data class Candidate(val frame: Int, val value: Float)

    val candidates = ArrayList<Candidate>()
    for (i in 1 until n - 1) {
        // A peak must stand out from the track's average energy — a flat
        // track has no real peaks, and the planner then falls back to an
        // even cut grid.
        if (energy[i] > energy[i - 1] && energy[i] >= energy[i + 1] && energy[i] >= meanEnergy * 1.15f) {
            candidates.add(Candidate(i, energy[i]))
        }
    }
    candidates.sortWith(compareByDescending<Candidate> { it.value }.thenBy { it.frame })

    val minGapFrames = (minGapMs * analysis.fps / 1000L).toInt().coerceAtLeast(1)
    val edgeFrames = (edgeGuardMs * analysis.fps / 1000L).toInt()
    val picked = ArrayList<Int>()
    for (candidate in candidates) {
        if (candidate.frame < edgeFrames || candidate.frame >= n - edgeFrames) continue
        if (picked.any { kotlin.math.abs(it - candidate.frame) < minGapFrames }) continue
        picked.add(candidate.frame)
        if (picked.size >= count) break
    }
    return picked.sorted().map { analysis.msAtFrame(it) }
}
