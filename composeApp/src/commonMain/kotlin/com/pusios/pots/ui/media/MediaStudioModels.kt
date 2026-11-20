package com.pusios.pots.ui.media

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import com.pusios.pots.media.AudioFlow
import com.pusios.pots.media.MediaElementKind
import com.pusios.pots.media.MediaProjectElement
import com.pusios.pots.media.SequenceClip
import com.pusios.pots.media.SequenceTransition
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/** Studio generation mode; drives chips, the model sheet and the dock. */
enum class MediaMode {
    Image,
    Video,
    Audio,
}

/**
 * Draft storage key per mode; audio drafts are separate per flow (spec §13),
 * image/video have no flow dimension. Drafts live in `MediaSettings`, never in
 * transient `remember` state.
 */
internal fun mediaDraftKey(mode: MediaMode, flow: AudioFlow): String = when (mode) {
    MediaMode.Image -> "image"
    MediaMode.Video -> "video"
    MediaMode.Audio -> "audio_" + flow.storageKey
}

/** Ink of a new text/stroke/arrow layer, ARGB (0xFF000000). */
internal const val DEFAULT_LAYER_COLOR_ARGB = -16777216

/** Text size in canvas px; the renderer measures with `Density(1f, 1f)`. */
internal const val DEFAULT_TEXT_SIZE_PX = 48f

/** Stroke/arrow line width in canvas px. */
internal const val DEFAULT_STROKE_WIDTH_PX = 6f

/** How far a duplicate lands from its source, canvas px. */
private const val DUPLICATE_OFFSET = 24f

/**
 * Where a layer sits on the canvas — the part every layer type shares.
 *
 * [opacity] is layer-wide: it is applied when the layer is composited (one
 * `GraphicsLayer.alpha` for the whole layer), never as per-brush alpha, or
 * strokes inside one layer would show through each other.
 */
@Immutable
data class LayerTransform(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val visible: Boolean = true,
)

/**
 * One layer of the media canvas, in draw order.
 *
 * Sealed instead of one flat class: a single `CanvasElement` would carry 20+
 * fields by the time text, strokes and arrows land, and 15 of them would be
 * `null` for any given layer. The flat side of the format is the DTO
 * ([MediaProjectElement]) — that is where compatibility lives.
 *
 * Draw order is the order of the list that holds these layers; there is no
 * `zIndex` here on purpose (see [toProjectElements] for what is written out).
 */
@Immutable
sealed interface CanvasLayer {
    val id: String
    val transform: LayerTransform

    /** The type as the project file names it; the single source of the kind. */
    val kind: MediaElementKind
}

/**
 * A layer backed by a file: an image, a video or an audio track. Sheets that
 * need the source bytes ask for this, so they never see text or strokes.
 */
@Immutable
sealed interface MediaLayer : CanvasLayer {
    /** Generation journal link; null for imports and edit results. */
    val jobId: String?

    /** Remote signed URL; opened without a key. */
    val url: String?

    /** Local cached copy (`media/jobs/…`) once downloaded. */
    val localPath: String?
}

/** A still image placed on the canvas. */
@Immutable
data class ImageLayer(
    override val id: String,
    override val transform: LayerTransform = LayerTransform(),
    override val jobId: String? = null,
    override val url: String? = null,
    override val localPath: String? = null,
    /** Source frame aspect (w/h) when known; usually resolved at compose time. */
    val aspectRatio: Float? = null,
) : MediaLayer {
    override val kind: MediaElementKind get() = MediaElementKind.Image
}

/** A video placed on the canvas; [aspectRatio] is the probed frame ratio. */
@Immutable
data class VideoLayer(
    override val id: String,
    override val transform: LayerTransform = LayerTransform(),
    override val jobId: String? = null,
    override val url: String? = null,
    override val localPath: String? = null,
    /**
     * Source frame aspect (width / height) when known — the element box is
     * sized from it instead of a square crop. Probed once on arrival.
     */
    val aspectRatio: Float? = null,
) : MediaLayer {
    override val kind: MediaElementKind get() = MediaElementKind.Video
}

/** An audio/music result: rendered as a player card instead of a frame. */
@Immutable
data class AudioLayer(
    override val id: String,
    override val transform: LayerTransform = LayerTransform(),
    override val jobId: String? = null,
    override val url: String? = null,
    override val localPath: String? = null,
) : MediaLayer {
    override val kind: MediaElementKind get() = MediaElementKind.Audio
}

