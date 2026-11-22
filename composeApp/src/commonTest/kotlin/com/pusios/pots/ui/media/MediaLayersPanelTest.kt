package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.Offset
import com.pusios.pots.media.MEDIA_PROJECT_SCHEMA_VERSION
import com.pusios.pots.media.MediaProjectFile
import com.pusios.pots.media.decodeMediaProject
import com.pusios.pots.media.encodeMediaProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The layers panel's contract with the draw list.
 *
 * The list is the z-order (index 0 = bottom); the panel only shows it
 * reversed, moves items in it and reads/writes per-layer opacity and
 * visibility. No function here keeps its own order or numbers.
 */
class MediaLayersPanelTest {

    private fun image(id: String, opacity: Float = 1f, visible: Boolean = true) = ImageLayer(
        id = id,
        transform = LayerTransform(opacity = opacity, visible = visible),
    )

    private fun text(id: String, body: String) = TextLayer(id = id, text = body)

    private fun stroke(id: String) = StrokeLayer(id = id, points = listOf(Offset.Zero, Offset(4f, 4f)))

    private fun arrow(id: String) = ArrowLayer(id = id, from = Offset.Zero, to = Offset(10f, 10f))

    private fun sample(): List<CanvasLayer> = listOf(image("bottom"), text("mid", "Ночь в городе"), stroke("top"))

    /** Saves the list through the real project codec and reads it back. */
    private fun roundTrip(layers: List<CanvasLayer>): List<CanvasLayer> {
        val project = MediaProjectFile(
            schemaVersion = MEDIA_PROJECT_SCHEMA_VERSION,
            name = "Слои",
            createdAtMs = 1_700_000_000_000L,
            updatedAtMs = 1_700_000_900_000L,
            elements = layers.toProjectElements(),
        )
        return decodeMediaProject(encodeMediaProject(project)).elements.toCanvasLayers()
    }

    // -- Order ----------------------------------------------------------------

    @Test
    fun reorderMovesTheLayerInTheDrawList() {
        val moved = sample().movedLayer(from = 0, to = 2)

        assertEquals(listOf("mid", "top", "bottom"), moved.map { it.id })
    }

    @Test
    fun reorderOutOfRangeIsANoOp() {
        val layers = sample()

        assertEquals(layers, layers.movedLayer(5, 0))
        assertEquals(layers, layers.movedLayer(0, 0))
        assertEquals(emptyList(), emptyList<CanvasLayer>().movedLayer(0, 1))
    }

    @Test
    fun reorderEndsAsDenseZIndexAndSurvivesSaveAndLoad() {
        val moved = sample().movedLayer(from = 2, to = 0)

        // The saved `zIndex` is the index: 0…n−1 with no gaps, by construction.
        assertEquals(listOf(0, 1, 2), moved.toProjectElements().map { it.zIndex })
        // …and the order after a save/load round trip is the list order.
        assertEquals(moved.map { it.id }, roundTrip(moved).map { it.id })
        // Twice, so a reorder is not a one-way door.
        val again = roundTrip(moved).movedLayer(from = 0, to = 2)
        assertEquals(listOf(0, 1, 2), again.toProjectElements().map { it.zIndex })
        assertEquals(again.map { it.id }, roundTrip(again).map { it.id })
    }

    @Test
    fun aFastDragIsOneMovePerEventAndLeavesNoHoles() {
        val row = 131f // 50 dp on a 2.6 density screen
        val four = listOf(image("a"), image("b"), image("c"), image("d"))

        // One event crossing two rows is a single move from the row's own index:
        // a second move from the same index would shift a stranger.
        val up = layerDragStep(from = 1, count = four.size, carriedPx = -2.5f * row, rowHeightPx = row)
        assertEquals(3, up.toIndex)
        assertEquals(-0.5f * row, up.leftoverPx, 0.001f)

        val moved = four.movedLayer(from = 1, to = up.toIndex)
        assertEquals(listOf("a", "c", "d", "b"), moved.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), moved.toProjectElements().map { it.zIndex })

        // Down in the panel is down in the draw list.
        val down = layerDragStep(from = 1, count = four.size, carriedPx = 1.2f * row, rowHeightPx = row)
        assertEquals(0, down.toIndex)
        assertEquals(0.2f * row, down.leftoverPx, 0.01f)

        // Less than a row of travel moves nothing.
        val short = layerDragStep(from = 1, count = four.size, carriedPx = 0.4f * row, rowHeightPx = row)
        assertEquals(1, short.toIndex)
        assertEquals(0.4f * row, short.leftoverPx, 0.001f)

