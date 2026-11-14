package com.pusios.pots.media

import kotlin.math.roundToInt

// Sequence assembly: a timeline over canvas elements. Clips play in order,
// junctions between them are transitions (hyperframes pattern: always a
// transition between scenes, no jump cuts — crossfade is the default), an
// optional audio track rides under the whole result and optional subtitles
// burn into the frame. Rendering runs through the same ffmpeg tooling as the
// video editor ([VideoProcessor.assembleSequence]); everything testable —
// junction math, subtitle timecodes, SRT rendering, command building — lives
// here as pure common code.
//
// Output is a NEW `.mp4` under `media/edits/`; source elements are never
// touched. Clip sound is not mixed in v1: the result has exactly one audio
// track — the picked one, or silence.

/** Transition between two consecutive clips. Labels are shown in the sheet. */
enum class SequenceTransition(val labelRu: String, val xfadeName: String?) {
    Cut("Склейка", null),
    Crossfade("Кроссфейд", "fade"),
    Slide("Сдвиг", "slideleft"),
    Wipe("Шторка", "wipeleft"),
}

/** Transitions are short and fixed: 0.5 s reads professional without upstaging the content. */
const val SEQUENCE_TRANSITION_MS = 500L

/** Hyperframes pattern — always a transition between scenes; crossfade is the safest primary. */
val SEQUENCE_DEFAULT_TRANSITION = SequenceTransition.Crossfade

/** Image clips are still frames with a user-adjustable screen time. */
const val SEQUENCE_IMAGE_DEFAULT_MS = 3000L
const val SEQUENCE_IMAGE_MIN_MS = 1000L
const val SEQUENCE_IMAGE_MAX_MS = 10000L

/** A junction needs room on both sides; shorter clips fall back to a cut. */
private const val SEQUENCE_MIN_SIDE_MS = SEQUENCE_TRANSITION_MS + 100L

/** One clip of the sequence: a canvas element resolved to its local file. */
data class SequenceClip(
    val elementId: String,
    val relPath: String,
    val isVideo: Boolean,
    val durationMs: Long,
)

/**
 * One subtitle cue: the text plus the time span it is visible. Cues with
 * real word timecodes (a future STT result) plug in unchanged; plain text is
 * spread evenly by [splitSubtitleCues].
 */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** A junction after clamping: a hard cut or a resolved xfade with its offset. */
sealed class SequenceJunction {
    data object Cut : SequenceJunction()

    data class XFade(
        val transition: SequenceTransition,
        val durationMs: Long,
        val offsetMs: Long,
    ) : SequenceJunction()
}

/**
 * Resolves the requested transitions against the clip lengths. The xfade
 * offset counts from the start of the OUTPUT timeline, which shrinks by every
 * earlier overlap; a junction whose neighbours are too short to host the
 * transition degrades to a cut instead of failing the render.
 */
fun resolveSequenceJunctions(clips: List<SequenceClip>, transitions: List<SequenceTransition>): List<SequenceJunction> {
    require(clips.isNotEmpty()) { "clips must not be empty" }
    require(transitions.size == clips.size - 1) { "transitions must cover every gap between clips" }
    val junctions = ArrayList<SequenceJunction>(transitions.size)
    var visibleEndMs = clips[0].durationMs
    transitions.forEachIndexed { index, transition ->
        val next = clips[index + 1].durationMs
        val xfadeName = transition.xfadeName
        if (xfadeName != null && visibleEndMs >= SEQUENCE_MIN_SIDE_MS && next >= SEQUENCE_MIN_SIDE_MS) {
            junctions.add(SequenceJunction.XFade(transition, SEQUENCE_TRANSITION_MS, visibleEndMs - SEQUENCE_TRANSITION_MS))
            visibleEndMs += next - SEQUENCE_TRANSITION_MS
        } else {
            junctions.add(SequenceJunction.Cut)
            visibleEndMs += next
        }
    }
    return junctions
}

/** Total duration of the assembled clip: sum of clips minus every overlap. */
fun sequenceDurationMs(clips: List<SequenceClip>, transitions: List<SequenceTransition>): Long {
    if (clips.isEmpty()) throw VideoEditException.EmptySequence()
    val junctions = resolveSequenceJunctions(clips, transitions)
    var total = clips[0].durationMs
    clips.drop(1).forEachIndexed { index, clip ->
        total += clip.durationMs
        val junction = junctions[index]
        if (junction is SequenceJunction.XFade) total -= junction.durationMs
    }
    return total
}