/** A text layer; [fontSizePx] and the box are in canvas px, not screen dp. */
@Immutable
data class TextLayer(
    override val id: String,
    override val transform: LayerTransform = LayerTransform(),
    val text: String,
    val fontSizePx: Float = DEFAULT_TEXT_SIZE_PX,
    val colorArgb: Int = DEFAULT_LAYER_COLOR_ARGB,
) : CanvasLayer {
    override val kind: MediaElementKind get() = MediaElementKind.Text
}

/** A free-hand vector stroke; [points] are canvas px, the first one is the anchor. */
@Immutable
data class StrokeLayer(
    override val id: String,
    override val transform: LayerTransform = LayerTransform(),
    val points: List<Offset>,
    val colorArgb: Int = DEFAULT_LAYER_COLOR_ARGB,
    val widthPx: Float = DEFAULT_STROKE_WIDTH_PX,
) : CanvasLayer {
    override val kind: MediaElementKind get() = MediaElementKind.Stroke
}

/** A straight arrow annotation from [from] to [to], canvas px. */
@Immutable
data class ArrowLayer(
    override val id: String,
    override val transform: LayerTransform = LayerTransform(),
    val from: Offset,
    val to: Offset,
    val colorArgb: Int = DEFAULT_LAYER_COLOR_ARGB,
    val widthPx: Float = DEFAULT_STROKE_WIDTH_PX,
) : CanvasLayer {
    override val kind: MediaElementKind get() = MediaElementKind.Arrow
}

enum class CanvasAction {
    Move,
    Resize,
    Rotate,
    Duplicate,
    Delete,
    BringForward,
    SendBack,
    UseAsReference,
    ClearSelection,
}

/**
 * The assembly sequence (spec §7): clips in playback order plus one
 * transition per gap. Persisted by the §12 project autosave.
 */
@Immutable
data class MediaSequenceState(
    val clips: List<SequenceClip> = emptyList(),
    val transitions: List<SequenceTransition> = emptyList(),
)

// -- Layer operations --------------------------------------------------------
// The sealed hierarchy has no common `copy`, so every mutation that keeps the
// layer's type goes through here — one place that knows all the subtypes.

/** Moves the layer by [dx]/[dy] canvas px. */
fun CanvasLayer.moved(dx: Float, dy: Float): CanvasLayer = retransform { it.copy(x = it.x + dx, y = it.y + dy) }

/** Scales the layer, clamped to the same 0.2…4 range the canvas always had. */
fun CanvasLayer.scaled(factor: Float): CanvasLayer = retransform { it.copy(scale = (it.scale * factor).coerceIn(0.2f, 4f)) }

/** Rotates the layer by [degrees] around its own axis. */
fun CanvasLayer.rotated(degrees: Float): CanvasLayer = retransform { it.copy(rotation = it.rotation + degrees) }

/** Layer-wide opacity, clamped to 0…1 — one alpha for the whole layer at render time. */
fun CanvasLayer.withOpacity(value: Float): CanvasLayer = retransform { it.copy(opacity = value.coerceIn(0f, 1f)) }

/** Hidden layers are not composited and not hit-tested; their data and place stay. */
fun CanvasLayer.withVisible(visible: Boolean): CanvasLayer = retransform { it.copy(visible = visible) }

/** A copy of the layer under a new [id], nudged off the source by 24 px. */
fun CanvasLayer.duplicated(id: String): CanvasLayer = retransform { it.copy(x = it.x + DUPLICATE_OFFSET, y = it.y + DUPLICATE_OFFSET) }
    .withId(id)

/** Stores a probed frame aspect on a media layer; a no-op for other kinds. */
fun CanvasLayer.withProbedAspect(aspect: Float): CanvasLayer = when (this) {
    is VideoLayer -> if (aspectRatio == null) copy(aspectRatio = aspect) else this
    is ImageLayer -> if (aspectRatio == null) copy(aspectRatio = aspect) else this
    is AudioLayer, is TextLayer, is StrokeLayer, is ArrowLayer -> this
}

private fun CanvasLayer.withId(id: String): CanvasLayer = when (this) {
    is ImageLayer -> copy(id = id)
    is VideoLayer -> copy(id = id)
    is AudioLayer -> copy(id = id)
    is TextLayer -> copy(id = id)
    is StrokeLayer -> copy(id = id)
    is ArrowLayer -> copy(id = id)
}

