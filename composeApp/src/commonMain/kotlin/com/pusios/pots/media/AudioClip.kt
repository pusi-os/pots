package com.pusios.pots.media

import kotlin.math.abs

// Audio-reactive clip (spec §11): one button turns a canvas audio element
// into a music video. The whole flow is deterministic and local — the
// track's band envelopes ([AudioAnalysis]) drive scene cuts (energy peaks),
// a bass-driven zoom pulse, treble-driven soft overexposure flashes and a
// warm tint on loud passages. No LLM, no provider calls, 0 ₽.
//
// Rendering rides the same ffmpeg tooling as the video editor and the
// sequence assembler ([VideoProcessor.assembleAudioClip]): everything
// testable — scene planning, junction math, zoom expressions, overlay
// windows and the ffmpeg command — lives here as pure common code.
//
// The effect mapping follows the hyperframes audio-reactive pattern: bass
// → scale pulse, treble → glow, overall amplitude → color temperature;
// equalizer bars and spectrum clichés are deliberately out.

/** Visual style presets (spec §11: three at start, no more). */
enum class AudioClipStyle(val labelRu: String, val descriptionRu: String) {
    Pulse("Пульс", "Минимальный: кадр пульсирует в такт басу"),
    Glow("Свечение", "Мягкие пересветы от верхов и тёплый дрейф от энергии"),
    Cinema("Кино", "Плавный зум, виньетка и затемнения между сценами"),
}

/** Scene transitions fade through black (spec §11: склейка или затемнение). */
const val AUDIO_CLIP_FADE_MS = 300L

/** A junction needs room on both sides; shorter scenes fall back to a cut. */
private const val AUDIO_CLIP_MIN_SIDE_MS = AUDIO_CLIP_FADE_MS + 100L

/** Overlay flash colors (opaque RGB; alpha rides the window itself). */
const val AUDIO_CLIP_FLASH_WHITE: Int = 0xFFFFFF
const val AUDIO_CLIP_WARM_TINT: Int = 0xFFB36B
const val AUDIO_CLIP_WARM_ALPHA = 0.10f

/** Caps keep the filtergraph small on long tracks. */
private const val AUDIO_CLIP_MAX_BEATS_PER_SCENE = 48
private const val AUDIO_CLIP_MAX_FLASHES = 32
private const val AUDIO_CLIP_MAX_WARM_WINDOWS = 16
private const val AUDIO_CLIP_BASS_BEAT_MIN = 0.45f
private const val AUDIO_CLIP_TREBLE_FLASH_MIN = 0.5f
private const val AUDIO_CLIP_AMPLITUDE_TINT_MIN = 0.72f

/** One visual source picked for the clip (a canvas/journal element). */
data class AudioClipSceneSource(
    val elementId: String?,
    val relPath: String,
    val isVideo: Boolean,
)

/**
 * App-storage view of one clip job: the track, the ordered visual sources
 * (empty = abstract background), the style preset and the output geometry.
 * [previewLimitMs] caps the render to the first N milliseconds (the sheet's
 * fast 10 s preview).
 */
data class AudioClipAssemblyRequest(
    val trackRelPath: String,
    val trackDurationMs: Long,
    val scenes: List<AudioClipSceneSource>,
    val style: AudioClipStyle,
    val width: Int,
    val height: Int,
    val fps: Int,
    val previewLimitMs: Long? = null,
)

/** One scene's time span on the TRACK timeline; sourceIndex −1 = background. */
data class AudioClipSceneSpan(
    val sourceIndex: Int,
    val startMs: Long,
    val endMs: Long,
)

/** A junction between two scenes on the VISIBLE timeline. */
sealed class AudioClipJunction {
    abstract val fadeBlack: Boolean
    abstract val durationMs: Long
    abstract val visibleOffsetMs: Long

    data object Cut : AudioClipJunction() {
        override val fadeBlack = false
        override val durationMs = 0L
        override val visibleOffsetMs = 0L
    }

    data class FadeBlack(override val durationMs: Long, override val visibleOffsetMs: Long) : AudioClipJunction() {
        override val fadeBlack = true
    }
}

/** One reactive overlay window on the VISIBLE timeline. */
data class AudioClipFlash(
    val colorArgb: Int,
    val startMs: Long,
    val endMs: Long,
    val alpha: Float,
)