/** Appends a clip; every gap gets the default transition (no jump cuts). */
fun sequenceAdd(
    clips: List<SequenceClip>,
    transitions: List<SequenceTransition>,
    clip: SequenceClip,
): Pair<List<SequenceClip>, List<SequenceTransition>> = if (clips.isEmpty()) {
    listOf(clip) to emptyList()
} else {
    (clips + clip) to (transitions + SEQUENCE_DEFAULT_TRANSITION)
}

/**
 * Removes the clip at [index]. The junction to the right of the removed
 * clip goes with it; removing the last clip drops the junction on its left,
 * so one junction always covers every remaining gap.
 */
fun sequenceRemoveAt(
    clips: List<SequenceClip>,
    transitions: List<SequenceTransition>,
    index: Int,
): Pair<List<SequenceClip>, List<SequenceTransition>> {
    val newClips = clips.toMutableList().also { it.removeAt(index) }
    val newTransitions = transitions.toMutableList().also { list ->
        if (list.isNotEmpty()) {
            if (index < list.size) list.removeAt(index) else list.removeAt(list.lastIndex)
        }
    }
    return newClips to newTransitions
}

/** Moves the clip at [from] to [to]; junction slots stay positional. */
fun sequenceMove(
    clips: List<SequenceClip>,
    transitions: List<SequenceTransition>,
    from: Int,
    to: Int,
): Pair<List<SequenceClip>, List<SequenceTransition>> {
    if (from !in clips.indices || to !in clips.indices) throw IndexOutOfBoundsException("move $from -> $to in ${clips.size} clips")
    val newClips = clips.toMutableList().also { it.add(to, it.removeAt(from)) }
    return newClips to transitions
}

/**
 * Splits plain text into subtitle cues shown "at the moment of speech".
 * Groups hold at most [maxWordsPerCue] words (hyperframes conversational
 * grouping) and break early on a sentence end; without word timecodes every
 * group gets an equal slice of [totalDurationMs].
 */
fun splitSubtitleCues(text: String, totalDurationMs: Long, maxWordsPerCue: Int = 4): List<SubtitleCue> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty() || totalDurationMs <= 0) return emptyList()
    val groups = ArrayList<List<String>>()
    var current = ArrayList<String>()
    words.forEach { word ->
        current.add(word)
        val sentenceEnd = word.last() in ".!?…"
        if (current.size >= maxWordsPerCue || sentenceEnd) {
            groups.add(current)
            current = ArrayList()
        }
    }
    if (current.isNotEmpty()) groups.add(current)
    return groups.mapIndexed { index, group ->
        SubtitleCue(
            startMs = totalDurationMs * index / groups.size,
            endMs = totalDurationMs * (index + 1) / groups.size,
            text = group.joinToString(" "),
        )
    }
}

/** "HH:MM:SS,mmm" as SRT demands. */
fun formatSrtTimestamp(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val hours = safe / 3_600_000
    val minutes = safe % 3_600_000 / 60_000
    val seconds = safe % 60_000 / 1000
    val millis = safe % 1000
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:" +
        "${seconds.toString().padStart(2, '0')},${millis.toString().padStart(3, '0')}"
}

/** Renders cues as an SRT document (1-based indexes, blank line between blocks). */
fun subtitleCuesToSrt(cues: List<SubtitleCue>): String = buildString {
    cues.forEachIndexed { index, cue ->
        append(index + 1).append('\n')
        append(formatSrtTimestamp(cue.startMs)).append(" --> ").append(formatSrtTimestamp(cue.endMs)).append('\n')
        append(cue.text).append('\n')
        if (index != cues.lastIndex) append('\n')
    }
}

/**
 * ASS force_style for the burned subtitles: white text on a 60 % dark box,
 * bottom-centered, sized off the frame height so 720p and 1080p read alike
 * (the subtitles filter scales styles by the video height).
 */
fun subtitleForceStyle(heightPx: Int): String {
    val fontSize = (heightPx * 0.045f).roundToInt()
    val marginV = (heightPx * 0.05f).roundToInt()
    return "FontName=Sans,FontSize=$fontSize,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000," +
        "BackColour=&H99000000,BorderStyle=4,Outline=1,Shadow=0,Alignment=2,MarginV=$marginV"
}

/**
 * Escapes a file path for use as the `subtitles=filename=` filter value:
 * backslashes and colons are backslash-escaped (the filter's own parser
 * unescapes them), the whole value is single-quoted so the filtergraph
 * parser tolerates spaces. App-storage paths (uuid names under `media/…`)
 * never need more than this.
 */
fun escapeFilterPath(path: String): String = "'" + path.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'") + "'"

/** One input of the render command, already resolved to an absolute path. */
data class SequenceInput(val path: String, val isVideo: Boolean, val durationMs: Long)