private fun CanvasLayer.retransform(change: (LayerTransform) -> LayerTransform): CanvasLayer {
    val next = change(transform)
    return when (this) {
        is ImageLayer -> copy(transform = next)
        is VideoLayer -> copy(transform = next)
        is AudioLayer -> copy(transform = next)
        is TextLayer -> copy(transform = next)
        is StrokeLayer -> copy(transform = next)
        is ArrowLayer -> copy(transform = next)
    }
}

/** Draw order: the layer [id] goes on top; unknown ids leave the list alone. */
internal fun List<CanvasLayer>.toTop(id: String): List<CanvasLayer> = firstOrNull { it.id == id }?.let { layer -> filterNot { it.id == id } + layer } ?: this

/** Draw order: the layer [id] goes to the bottom; unknown ids leave the list alone. */
internal fun List<CanvasLayer>.toBottom(id: String): List<CanvasLayer> = firstOrNull { it.id == id }?.let { layer -> listOf(layer) + filterNot { it.id == id } } ?: this

// -- Project serialization (spec §12) ----------------------------------------

/**
 * Project serialization: canvas layer → flat project DTO.
 *
 * [zIndex] is the position in the draw list — the list is the source of truth,
 * the field only keeps schema-1 readers (and v1 files) in the same order.
 * `isVideo`/`isAudio` are written from [kind] for the same reason, so the two
 * representations can never drift apart.
 */
fun CanvasLayer.toProjectElement(zIndex: Int): MediaProjectElement {
    val media = this as? MediaLayer
    return MediaProjectElement(
        id = id,
        kind = kind.name,
        jobId = media?.jobId,
        url = media?.url,
        localPath = media?.localPath,
        isVideo = kind == MediaElementKind.Video,
        isAudio = kind == MediaElementKind.Audio,
        x = transform.x,
        y = transform.y,
        scale = transform.scale,
        rotation = transform.rotation,
        opacity = transform.opacity,
        visible = transform.visible,
        zIndex = zIndex,
        aspectRatio = when (this) {
            is ImageLayer -> aspectRatio
            is VideoLayer -> aspectRatio
            else -> null
        },
        text = (this as? TextLayer)?.text,
        fontSizePx = (this as? TextLayer)?.fontSizePx,
        colorArgb = when (this) {
            is TextLayer -> colorArgb
            is StrokeLayer -> colorArgb
            is ArrowLayer -> colorArgb
            else -> null
        },
        strokeWidthPx = when (this) {
            is StrokeLayer -> widthPx
            is ArrowLayer -> widthPx
            else -> null
        },
        points = when (this) {
            is StrokeLayer -> encodeStrokePoints(points)
            is ArrowLayer -> encodeStrokePoints(listOf(from, to))
            else -> null
        },
    )
}

/**
 * Project import: flat project DTO → canvas layer.
 *
 * `kind` wins when present (schema 2); a v1 element has none, so the type is
 * derived from `isVideo`/`isAudio` exactly as those files meant it.
 */
fun MediaProjectElement.toCanvasLayer(): CanvasLayer {
    val transform = LayerTransform(
        x = x,
        y = y,
        scale = scale,
        rotation = rotation,
        opacity = opacity,
        visible = visible,
    )
    return when (kindOrDefault()) {
        MediaElementKind.Image -> ImageLayer(id, transform, jobId, url, localPath, aspectRatio)

        MediaElementKind.Video -> VideoLayer(id, transform, jobId, url, localPath, aspectRatio)

        MediaElementKind.Audio -> AudioLayer(id, transform, jobId, url, localPath)

        MediaElementKind.Text -> TextLayer(
            id = id,
            transform = transform,
            text = text.orEmpty(),
            fontSizePx = fontSizePx ?: DEFAULT_TEXT_SIZE_PX,
            colorArgb = colorArgb ?: DEFAULT_LAYER_COLOR_ARGB,
        )

        MediaElementKind.Stroke -> StrokeLayer(
            id = id,
            transform = transform,
            points = decodeStrokePoints(points),
            colorArgb = colorArgb ?: DEFAULT_LAYER_COLOR_ARGB,
            widthPx = strokeWidthPx ?: DEFAULT_STROKE_WIDTH_PX,
        )

        MediaElementKind.Arrow -> {
            val ends = decodeStrokePoints(points)
            ArrowLayer(
                id = id,
                transform = transform,
                from = ends.getOrNull(0) ?: Offset.Zero,
                to = ends.getOrNull(1) ?: ends.getOrNull(0) ?: Offset.Zero,
                colorArgb = colorArgb ?: DEFAULT_LAYER_COLOR_ARGB,
                widthPx = strokeWidthPx ?: DEFAULT_STROKE_WIDTH_PX,
            )
        }
    }
}