/** Everything the ffmpeg command needs, independent of app-storage layout. */
data class AudioClipRenderSpec(
    val inputs: List<SequenceInput>,
    val junctions: List<AudioClipJunction>,
    val visibleDurationMs: Long,
    val renderDurationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val audioPath: String,
    val zoomExprs: List<String>,
    val flashes: List<AudioClipFlash>,
    val vignettePath: String?,
)

/** The resolved clip: spans, junctions, effect data and render duration. */
data class AudioClipPlan(
    val spans: List<AudioClipSceneSpan>,
    val junctions: List<AudioClipJunction>,
    val visibleDurationMs: Long,
    val renderDurationMs: Long,
    val sceneZoomExprs: List<String>,
    val flashWindows: List<AudioClipFlash>,
    val needsVignette: Boolean,
)

/** Builds scene spans from cut points; every source gets exactly one span. */
fun spansFromCuts(cuts: List<Long>, totalDurationMs: Long, sceneCount: Int): List<AudioClipSceneSpan> {
    require(sceneCount > 0) { "sceneCount must be positive" }
    require(totalDurationMs > 0) { "totalDurationMs must be positive" }
    require(cuts.size == sceneCount - 1) { "cuts must cover every gap between scenes" }
    val boundaries = listOf(0L) + cuts.sorted() + listOf(totalDurationMs)
    return boundaries.zipWithNext().mapIndexed { index, (start, end) ->
        AudioClipSceneSpan(sourceIndex = index, startMs = start, endMs = end)
    }
}

/**
 * Resolves a fade-through-black per junction; a scene too short to host the
 * fade degrades that junction to a cut instead of failing the render (the
 * sequence assembler's honesty). Offsets count along the shrunk visible
 * timeline.
 */
fun resolveAudioClipJunctions(spans: List<AudioClipSceneSpan>): List<AudioClipJunction> {
    require(spans.isNotEmpty()) { "spans must not be empty" }
    val junctions = ArrayList<AudioClipJunction>(spans.size - 1)
    var visibleEndMs = spans[0].endMs - spans[0].startMs
    for (index in 1 until spans.size) {
        val previous = spans[index - 1].endMs - spans[index - 1].startMs
        val current = spans[index].endMs - spans[index].startMs
        if (previous >= AUDIO_CLIP_MIN_SIDE_MS && current >= AUDIO_CLIP_MIN_SIDE_MS) {
            junctions.add(AudioClipJunction.FadeBlack(AUDIO_CLIP_FADE_MS, visibleEndMs - AUDIO_CLIP_FADE_MS))
            visibleEndMs += current - AUDIO_CLIP_FADE_MS
        } else {
            junctions.add(AudioClipJunction.Cut)
            visibleEndMs += current
        }
    }
    return junctions
}

/** Visible duration: spans minus every fade overlap. */
fun audioClipVisibleDurationMs(spans: List<AudioClipSceneSpan>, junctions: List<AudioClipJunction>): Long {
    require(spans.isNotEmpty()) { "spans must not be empty" }
    val total = spans.sumOf { it.endMs - it.startMs }
    return total - junctions.sumOf { it.durationMs }
}

/**
 * Plans the whole clip from the request and the track analysis. Scene cuts
 * align to the strongest energy peaks (spec §11); a uniform track without
 * real peaks gets evenly spread cuts so scenes still rotate. The preview
 * cap only shortens the render — planning always covers the full track.
 */
fun planAudioClip(request: AudioClipAssemblyRequest, analysis: AudioAnalysis): AudioClipPlan {
    require(request.trackDurationMs > 0) { "trackDurationMs must be positive" }
    val totalMs = request.trackDurationMs
    val fps = request.fps

    val spans: List<AudioClipSceneSpan>
    if (request.scenes.isEmpty()) {
        spans = listOf(AudioClipSceneSpan(sourceIndex = -1, startMs = 0, endMs = totalMs))
    } else {
        val needed = request.scenes.size - 1
        val minGap = maxOf(600L, totalMs / (request.scenes.size * 3L))
        val peaks = energyCutPeaks(analysis, count = needed, minGapMs = minGap, edgeGuardMs = 500)
        val cuts = fillCutsEvenly(peaks, totalMs, needed)
        spans = spansFromCuts(cuts, totalMs, request.scenes.size)
    }

    val junctions = resolveAudioClipJunctions(spans)
    val visibleMs = audioClipVisibleDurationMs(spans, junctions)
    val renderMs = request.previewLimitMs?.let { minOf(visibleMs, it) } ?: visibleMs

    val zoomExprs = spans.mapIndexed { index, span ->
        sceneZoomExpression(request.style, span.startMs, span.endMs, fps, analysis, sceneIndex = index, sceneCount = spans.size)
    }

    val flashes = audioClipFlashWindows(analysis, fadeShiftMs = 0, style = request.style).mapNotNull { window ->
        val shift = fadeShiftBeforeMs(window.startMs, spans, junctions)
        val start = window.startMs - shift
        val end = window.endMs - shift
        if (end > start) window.copy(startMs = start.coerceAtLeast(0), endMs = end) else null
    }

    return AudioClipPlan(
        spans = spans,
        junctions = junctions,
        visibleDurationMs = visibleMs,
        renderDurationMs = renderMs,
        sceneZoomExprs = zoomExprs,
        flashWindows = flashes,
        needsVignette = request.style == AudioClipStyle.Cinema,
    )
}