        // The ends hold, and the travel past them is spent instead of piling up.
        assertEquals(3, layerDragStep(from = 3, count = four.size, carriedPx = -5f * row, rowHeightPx = row).toIndex)
        assertEquals(0f, layerDragStep(from = 3, count = four.size, carriedPx = -5f * row, rowHeightPx = row).leftoverPx, 0.001f)
        assertEquals(0, layerDragStep(from = 0, count = four.size, carriedPx = 5f * row, rowHeightPx = row).toIndex)
    }

    @Test
    fun consecutiveEventsMoveTheRowTheFingerHolds() {
        val row = 131f
        var layers: List<CanvasLayer> = listOf(image("a"), image("b"), image("c"), image("d"))
        var fromIndex = 1
        var carried = 0f

        // Two events, each 1.5 rows of travel, without a recomposition in between.
        repeat(2) {
            carried += -1.5f * row
            val step = layerDragStep(fromIndex, layers.size, carried, row)
            carried = step.leftoverPx
            if (step.toIndex != fromIndex) {
                layers = layers.movedLayer(fromIndex, step.toIndex)
                fromIndex = step.toIndex
            }
        }

        // «b» walked to the top; a stale index would have moved «c» twice.
        assertEquals(listOf("a", "c", "d", "b"), layers.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), layers.toProjectElements().map { it.zIndex })
    }

    @Test
    fun panelOrderIsTheReverseOfTheRenderOrder() {
        val layers = sample()

        val panel = layers.panelOrder()

        assertEquals(layers.reversed(), panel)
        // First row is the top layer — the last one the canvas draws.
        assertEquals(layers.last().id, panel.first().id)
    }

    // -- Visibility -----------------------------------------------------------

    @Test
    fun hiddenLayerIsNeitherDrawnNorHitTested() {
        val layers = listOf(image("a"), image("b", visible = false), image("c"))

        val drawn = layers.visibleLayers()

        assertEquals(listOf("a", "c"), drawn.map { it.id })
        assertFalse(drawn.any { it.id == "b" })
        // The hidden layer keeps its data and its place in the list.
        assertEquals(3, layers.size)
        assertEquals("b", layers[1].id)
    }

    @Test
    fun visibilityRoundTripsThroughTheProjectFile() {
        val layers = listOf(image("a"), image("b").withVisible(false))

        val restored = roundTrip(layers)

        assertTrue(restored[0].transform.visible)
        assertFalse(restored[1].transform.visible)
        assertEquals(listOf("a"), restored.visibleLayers().map { it.id })
    }

    // -- Opacity --------------------------------------------------------------

    @Test
    fun opacityReachesTheLayerTheRendererComposites() {
        val layers = listOf(image("a"), image("b").withOpacity(0.4f))

        // The value the canvas hands to `graphicsLayer(alpha = …)`.
        assertEquals(0.4f, layers[1].transform.opacity, 0.0001f)
        // Filtering for visibility must not lose it.
        assertEquals(0.4f, layers.visibleLayers()[1].transform.opacity, 0.0001f)
        // And it is written to the project, so it survives a restart.
        assertEquals(0.4f, layers.toProjectElements()[1].opacity, 0.0001f)
        assertEquals(0.4f, roundTrip(layers)[1].transform.opacity, 0.0001f)
    }

    @Test
    fun opacityIsClampedToTheValidRange() {
        assertEquals(0f, image("a").withOpacity(-3f).transform.opacity, 0f)
        assertEquals(1f, image("a").withOpacity(7f).transform.opacity, 0f)
    }

    @Test
    fun railTapStepsAndDragSlides() {
        assertEquals(0.4f, opacityAfterTap(0.3f), 0.0001f)
        assertEquals(1f, opacityAfterTap(0.95f), 0.0001f)
        assertEquals(OPACITY_TAP_STEP, opacityAfterTap(1f), 0.0001f)
        assertEquals(0.5f, opacityAfterDrag(0.4f, dxPx = 4.4f, railWidthPx = 44f), 0.0001f)
        assertEquals(0f, opacityAfterDrag(0.1f, dxPx = -50f, railWidthPx = 44f), 0.0001f)
        assertEquals("40%", opacityLabel(0.4f))
        assertEquals("100%", opacityLabel(1f))
    }

    // -- Names ----------------------------------------------------------------

    @Test
    fun titlesAreMeaningfulPerKind() {
        val layers = listOf(
            image("i1"),
            image("i2"),
            text("t1", "Ночь в городе светится окнами"),
            text("t2", "   "),
            stroke("s1"),
            arrow("a1"),
        )

        val titles = layers.layerTitles()

        assertEquals("Картинка 1", titles["i1"])
        assertEquals("Картинка 2", titles["i2"])
        assertEquals("Текст: Ночь в городе", titles["t1"])
        assertEquals("Текст 2", titles["t2"])
        assertEquals("Рисунок 1", titles["s1"])
        assertEquals("Стрелка 1", titles["a1"])
    }

    @Test
    fun aLongTextTitleIsClippedToOneRow() {
        val long = text("t", "Невероятно длинное название города на закате")

        val title = listOf<CanvasLayer>(long).layerTitles()["t"]!!

        assertTrue(title.startsWith("Текст: "), title)
        assertTrue(title.endsWith("…"), title)
        assertTrue(title.length <= "Текст: ".length + 22, title)
    }

    @Test
    fun subtitleNamesTheKindAndFlagsAHiddenLayer() {
        assertEquals("Изображение", layerSubtitle(image("a")))
        assertEquals("Изображение · скрыт", layerSubtitle(image("a", visible = false)))
        assertEquals("Текст · 48 px", layerSubtitle(text("t", "привет")))
        assertEquals("Рисунок · 2 точек", layerSubtitle(stroke("s")))
    }
}
