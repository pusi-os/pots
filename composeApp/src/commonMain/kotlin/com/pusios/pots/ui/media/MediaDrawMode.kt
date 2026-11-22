// Free-hand drawing on the studio canvas.
//
// The ink is vector: every finished stroke becomes a StrokeLayer in the layer
// list, so it takes part in the z-order, the opacity, the visibility and the
// export exactly like an image. Nothing is baked into a PNG any more.
//
// Schema 2 stores one stroke per element (`points` is one polyline), so a
// drawing session is a run of StrokeLayers on top of the canvas rather than one
// layer holding a list of strokes; grouping them would need a new file format
// (a third schema version), which is not this card's decision to make.

package com.pusios.pots.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** The pen or the eraser. The eraser removes whole strokes, it never cuts pixels. */
internal enum class DrawTool {
    Brush,
    Eraser,
}

/** Ink samples of the dock: the app accent and four inks (mock `03-draw-mode`). */
internal val DRAW_COLORS: List<Int> = listOf(
    0xFFFDD835.toInt(),
    0xFFBB86FC.toInt(),
    0xFF43D0C8.toInt(),
    0xFFE53935.toInt(),
    0xFFFFFFFF.toInt(),
)

/**
 * Thickness presets in canvas units: 6 / 11 / 17 / 24, i.e. 1× / 2× / 3× / 4.5×
 * the old sheet's 6 px brush. Four circles, not a slider: the dock has to fit
 * five colours, the eraser, undo and «Готово» in one row of a 393 dp phone.
 */
internal val DRAW_WIDTH_PRESETS: List<Float> = listOf(6f, 11f, 17f, 24f)

/** The preset a new session starts on: the mock's «обычная» line. */
internal const val DRAW_DEFAULT_WIDTH_PX = 11f

/**
 * Distance a point has to travel before it is kept, canvas units.
 *
 * The touch stream arrives at 60–120 Hz and carries sub-pixel jitter; a point
 * closer than this to the previous one cannot change the drawn line (the
 * thinnest brush is 6 units wide) but does cost ~5 µs of path and draw time.
 * 2.5 units is under half the thinnest brush, so the line keeps its shape.
 */
internal const val STROKE_POINT_MIN_DISTANCE = 2.5f

/**
 * Hard cap per stroke, the same number tldraw uses. A 1000-point stroke costs
 * ~5.3 KB in the project file; the report measured a 2000-point stroke at
 * 14 ms per frame, i.e. a dropped frame on its own, so the cap is what keeps
 * one long line from eating the budget.
 */
internal const val MAX_STROKE_POINTS = 1000

/** Slack around a stroke when the eraser looks for a hit, canvas units — a finger is wider than a line. */
internal const val STROKE_HIT_SLOP = 8f

/** Length of the little segment that turns a single tap into a visible dot. */
private const val STROKE_DOT_LENGTH = 0.6f

/**
 * The strokes of the current drawing session, oldest first, by layer id.
 *
 * Undo only reaches these: strokes drawn before the session started belong to
 * earlier work, and a session that ends (or a project that loads) starts a new
 * one. The layers themselves live in the view model's list — this is the
 * bookkeeping that lets undo know which of them are the session's.
 */
@Immutable
data class DrawSession(val strokeIds: List<String> = emptyList()) {
    val strokeCount: Int get() = strokeIds.size

    /** Registers a stroke that just landed on the canvas. */
    fun with(id: String): DrawSession = DrawSession(strokeIds + id)

    /** Forgets the newest stroke (it has been removed from the canvas). */
    fun undone(): DrawSession = DrawSession(strokeIds.dropLast(1))

    /** Forgets strokes the eraser removed; ids from other sessions are ignored. */
    fun without(ids: Set<String>): DrawSession = DrawSession(strokeIds.filterNot { it in ids })
}

/**
 * Appends [point] unless it is closer than [minDistance] to the last kept one,
 * or the stroke already holds [maxPoints] points.
 *
 * Returns the very same list when the point is dropped, so a move that adds no
 * ink does not invalidate a draw.
 */
internal fun List<Offset>.withThinnedPoint(
    point: Offset,
    minDistance: Float = STROKE_POINT_MIN_DISTANCE,
    maxPoints: Int = MAX_STROKE_POINTS,
): List<Offset> {
    if (isEmpty()) return listOf(point)
    if (size >= maxPoints) return this
    if ((point - last()).getDistance() < minDistance) return this
    return this + point
}

/**
 * The stroke path in canvas units: quadratic segments through the midpoints of
 * consecutive points, so the line is smooth instead of a chain of corners. The
 * same builder feeds the layer pass and the stroke under the finger — the
 * in-progress line cannot look different from the committed one.
 */