/**
 * Cut points for [needed] scenes. Real energy peaks win; when the track has
 * fewer detectable peaks (a uniform ambient piece), the cuts spread evenly
 * so the scenes still rotate. Deterministic either way.
 */
private fun fillCutsEvenly(peaks: List<Long>, totalMs: Long, needed: Int): List<Long> {
    if (needed <= 0) return emptyList()
    if (peaks.size >= needed) return peaks.take(needed).sorted()
    return (1..needed).map { totalMs * it / (needed + 1) }
}

/** Visible-time shift accumulated before a track position (one fade per passed junction). */
private fun fadeShiftBeforeMs(trackMs: Long, spans: List<AudioClipSceneSpan>, junctions: List<AudioClipJunction>): Long {
    var shift = 0L
    junctions.forEachIndexed { index, junction ->
        if (junction.fadeBlack && spans[index].endMs <= trackMs) shift += junction.durationMs
    }
    return shift
}

/**
 * The per-scene zoompan `z` expression. Pulse and Glow ride decaying bass
 * pulses at the scene's beats; Cinema eases linearly in or out across the
 * whole scene (direction alternates). A beatless scene stays flat — zoompan
 * is skipped for the literal "1".
 */
fun sceneZoomExpression(
    style: AudioClipStyle,
    sceneStartMs: Long,
    sceneEndMs: Long,
    fps: Int,
    analysis: AudioAnalysis,
    sceneIndex: Int = 0,
    sceneCount: Int = 1,
): String {
    val sceneFrames = ((sceneEndMs - sceneStartMs) * fps / 1000L).toInt()
    if (sceneFrames <= 0) return "1"
    return when (style) {
        AudioClipStyle.Cinema -> {
            val ramp = if (sceneIndex % 2 == 0) "on/$sceneFrames" else "1-(on/$sceneFrames)"
            "min(1.05,1+0.05*($ramp))"
        }

        else -> {
            val beats = sceneBeatSeconds(analysis, sceneStartMs, sceneEndMs)
            if (beats.isEmpty()) return "1"
            val terms = beats.map { beat ->
                val t = formatZoomSeconds(beat)
                // gte(on/fps, beat) gates the pulse: it fires at the beat, not before.
                "exp(-7*max(0,on/$fps-$t))*gte(on/$fps,$t)"
            }
            // ffmpeg's max() wants two arguments; a lone term rides parentheses.
            val combined = terms.drop(1).fold(terms.first()) { acc, term -> "max($acc,$term)" }
            when (style) {
                AudioClipStyle.Pulse -> "min(1.06,1+0.06*($combined))"
                AudioClipStyle.Glow -> "min(1.04,1.005+0.03*($combined))"
                AudioClipStyle.Cinema -> error("unreachable")
            }
        }
    }
}

private fun formatZoomSeconds(seconds: Double): String {
    val ms = kotlin.math.round(seconds * 1000).toLong()
    return "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
}

/**
 * Bass beats inside one scene, as scene-local seconds for the zoompan
 * `on/fps` clock. Beats are bass-envelope onsets (the rising edge crossing
 * the threshold, with hysteresis rearm), so the pulse fires the moment the
 * hit lands — and a burst that triggers a scene cut pulses in the scene
 * that contains its onset. Onsets are attributed by their millisecond, not
 * by frame index: a boundary frame can straddle two scenes, but its time
 * belongs to exactly one.
 */
