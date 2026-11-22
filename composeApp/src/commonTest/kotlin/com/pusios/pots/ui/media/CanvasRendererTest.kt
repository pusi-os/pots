package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The canvas renderer, checked by pixels.
 *
 * The old screen was a stack of composables: `offset(x, y)` + a 232.dp box +
 * `graphicsLayer(scale, rotation)`. At density 1 and zoom 1 that is a 232 × 232
 * box whose top-left corner is (x, y) — the assertions below pin exactly that,
 * so a layer lands where the composable path put it and the list order is the
 * z-order.
 */
class CanvasRendererTest {

    private val red = solid(50, 50, Color.Red)
    private val green = solid(50, 50, Color.Green)

    @Test
    fun emptyLayerListDrawsNothing() {
        val pixels = render(emptyList())
        assertEquals(0f, pixels.colorAt(10, 10).alpha)
        assertEquals(0f, pixels.colorAt(240, 240).alpha)
    }

    @Test
    fun twoLayersKeepTheirCoordinatesAndOrder() {
        val first = ImageLayer(id = "first", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val second = ImageLayer(id = "second", transform = LayerTransform(x = 100f, y = 0f), aspectRatio = 1f)
        val frames = mapOf("first" to BitmapPainter(red), "second" to BitmapPainter(green))

        val pixels = render(listOf(first, second), frames)

        // Both boxes are 232 wide: "first" covers 0…232, "second" 100…332.
        assertEquals(Color.Red, pixels.colorAt(50, 10))
        // The overlap shows the later layer: the list is the z-order.
        assertEquals(Color.Green, pixels.colorAt(110, 10))
        // Nothing outside the boxes.
        assertEquals(0f, pixels.colorAt(400, 400).alpha)

        val flipped = render(listOf(second, first), frames)
        assertEquals(Color.Red, flipped.colorAt(110, 10))
    }

    @Test
    fun hiddenLayerIsNotDrawn() {
        val hidden = ImageLayer(
            id = "hidden",
            transform = LayerTransform(x = 0f, y = 0f, visible = false),
            aspectRatio = 1f,
        )
        val pixels = render(listOf(hidden), mapOf("hidden" to BitmapPainter(red)))
        assertEquals(0f, pixels.colorAt(116, 116).alpha)
    }

    @Test
    fun rotatedLayerTurnsAroundItsOwnAxis() {
        val flat = ImageLayer(id = "flat", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val turned = ImageLayer(id = "flat", transform = LayerTransform(x = 0f, y = 0f, rotation = 45f), aspectRatio = 1f)
        val frames = mapOf("flat" to BitmapPainter(red))

        // A point near the top-left corner of the unrotated box (0…232).
        assertEquals(Color.Red, render(listOf(flat), frames).colorAt(20, 2))

        val pixels = render(listOf(turned), frames)
        // 45° around the centre (116, 116) swings that corner out of the box…
        assertEquals(0f, pixels.colorAt(20, 2).alpha)
        // …and keeps the centre where it was.
        assertEquals(Color.Red, pixels.colorAt(116, 116))
    }

    @Test
    fun scaleChangesTheSizeNotTheOrder() {
        val first = ImageLayer(id = "first", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val second = ImageLayer(id = "second", transform = LayerTransform(x = 100f, y = 0f), aspectRatio = 1f)
        val frames = mapOf("first" to BitmapPainter(red), "second" to BitmapPainter(green))

        // At scale 1 the box spans 0…232, so 300 is empty canvas; at scale 2 it
        // is twice as wide and reaches that pixel.
        assertEquals(0f, render(listOf(first), frames).colorAt(300, 116).alpha)
        assertEquals(Color.Red, render(listOf(first), frames, scale = 2f).colorAt(300, 116))

        // Order is untouched by the scale: the later layer still wins the overlap.
        assertEquals(Color.Green, render(listOf(first, second), frames, scale = 2f).colorAt(150, 116))
        assertEquals(Color.Red, render(listOf(second, first), frames, scale = 2f).colorAt(150, 116))
    }

    @Test
    fun zoomGrowsTheBoxAroundItsLayoutCentre() {
        val layer = ImageLayer(id = "one", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val pixels = render(listOf(layer), mapOf("one" to BitmapPainter(red)), zoom = 2f)
        // Box 464 wide centred on x = 116: it spans -116…348, like the old
        // graphicsLayer did. A top-left anchored box would reach 464.
        assertEquals(Color.Red, pixels.colorAt(340, 116))
        assertEquals(0f, pixels.colorAt(360, 116).alpha)
    }

    @Test
    fun cardBackgroundShowsInTheLetterboxBars() {
        // A 3:1 frame in a card clamped to 16:9 leaves bars above and below.
        val wide = ImageLayer(id = "wide", transform = LayerTransform(x = 0f, y = 0f))
        val pixels = render(
            layers = listOf(wide),
            frames = mapOf("wide" to BitmapPainter(solid(300, 100, Color.Red))),
            cardBackground = Color.Blue,
        )
        assertEquals(Color.Blue, pixels.colorAt(116, 5))
        assertEquals(Color.Red, pixels.colorAt(116, 65))
    }

    @Test
    fun layerOpacityAppliesToTheWholeCard() {
        val layer = ImageLayer(id = "half", transform = LayerTransform(x = 0f, y = 0f, opacity = 0.5f), aspectRatio = 1f)
        val alpha = render(listOf(layer), mapOf("half" to BitmapPainter(red))).colorAt(116, 116).alpha
        assertTrue(abs(alpha - 0.5f) < 0.02f, "expected ~50 % alpha, got $alpha")
    }

    @Test
    fun opacityCompositesTheCardAsOneGroup() {
        // A 3:1 frame in a 16:9 card: the frame sits between the letterbox bars,
        // so one render gives a bar pixel and a frame pixel of the same layer.
        val layer = ImageLayer(id = "half", transform = LayerTransform(x = 0f, y = 0f, opacity = 0.5f))
        val pixels = render(
            layers = listOf(layer),
            frames = mapOf("half" to BitmapPainter(solid(300, 100, Color.Red))),
            cardBackground = Color.Blue,
        )
        // The old graphicsLayer(alpha) faded the whole element: the bar and the
        // frame are equally 50 % opaque. Fading the fill and the frame
        // separately stacks them in the frame area (0.5 over 0.5 = 0.75).
        val bar = pixels.colorAt(116, 5).alpha
        val frame = pixels.colorAt(116, 65).alpha
        assertTrue(abs(bar - 0.5f) < 0.02f, "letterbox bar: expected ~50 % alpha, got $bar")
        assertTrue(abs(frame - 0.5f) < 0.02f, "frame: expected ~50 % alpha, got $frame")
        assertEquals(bar, frame, 0.02f)
    }

    @Test
    fun audioAndTextLayersAreSkipped() {
        val audio = AudioLayer(id = "audio", transform = LayerTransform(x = 0f, y = 0f))
        val text = TextLayer(id = "text", transform = LayerTransform(x = 0f, y = 0f), text = "hi")
        // Frames are offered for these ids on purpose: only image/video draw them.
        val frames = mapOf("audio" to BitmapPainter(red), "text" to BitmapPainter(green))
        val pixels = render(listOf(audio, text), frames)
        assertEquals(0f, pixels.colorAt(116, 116).alpha)
    }

    @Test
    fun boxBiggerThanTheCanvasDoesNotThrow() {
        // A 232-unit box on a 100 px canvas: the painter is drawn into a scope
        // larger than the canvas, which is the case that used to be rejected.
        val layer = ImageLayer(id = "big", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val pixels = render(listOf(layer), mapOf("big" to BitmapPainter(red)), width = 100, height = 100)
        assertEquals(Color.Red, pixels.colorAt(50, 50))
    }

    @Test
    fun layerWithoutFrameDrawsOnlyTheCard() {
        val loading = ImageLayer(id = "loading", transform = LayerTransform(x = 0f, y = 0f))
        val pixels = render(listOf(loading), cardBackground = Color.Blue)
        assertEquals(Color.Blue, pixels.colorAt(116, 116))
    }

    // -- helpers -------------------------------------------------------------

    private fun render(
        layers: List<CanvasLayer>,
        frames: Map<String, Painter> = emptyMap(),
        scale: Float = 1f,
        zoom: Float = 1f,
        cardBackground: Color? = null,
        width: Int = 480,
        height: Int = 480,
    ): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        CanvasDrawScope().draw(
            density = Density(1f, 1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            renderLayers(
                layers = layers,
                scale = scale,
                fonts = emptyMap(),
                measurer = null,
                frames = frames,
                cardBackground = cardBackground,
                zoom = zoom,
            )
        }
        return bitmap
    }

    private fun ImageBitmap.colorAt(x: Int, y: Int): Color = toPixelMap()[x, y]

    private fun solid(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        CanvasDrawScope().draw(
            density = Density(1f, 1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(color)
        }
        return bitmap
    }
}