/**
 * App-storage view of one assembly job: clips and junctions as the user
 * arranged them, output geometry, the optional soundtrack and the optional
 * plain subtitle text (split evenly over the result at render time). The
 * processor resolves paths, probes binaries and runs ffmpeg.
 */
data class SequenceAssemblyRequest(
    val clips: List<SequenceClip>,
    val transitions: List<SequenceTransition>,
    val width: Int,
    val height: Int,
    val fps: Int,
    val audioRelPath: String? = null,
    val subtitleText: String? = null,
)

/** Everything the ffmpeg command needs, independent of app-storage layout. */
data class SequenceRenderSpec(
    val inputs: List<SequenceInput>,
    val junctions: List<SequenceJunction>,
    val totalDurationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val audioPath: String? = null,
    val srtPath: String? = null,
)

/**
 * Builds the assembly command. Every input is normalized to the same
 * resolution/fps/SAR first; junctions chain `xfade` (transitions) or
 * `concat` (explicit cuts); the optional SRT burns in last, after the
 * junctions, so captions ride the final picture. The audio track (when
 * picked) loops to cover the whole result and fades out over the last
 * half second; without a track the output is silent.
 */
fun ffmpegSequenceArgs(spec: SequenceRenderSpec, output: String): List<String> {
    require(spec.inputs.isNotEmpty()) { "inputs must not be empty" }
    require(spec.totalDurationMs > 0) { "totalDurationMs must be positive" }

    val normalize = spec.inputs.mapIndexed { index, input ->
        "[$index:v]scale=${spec.width}:${spec.height}:force_original_aspect_ratio=decrease," +
            "pad=${spec.width}:${spec.height}:(ow-iw)/2:(oh-ih)/2:color=black," +
            "fps=${spec.fps},setsar=1,format=yuv420p[v$index]"
    }

    val junctionFilters = ArrayList<String>(spec.junctions.size)
    var current = "[v0]"
    spec.junctions.forEachIndexed { index, junction ->
        val next = "[v${index + 1}]"
        val outLabel = if (index == spec.junctions.lastIndex) "[xlast]" else "[x$index]"
        junctionFilters.add(
            when (junction) {
                is SequenceJunction.Cut -> "$current${next}concat=n=2:v=1:a=0$outLabel"

                is SequenceJunction.XFade ->
                    "$current${next}xfade=transition=${junction.transition.xfadeName}:" +
                        "duration=${formatSecondsForFfmpeg(junction.durationMs)}:" +
                        "offset=${formatSecondsForFfmpeg(junction.offsetMs)}$outLabel"
            },
        )
        current = outLabel
    }
    val pictureLabel = if (junctionFilters.isEmpty()) "[v0]" else "[xlast]"

    val subtitleFilter = spec.srtPath?.let { srt ->
        "${pictureLabel}subtitles=filename=${escapeFilterPath(srt)}:force_style='${subtitleForceStyle(spec.height)}'[vout]"
    }
    val finalLabel = if (subtitleFilter != null) "[vout]" else pictureLabel

    val filterComplex = buildList {
        addAll(normalize)
        addAll(junctionFilters)
        if (subtitleFilter != null) add(subtitleFilter)
    }.joinToString(";")

    val hasAudio = spec.audioPath != null
    val args = ArrayList<String>(32)
    args.addAll(FFMPEG_PREFIX)
    args.addAll(listOf("-progress", "pipe:1"))
    spec.inputs.forEach { input ->
        if (!input.isVideo) args.addAll(listOf("-loop", "1"))
        args.addAll(listOf("-t", formatSecondsForFfmpeg(input.durationMs)))
        args.addAll(listOf("-i", input.path))
    }
    if (hasAudio) {
        // Loop covers short tracks; the output -t cuts long ones to size.
        args.addAll(listOf("-stream_loop", "-1", "-i", spec.audioPath!!))
    }
    args.addAll(listOf("-filter_complex", filterComplex))
    args.addAll(listOf("-map", finalLabel))
    if (hasAudio) {
        args.addAll(listOf("-map", "${spec.inputs.size}:a:0"))
        if (spec.totalDurationMs >= 1500) {
            val fadeStart = spec.totalDurationMs - 500
            args.addAll(listOf("-af", "afade=t=out:st=${formatSecondsForFfmpeg(fadeStart)}:d=0.500"))
        }
    } else {
        args.add("-an")
    }
    args.addAll(listOf("-t", formatSecondsForFfmpeg(spec.totalDurationMs)))
    args.addAll(videoEncodeFlags(hasAudio))
    args.add(output)
    return args
}