private fun sceneBeatSeconds(analysis: AudioAnalysis, sceneStartMs: Long, sceneEndMs: Long): List<Double> {
    if (analysis.frameCount == 0 || sceneEndMs <= sceneStartMs) return emptyList()
    return bassOnsetFrames(analysis)
        .map { analysis.msAtFrame(it) }
        .filter { it >= sceneStartMs && it < sceneEndMs }
        .take(AUDIO_CLIP_MAX_BEATS_PER_SCENE)
        .map { (it - sceneStartMs) / 1000.0 }
}

/**
 * Rising edges of the bass envelope crossing [AUDIO_CLIP_BASS_BEAT_MIN],
 * rearmed once the envelope falls below 60 % of the threshold. One onset
 * per burst, deterministic.
 */
private fun bassOnsetFrames(analysis: AudioAnalysis): List<Int> {
    val onsets = ArrayList<Int>()
    var armed = true
    for (i in analysis.bass.indices) {
        val value = analysis.bass[i]
        if (armed && value >= AUDIO_CLIP_BASS_BEAT_MIN) {
            onsets.add(i)
            armed = false
        } else if (!armed && value < AUDIO_CLIP_BASS_BEAT_MIN * 0.6f) {
            armed = true
        }
    }
    return onsets
}

/**
 * Reactive overlay windows (spec §11): treble peaks become soft white
 * overexposure flashes (Glow style), sustained loudness becomes a warm
 * tint (all styles). Windows sit on the track timeline; [fadeShiftMs]
 * shifts them uniformly for the shrunk visible timeline (the planner maps
 * each window through the real junction shifts instead).
 */
fun audioClipFlashWindows(analysis: AudioAnalysis, fadeShiftMs: Long, style: AudioClipStyle): List<AudioClipFlash> {
    if (analysis.frameCount == 0) return emptyList()
    val windows = ArrayList<AudioClipFlash>()

    if (style == AudioClipStyle.Glow) {
        data class Peak(val frame: Int, val value: Float)

        val candidates = ArrayList<Peak>()
        for (i in 1 until analysis.frameCount - 1) {
            val value = analysis.treble[i]
            if (value >= AUDIO_CLIP_TREBLE_FLASH_MIN && value > analysis.treble[i - 1] && value >= analysis.treble[i + 1]) {
                candidates.add(Peak(i, value))
            }
        }
        candidates.sortWith(compareByDescending<Peak> { it.value }.thenBy { it.frame })
        val minGapFrames = (300L * analysis.fps / 1000L).toInt().coerceAtLeast(1)
        val picked = ArrayList<Peak>()
        for (peak in candidates) {
            if (picked.any { abs(it.frame - peak.frame) < minGapFrames }) continue
            picked.add(peak)
            if (picked.size >= AUDIO_CLIP_MAX_FLASHES) break
        }
        for (peak in picked) {
            val atMs = analysis.msAtFrame(peak.frame)
            windows.add(
                AudioClipFlash(
                    colorArgb = AUDIO_CLIP_FLASH_WHITE,
                    startMs = atMs - 80,
                    endMs = atMs + 220,
                    alpha = (0.18f + 0.22f * peak.value).coerceAtMost(0.4f),
                ),
            )
        }
    }

    // Warm tint while the overall amplitude stays loud.
    val frameMs = 1000L / analysis.fps
    var runStart = -1
    var gapTolerance = 0
    val warmCap = AUDIO_CLIP_MAX_WARM_WINDOWS
    val warmRuns = ArrayList<IntRange>()
    for (i in analysis.amplitude.indices) {
        val loud = analysis.amplitude[i] >= AUDIO_CLIP_AMPLITUDE_TINT_MIN
        if (loud) {
            if (runStart < 0) runStart = i
            gapTolerance = 0
        } else if (runStart >= 0) {
            gapTolerance++
            if (gapTolerance > 3) {
                warmRuns.add(runStart..(i - gapTolerance))
                runStart = -1
                gapTolerance = 0
            }
        }
    }
    if (runStart >= 0) warmRuns.add(runStart until analysis.amplitude.size)
    val minRunFrames = (250L * analysis.fps / 1000L).toInt().coerceAtLeast(1)
    warmRuns.filter { it.last - it.first + 1 >= minRunFrames }
        .sortedByDescending { run -> run.maxOf { analysis.amplitude[it] } }
        .take(warmCap)
        .mapTo(windows) { run ->
            AudioClipFlash(
                colorArgb = AUDIO_CLIP_WARM_TINT,
                startMs = analysis.msAtFrame(run.first),
                endMs = analysis.msAtFrame(run.last) + frameMs,
                alpha = AUDIO_CLIP_WARM_ALPHA,
            )
        }

    return windows
        .map { it.copy(startMs = it.startMs - fadeShiftMs, endMs = it.endMs - fadeShiftMs) }
        .filter { it.endMs > it.startMs }
        .map { it.copy(startMs = it.startMs.coerceAtLeast(0)) }
        .sortedWith(compareBy<AudioClipFlash> { it.startMs }.thenBy { it.endMs })
}