internal fun strokePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    val first = points.first()
    path.moveTo(first.x, first.y)
    if (points.size == 1) {
        // A tap is a dot: a zero-length segment with a round cap draws nothing.
        path.lineTo(first.x + STROKE_DOT_LENGTH, first.y)
        return path
    }
    for (index in 1 until points.size - 1) {
        val control = points[index]
        val next = points[index + 1]
        path.quadraticBezierTo(control.x, control.y, (control.x + next.x) / 2f, (control.y + next.y) / 2f)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

/** Shortest distance from [point] to the polyline through these points. */
internal fun List<Offset>.distanceTo(point: Offset): Float {
    if (isEmpty()) return Float.MAX_VALUE
    if (size == 1) return (point - first()).getDistance()
    var best = Float.MAX_VALUE
    for (index in 1 until size) {
        best = min(best, distanceToSegment(point, this[index - 1], this[index]))
    }
    return best
}

private fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val segment = end - start
    val lengthSquared = segment.x * segment.x + segment.y * segment.y
    if (lengthSquared <= 0f) return (point - start).getDistance()
    val along = (((point.x - start.x) * segment.x + (point.y - start.y) * segment.y) / lengthSquared).coerceIn(0f, 1f)
    val projection = Offset(start.x + segment.x * along, start.y + segment.y * along)
    return (point - projection).getDistance()
}

/**
 * True when [point] (canvas units) touches this stroke: the eraser takes whole
 * strokes, so the test is "close enough to the line", not a bbox hit — a 2-unit
 * line inside a 232-unit box would otherwise be selectable from anywhere in it.
 *
 * The layer's own offset and scale are honoured; rotation is not, which is fine
 * for the eraser (it only needs to be roughly under the finger).
 */
internal fun CanvasLayer.isStrokeHitBy(point: Offset): Boolean {
    val stroke = this as? StrokeLayer ?: return false
    if (!stroke.transform.visible || stroke.points.isEmpty()) return false
    val local = (point - Offset(stroke.transform.x, stroke.transform.y)) / stroke.transform.scale
    return stroke.points.distanceTo(local) <= stroke.widthPx / 2f + STROKE_HIT_SLOP
}

/**
 * What the canvas needs to run the draw mode: the active tool, its ink, and the
 * two ways out of a gesture. `null` means the mode is off.
 *
 * Not `@Immutable`: it carries lambdas, and promising immutability to the
 * runtime would let a stale ink survive a recomposition.
 */
internal data class DrawSurface(
    val tool: DrawTool,
    val colorArgb: Int,
    val widthPx: Float,
    /** A finished stroke, in canvas units. */
    val onStroke: (points: List<Offset>, colorArgb: Int, widthPx: Float) -> Unit,
    /** The eraser crossed this point (canvas units). */
    val onErase: (point: Offset) -> Unit,
)

/**
 * Puts a finished stroke on top of the draw list: the ink always lands above
 * what is already there, exactly like a new image does.
 */
internal fun List<CanvasLayer>.withStroke(layer: StrokeLayer): List<CanvasLayer> = this + layer

/** Removes the strokes with these ids; ids that are not on the canvas are ignored. */
internal fun List<CanvasLayer>.withoutStrokes(ids: Set<String>): List<CanvasLayer> = filterNot { it.id in ids }

/**
 * The draw dock (mock `03-draw-mode`): the ink and the eraser on top, the
 * thickness presets, the session counter and «Готово» below. It floats over the
 * canvas, so the pictures stay visible under the strokes while drawing.
 *
 * There is no «×» next to «Готово»: both would leave the mode, and a second exit
 * with a different meaning (discard the session?) is a decision nothing asks
 * for — one exit, clearly labelled, is the whole point of the wording.
 */
@Composable
internal fun MediaDrawDock(
    modifier: Modifier = Modifier,
    tool: DrawTool,
    colorArgb: Int,
    widthPx: Float,
    strokeCount: Int,
    onTool: (DrawTool) -> Unit,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DRAW_COLORS.forEach { ink ->
                    InkSwatch(
                        colorArgb = ink,
                        selected = tool == DrawTool.Brush && ink == colorArgb,
                    ) {
                        onTool(DrawTool.Brush)
                        onColor(ink)
                    }
                }
                Spacer(Modifier.width(4.dp))
                DockIconButton(EraserIcon, "Ластик", selected = tool == DrawTool.Eraser) {
                    onTool(DrawTool.Eraser)
                }
                DockIconButton(
                    icon = Icons.Filled.Undo,
                    label = "Отменить последний штрих",
                    enabled = strokeCount > 0,
                    onClick = onUndo,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DRAW_WIDTH_PRESETS.forEach { preset ->
                    WidthPreset(preset, selected = preset == widthPx) { onWidth(preset) }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "штрихов: $strokeCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .semantics { contentDescription = "Готово" }
                        .clickable(role = Role.Button, onClick = onDone),
                ) {
                    Text(
                        "Готово",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InkSwatch(colorArgb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .semantics { contentDescription = "Цвет" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(Color(colorArgb), CircleShape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                    shape = CircleShape,
                ),
        )
    }
}

/**
 * One thickness preset: the dot is drawn at the preset's real size, so the dock
 * shows what the brush will be rather than a number.
 */
@Composable
private fun WidthPreset(widthPx: Float, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .semantics { contentDescription = "Толщина ${widthPx.toInt()}" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(widthPx.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    CircleShape,
                ),
        )
    }
}

@Composable
private fun DockIconButton(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The eraser glyph of the mock. Material Icons has no eraser, and the four
 * straight segments of the mock's block are enough to read as one.
 */
private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Eraser",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4.6f, 15.6f)
            lineTo(11.7f, 8.5f)
            lineTo(17.6f, 14.4f)
            lineTo(11.6f, 20.4f)
            lineTo(8.2f, 20.4f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(9.6f, 10.6f)
            lineTo(14.6f, 15.6f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(8.2f, 20.4f)
            lineTo(19.6f, 20.4f)
        }
    }.build()
}