/** The stored kind, or the schema-1 derivation when the name is missing/unknown. */
private fun MediaProjectElement.kindOrDefault(): MediaElementKind = MediaElementKind.entries.firstOrNull { it.name == kind }
    ?: when {
        isAudio -> MediaElementKind.Audio
        isVideo -> MediaElementKind.Video
        else -> MediaElementKind.Image
    }

/**
 * A project's elements in draw order: the list order is authoritative for
 * schema 2 (it is written as `zIndex`), `zIndex` restores the order of v1 files.
 */
fun List<MediaProjectElement>.toCanvasLayers(): List<CanvasLayer> = sortedBy { it.zIndex }.map { it.toCanvasLayer() }

/** The draw list as project elements, each carrying its own index as `zIndex`. */
fun List<CanvasLayer>.toProjectElements(): List<MediaProjectElement> = mapIndexed { index, layer -> layer.toProjectElement(index) }

/**
 * Stroke/arrow geometry for the flat DTO: base64 of `[x0: Int32][y0: Int32]`
 * (little-endian, the anchor) followed by `Int16 dx, dy` deltas — 4 bytes per
 * point after the first. Coordinates are canvas px quantised to whole pixels,
 * so a round-trip moves a point by less than one pixel; a stroke of 1000
 * points costs 4 004 bytes → 5 340 base64 chars, against 22 002 chars for the
 * same stroke as a pretty-printed float list.
 */
internal fun encodeStrokePoints(points: List<Offset>): String {
    if (points.isEmpty()) return ""
    val bytes = ByteArray(4 * points.size + 4)
    var cursorX = points[0].x.roundToInt()
    var cursorY = points[0].y.roundToInt()
    bytes.putInt(0, cursorX)
    bytes.putInt(4, cursorY)
    for (index in 1 until points.size) {
        // Deltas are clamped, so the running cursor below is what the decoder
        // will see even for an absurd jump.
        val dx = (points[index].x.roundToInt() - cursorX).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val dy = (points[index].y.roundToInt() - cursorY).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val at = 4 * index + 4
        bytes.putShort(at, dx)
        bytes.putShort(at + 2, dy)
        cursorX += dx
        cursorY += dy
    }
    return Base64.encode(bytes)
}

/** The inverse of [encodeStrokePoints]; empty for absent or unreadable input. */
internal fun decodeStrokePoints(text: String?): List<Offset> {
    if (text.isNullOrEmpty()) return emptyList()
    val bytes = runCatching { Base64.decode(text) }.getOrNull() ?: return emptyList()
    if (bytes.size < 8 || (bytes.size - 8) % 4 != 0) return emptyList()
    var cursorX = bytes.intAt(0)
    var cursorY = bytes.intAt(4)
    val points = ArrayList<Offset>((bytes.size - 8) / 4 + 1)
    points += Offset(cursorX.toFloat(), cursorY.toFloat())
    var at = 8
    while (at < bytes.size) {
        cursorX += bytes.shortAt(at)
        cursorY += bytes.shortAt(at + 2)
        points += Offset(cursorX.toFloat(), cursorY.toFloat())
        at += 4
    }
    return points
}

private fun ByteArray.putInt(index: Int, value: Int) {
    this[index] = (value and 0xFF).toByte()
    this[index + 1] = ((value shr 8) and 0xFF).toByte()
    this[index + 2] = ((value shr 16) and 0xFF).toByte()
    this[index + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun ByteArray.intAt(index: Int): Int = (this[index].toInt() and 0xFF) or
    ((this[index + 1].toInt() and 0xFF) shl 8) or
    ((this[index + 2].toInt() and 0xFF) shl 16) or
    ((this[index + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.putShort(index: Int, value: Int) {
    this[index] = (value and 0xFF).toByte()
    this[index + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.shortAt(index: Int): Int = ((this[index].toInt() and 0xFF) or ((this[index + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

/** Tolerant mode restore from a project file: an unknown name degrades to Image. */
fun mediaModeFromProject(raw: String): MediaMode = MediaMode.entries.firstOrNull { it.name == raw } ?: MediaMode.Image