/**
 * Builds the clip command. Every scene normalizes to the output geometry,
 * then rides a per-scene zoompan with its envelope expression; junctions
 * chain `xfade=fadeblack` (or `concat` for degraded cuts); reactive overlay
 * windows arrive as short lavfi color inputs with alpha fades gated by
 * `enable` on the visible timeline; the Cinema vignette overlays last. The
 * track itself is the soundtrack, trimmed to the render length with a
 * half-second fade-out.
 */
fun ffmpegAudioClipArgs(spec: AudioClipRenderSpec, output: String): List<String> {
    require(spec.inputs.isNotEmpty()) { "inputs must not be empty" }
    require(spec.renderDurationMs > 0) { "renderDurationMs must be positive" }
    require(spec.zoomExprs.size == spec.inputs.size) { "every scene needs a zoom expression" }

    val sceneCount = spec.inputs.size
    val trackInput = sceneCount
    val firstFlashInput = sceneCount + 1
    val vignetteInput = firstFlashInput + spec.flashes.size

    val filter = buildList {
        // Normalize + zoompan per scene.
        spec.inputs.forEachIndexed { index, input ->
            val zoom = spec.zoomExprs[index]
            add(
                "[$index:v]scale=${spec.width}:${spec.height}:force_original_aspect_ratio=decrease," +
                    "pad=${spec.width}:${spec.height}:(ow-iw)/2:(oh-ih)/2:color=black," +
                    "fps=${spec.fps},setsar=1,format=yuv420p[src$index]",
            )
            if (zoom == "1") {
                add("[src$index]null,format=yuv420p[v$index]")
            } else {
                add(
                    "[src$index]zoompan=z='$zoom':x='iw/2-(iw/zoom)/2':y='ih/2-(ih/zoom)/2':" +
                        "d=1:s=${spec.width}x${spec.height}:fps=${spec.fps},format=yuv420p[v$index]",
                )
            }
        }

        // Junction chain on the visible timeline.
        var current = "[v0]"
        spec.junctions.forEachIndexed { index, junction ->
            val next = "[v${index + 1}]"
            val outLabel = if (index == spec.junctions.lastIndex) "[xlast]" else "[x$index]"
            when (junction) {
                is AudioClipJunction.Cut -> add("$current${next}concat=n=2:v=1:a=0$outLabel")

                is AudioClipJunction.FadeBlack -> add(
                    "$current${next}xfade=transition=fadeblack:" +
                        "duration=${formatSecondsForFfmpeg(junction.durationMs)}:" +
                        "offset=${formatSecondsForFfmpeg(junction.visibleOffsetMs)}$outLabel",
                )
            }
            current = outLabel
        }

        // Reactive overlays: alpha-faded color flashes gated to their window.
        var picture = if (spec.junctions.isEmpty()) "[v0]" else "[xlast]"
        spec.flashes.forEachIndexed { index, flash ->
            val inputIndex = firstFlashInput + index
            val startSec = formatSecondsForFfmpeg(flash.startMs)
            val endSec = formatSecondsForFfmpeg(flash.endMs)
            val fadeMs = minOf(80L, (flash.endMs - flash.startMs) / 2)
            val fadeSec = formatSecondsForFfmpeg(fadeMs)
            val fadeOutStart = formatSecondsForFfmpeg(flash.endMs - fadeMs)
            add(
                "[$inputIndex:v]format=rgba," +
                    "fade=t=in:st=$startSec:d=$fadeSec:alpha=1," +
                    "fade=t=out:st=$fadeOutStart:d=$fadeSec:alpha=1[flash$index]",
            )
            val outLabel = if (index == spec.flashes.lastIndex && spec.vignettePath == null) "[vout]" else "[ov$index]"
            add("$picture[flash$index]overlay=0:0:enable='between(t,$startSec,$endSec)'$outLabel")
            picture = outLabel
        }
        if (spec.vignettePath != null) {
            add("$picture[$vignetteInput:v]overlay=0:0[vout]")
            picture = "[vout]"
        }
        if (picture != "[vout]") {
            // No overlays at all: hand the junction chain's picture straight out.
            add("$picture null[vout]")
        }
    }.joinToString(";")

    val renderSec = formatSecondsForFfmpeg(spec.renderDurationMs)
    val args = ArrayList<String>(32 + spec.flashes.size * 3)
    args.addAll(FFMPEG_PREFIX)
    args.addAll(listOf("-progress", "pipe:1"))
    spec.inputs.forEachIndexed { index, input ->
        if (input.isVideo) {
            // Looping covers scene spans longer than the source clip.
            args.addAll(listOf("-stream_loop", "-1"))
        } else {
            args.add("-loop")
            args.add("1")
        }
        args.addAll(listOf("-t", formatSecondsForFfmpeg(input.durationMs), "-i", input.path))
    }
    args.addAll(listOf("-i", spec.audioPath))
    spec.flashes.forEach { flash ->
        val rgb = (flash.colorArgb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
        args.addAll(
            listOf(
                "-f",
                "lavfi",
                "-i",
                "color=c=0x$rgb:s=${spec.width}x${spec.height}:r=${spec.fps}:d=$renderSec",
            ),
        )
    }
    if (spec.vignettePath != null) {
        args.addAll(listOf("-loop", "1", "-t", renderSec, "-i", spec.vignettePath!!))
    }
    args.addAll(listOf("-filter_complex", filter))
    args.addAll(listOf("-map", "[vout]", "-map", "$trackInput:a:0"))
    if (spec.renderDurationMs >= 1500) {
        args.addAll(listOf("-af", "afade=t=out:st=${formatSecondsForFfmpeg(spec.renderDurationMs - 500)}:d=0.500"))
    }
    args.addAll(listOf("-t", renderSec))
    args.addAll(videoEncodeFlags(hasAudio = true))
    args.add(output)
    return args
}

/** Two deterministic palette colors derived from the track bytes (FNV-1a). */
fun audioClipGradientColors(seed: ByteArray): List<Int> {
    var hash = 0x811C9DC5.toInt()
    val limit = minOf(seed.size, 4096)
    for (i in 0 until limit) {
        hash = hash xor seed[i].toInt()
        hash = hash * 0x01000193
    }
    val h1 = abs(hash % 360)
    val h2 = (h1 + 120 + abs((hash ushr 8) % 120)) % 360
    return listOf(hslToArgb(h1.toFloat(), 0.55f, 0.26f), hslToArgb(h2.toFloat(), 0.6f, 0.48f))
}

/** Diagonal gradient between the two seed colors — the abstract background. */
fun audioClipBackgroundGrid(width: Int, height: Int, seed: ByteArray): PixelGrid {
    val (c1, c2) = audioClipGradientColors(seed).let { it[0] to it[1] }
    val r1 = (c1 shr 16) and 0xFF
    val g1 = (c1 shr 8) and 0xFF
    val b1 = c1 and 0xFF
    val r2 = (c2 shr 16) and 0xFF
    val g2 = (c2 shr 8) and 0xFF
    val b2 = c2 and 0xFF
    val argb = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val t = (x.toFloat() / width + y.toFloat() / height) / 2f
            val r = (r1 + (r2 - r1) * t).toInt()
            val g = (g1 + (g2 - g1) * t).toInt()
            val b = (b1 + (b2 - b1) * t).toInt()
            argb[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return PixelGrid(width, height, argb)
}

/** Radial dark vignette (Cinema): transparent center, 45 % black corners. */
fun audioClipVignetteGrid(width: Int, height: Int): PixelGrid {
    val cx = width / 2f
    val cy = height / 2f
    val maxDistance = kotlin.math.sqrt(cx * cx + cy * cy)
    val argb = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val dx = x - cx
            val dy = y - cy
            val d = kotlin.math.sqrt(dx * dx + dy * dy) / maxDistance
            val t = ((d - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            val alpha = (0.45f * smooth * 255).toInt()
            argb[y * width + x] = (alpha shl 24)
        }
    }
    return PixelGrid(width, height, argb)
}

private fun hslToArgb(h: Float, s: Float, l: Float): Int {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = (h % 360f) / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r, g, b) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    val ri = ((r + m) * 255).toInt().coerceIn(0, 255)
    val gi = ((g + m) * 255).toInt().coerceIn(0, 255)
    val bi = ((b + m) * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
}
