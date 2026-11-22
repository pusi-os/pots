package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canvas export, checked by pixels and by sizes.
 *
 * Every assertion here is about the file the user gets: how big it is, where
 * the ink lands inside it, what is left out (hidden layers, annotations) and
 * that a different export factor is the same picture, not a different layout.
 * The renderer underneath is the screen's own [renderLayers], so a green run
 * here is a statement about both callers.
 */
class CanvasExportTest {

    private val red = solid(232, 232, Color.Red)
    private val green = solid(232, 232, Color.Green)
    private val textMeasurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr)
    private val textFonts = mapOf(TEXT_FONT_SANS to FontFamily.Default)

    @Test
    fun wholeScopeFitsTheContentWithAPadding() = runTest {
        val first = ImageLayer(id = "first", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val second = ImageLayer(id = "second", transform = LayerTransform(x = 100f, y = 0f), aspectRatio = 1f)
        val frames = mapOf("first" to BitmapPainter(red), "second" to BitmapPainter(green))

        val bitmap = render(listOf(first, second), ExportScope.Whole, frames)
        assertNotNull(bitmap)

        // Content is 0…332 × 0…232, so the canvas is that plus 24 units on each
        // side: 380 × 280. It is not the screen size and not an unbounded canvas.
        assertEquals(380, bitmap.width)
        assertEquals(280, bitmap.height)
        // The first card starts at the padding, the second one 100 units later.
        assertEquals(Color.Red, bitmap.colorAt(24 + 10, 24 + 10))
        assertEquals(Color.Green, bitmap.colorAt(24 + 110, 24 + 10))
        // Nothing beyond the content plus padding.
        assertEquals(0f, bitmap.colorAt(380 - 2, 280 - 2).alpha)
    }

    @Test
    fun hiddenLayerIsLeftOutOfTheBounds() = runTest {
        val visible = ImageLayer(id = "visible", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val hidden = ImageLayer(
            id = "hidden",
            transform = LayerTransform(x = 400f, y = 0f, visible = false),
            aspectRatio = 1f,
        )
        val frames = mapOf("visible" to BitmapPainter(red), "hidden" to BitmapPainter(green))

        val bitmap = render(listOf(visible, hidden), ExportScope.Whole, frames)
        assertNotNull(bitmap)
        // 232 + 2 × 24: the hidden card at x = 400 would have made it 656.
        assertEquals(280, bitmap.width)
        assertEquals(280, bitmap.height)
        assertEquals(0f, bitmap.colorAt(270, 140).alpha)
    }

    @Test
    fun regionExportsExactlyItsRectangle() = runTest {
        val layer = ImageLayer(id = "one", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val frames = mapOf("one" to BitmapPainter(red))

        val bitmap = render(listOf(layer), ExportScope.Region(left = 10f, top = 20f, right = 110f, bottom = 80f), frames)
        assertNotNull(bitmap)
        assertEquals(100, bitmap.width)
        assertEquals(60, bitmap.height)
        // The frame is drawn where it was asked for: the card starts at x = 0,
        // so its right part fills the region from its left edge.
        assertEquals(Color.Red, bitmap.colorAt(50, 30))
    }

    @Test
    fun emptyCanvasHasNothingToExport() = runTest {
        assertNull(render(emptyList(), ExportScope.Whole, emptyMap()))
        // A canvas of hidden layers is just as empty.
        val hidden = ImageLayer(id = "hidden", transform = LayerTransform(visible = false), aspectRatio = 1f)
        assertNull(render(listOf(hidden), ExportScope.Whole, mapOf("hidden" to BitmapPainter(red))))
        // A region with no area is not an export either.
        val layer = ImageLayer(id = "one", transform = LayerTransform(), aspectRatio = 1f)
        assertNull(
            render(listOf(layer), ExportScope.Region(50f, 50f, 50f, 80f), mapOf("one" to BitmapPainter(red))),
        )
    }

    @Test
    fun topLayerCoversTheLowerOne() = runTest {
        val bottom = ImageLayer(id = "bottom", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val top = ImageLayer(id = "top", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val frames = mapOf("bottom" to BitmapPainter(red), "top" to BitmapPainter(green))

        val bitmap = render(listOf(bottom, top), ExportScope.Whole, frames)
        assertNotNull(bitmap)
        // The list is the z-order: the later layer wins the same pixel.
        assertEquals(Color.Green, bitmap.colorAt(140, 140))
        val flipped = render(listOf(top, bottom), ExportScope.Whole, frames)
        assertNotNull(flipped)
        assertEquals(Color.Red, flipped.colorAt(140, 140))
    }

    @Test
    fun aVideoLayerIsExportedAsItsStillFrame() = runTest {
        val video = VideoLayer(id = "clip", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 16f / 9f)
        val frames = mapOf("clip" to BitmapPainter(solid(232, 130, Color.Blue)))

        val bitmap = render(listOf(video), ExportScope.Whole, frames)
        assertNotNull(bitmap)
        // The card is 232 units wide, 16:9 tall, plus the padding on each side.
        assertEquals(280, bitmap.width)
        assertTrue(abs(bitmap.height - (130.5f + 2 * EXPORT_PADDING)) <= 1f, "canvas height ${bitmap.height}")
        // The frame is inside the card, not an empty box.
        assertEquals(Color.Blue, bitmap.colorAt(24 + 10, 24 + 10))
    }

    @Test
    fun oneFactorAndTwoAreTheSamePicture() = runTest {
        val layer = ImageLayer(id = "one", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        // The factor follows the source width: a 232 px frame exports 1:1, a
        // 464 px one exports at 2×. Same renderer, same layout.
        val small = render(listOf(layer), ExportScope.Whole, mapOf("one" to BitmapPainter(red)))
        val large = render(listOf(layer), ExportScope.Whole, mapOf("one" to BitmapPainter(solid(464, 464, Color.Red))))
        assertNotNull(small)
        assertNotNull(large)

        assertEquals(small.width * 2, large.width)
        assertEquals(small.height * 2, large.height)
        val smallInk = small.inkBounds()
        val largeInk = large.inkBounds()
        // Every edge of the ink doubles: the picture is the same, only bigger.
        assertTrue(abs(largeInk.left - smallInk.left * 2) <= 2, "left ${largeInk.left} vs ${smallInk.left * 2}")
        assertTrue(abs(largeInk.right - smallInk.right * 2) <= 2, "right ${largeInk.right} vs ${smallInk.right * 2}")
        assertTrue(abs(largeInk.top - smallInk.top * 2) <= 2, "top ${largeInk.top} vs ${smallInk.top * 2}")
        assertTrue(abs(largeInk.bottom - smallInk.bottom * 2) <= 2, "bottom ${largeInk.bottom} vs ${smallInk.bottom * 2}")
    }

    @Test
    fun rotatedLayerIsNotClipped() = runTest {
        val flat = ImageLayer(id = "one", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val turned = ImageLayer(id = "one", transform = LayerTransform(x = 0f, y = 0f, rotation = 45f), aspectRatio = 1f)
        val frames = mapOf("one" to BitmapPainter(red))

        val flatBitmap = render(listOf(flat), ExportScope.Whole, frames)
        val turnedBitmap = render(listOf(turned), ExportScope.Whole, frames)
        assertNotNull(flatBitmap)
        assertNotNull(turnedBitmap)

        // A 45° square needs 232·√2 ≈ 328 units; with the padding the canvas is
        // 376 wide instead of 280. That growth is the anti-clipping work.
        assertEquals(280, flatBitmap.width)
        val diagonal = 232f * sqrt(2f)
        assertEquals((diagonal + 2 * EXPORT_PADDING).toInt(), turnedBitmap.width)

        val ink = turnedBitmap.inkBounds()
        // The rotated square is drawn whole: if the canvas had been sized from
        // the unrotated box, the ink would be cut to 232 wide. (The card's
        // rounded corners shave a few px off the diagonal.)
        assertTrue(ink.width > 300f, "rotated ink is ${ink.width} wide — it was clipped")
        assertTrue(ink.width <= diagonal + 2f, "rotated ink is ${ink.width}, expected ≤ $diagonal")
    }

    @Test
    fun backgroundIsTransparentOrFilled() = runTest {
        val layer = ImageLayer(id = "one", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val frames = mapOf("one" to BitmapPainter(red))

        val transparent = render(listOf(layer), ExportScope.Whole, frames)
        assertNotNull(transparent)
        assertEquals(0f, transparent.colorAt(0, 0).alpha)

        val filled = render(listOf(layer), ExportScope.Whole, frames, background = EXPORT_DARK_BACKGROUND)
        assertNotNull(filled)
        assertEquals(1f, filled.colorAt(0, 0).alpha)
        assertEquals(EXPORT_DARK_BACKGROUND, filled.colorAt(0, 0))
    }

    @Test
    fun annotationsStayOutOfTheGalleryExport() = runTest {
        val arrow = ArrowLayer(
            id = "arrow",
            from = Offset(40f, 40f),
            to = Offset(200f, 160f),
            colorArgb = Color.Blue.toArgb(),
            widthPx = 8f,
        )
        // The gallery export drops annotations: an arrow-only canvas has
        // nothing to save, and it cannot leak into a picture with content.
        assertNull(render(listOf(arrow), ExportScope.Whole, emptyMap(), includeAnnotation = false))

        val withMarkup = render(listOf(arrow), ExportScope.Whole, emptyMap(), includeAnnotation = true)
        assertNotNull(withMarkup)
        val ink = withMarkup.inkBounds()
        assertTrue(ink.width > 100f, "the arrow should be drawn, ink is ${ink.width} wide")

        val card = ImageLayer(id = "card", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val frames = mapOf("card" to BitmapPainter(red))
        val gallery = render(listOf(card, arrow), ExportScope.Whole, frames, includeAnnotation = false)
        val model = render(listOf(card, arrow), ExportScope.Whole, frames, includeAnnotation = true)
        assertNotNull(gallery)
        assertNotNull(model)
        // The arrow starts 40 units into the card and is not in the gallery file.
        assertEquals(Color.Red, gallery.colorAt(24 + 40, 24 + 40))
        assertEquals(Color.Blue, model.colorAt(24 + 40, 24 + 40))
    }

    @Test
    fun strokesAndTextWidenTheBounds() = runTest {
        val card = ImageLayer(id = "card", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val ink = StrokeLayer(
            id = "ink",
            points = listOf(Offset(400f, 50f), Offset(500f, 50f)),
            colorArgb = Color.Blue.toArgb(),
            widthPx = 10f,
        )
        val text = TextLayer(id = "text", transform = LayerTransform(x = 0f, y = 400f), text = "Потсик", fontSizePx = 48f)
        val frames = mapOf("card" to BitmapPainter(red))

        val cardOnly = render(listOf(card), ExportScope.Whole, frames, measurer = textMeasurer, fonts = textFonts)
        val withInk = render(listOf(card, ink), ExportScope.Whole, frames, measurer = textMeasurer, fonts = textFonts)
        val withText = render(
            listOf(card, ink, text),
            ExportScope.Whole,
            frames,
            measurer = textMeasurer,
            fonts = textFonts,
        )
        assertNotNull(cardOnly)
        assertNotNull(withInk)
        assertNotNull(withText)

        // The card alone is its own box plus padding.
        assertEquals(280, cardOnly.width)
        assertEquals(280, cardOnly.height)
        // The stroke reaches x = 505, the caption sits at y = 400: both grow
        // the canvas instead of falling outside it.
        assertTrue(abs(withInk.width - 553f) < 0.5f, "canvas width ${withInk.width}")
        assertTrue(withText.height > withInk.height, "the caption must make the canvas taller")
        val textInk = withText.inkBounds()
        assertTrue(textInk.bottom > 400, "the caption is below the card, ink bottom ${textInk.bottom}")
    }

    @Test
    fun pixelGridKeepsTheRenderedPixels() = runTest {
        val layer = ImageLayer(id = "one", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val bitmap = render(listOf(layer), ExportScope.Whole, mapOf("one" to BitmapPainter(red)))
        assertNotNull(bitmap)

        val grid = bitmap.toPixelGrid()
        assertEquals(bitmap.width, grid.width)
        assertEquals(bitmap.height, grid.height)
        // The PNG encoder gets straight ARGB: the same colour the renderer drew.
        assertEquals(Color.Red.toArgb(), grid[24 + 10, 24 + 10])
        assertEquals(0, grid[0, 0])
    }

    @Test
    fun fileNameCarriesTheTime() {
        val name = canvasExportFileName(1_797_000_000_000L)
        assertTrue(name.startsWith("pots-canvas-"), name)
        assertTrue(Regex("""pots-canvas-\d{4}-\d{2}-\d{2}-\d{4}""").matches(name), name)
    }

    // -- helpers -------------------------------------------------------------

    private suspend fun render(
        layers: List<CanvasLayer>,
        scope: ExportScope,
        frames: Map<String, Painter>,
        background: Color? = null,
        includeAnnotation: Boolean = false,
        measurer: TextMeasurer? = null,
        fonts: Map<String, FontFamily> = emptyMap(),
    ): ImageBitmap? = renderCanvas(
        layers = layers,
        scope = scope,
        includeAnnotation = includeAnnotation,
        background = background,
        frames = frames,
        fonts = fonts,
        measurer = measurer,
    )

    private data class InkBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Float get() = (right - left + 1).toFloat()
        val height: Float get() = (bottom - top + 1).toFloat()
    }

    /** The box of everything that is not fully transparent. */
    private fun ImageBitmap.inkBounds(): InkBounds {
        val pixels = toPixelMap()
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[x, y].alpha > 0.05f) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return InkBounds(left, top, right, bottom)
    }

    private fun ImageBitmap.colorAt(x: Int, y: Int): Color = toPixelMap()[x, y]

    private fun solid(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density = Density(1f, 1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(color)
        }
        return bitmap
    }
}
