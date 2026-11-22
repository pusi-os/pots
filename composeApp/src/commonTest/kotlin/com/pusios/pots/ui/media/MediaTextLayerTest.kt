package com.pusios.pots.ui.media

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.pusios.pots.media.MEDIA_PROJECT_MARK
import com.pusios.pots.media.MEDIA_PROJECT_SCHEMA_VERSION
import com.pusios.pots.media.MediaElementKind
import com.pusios.pots.media.MediaProjectFile
import com.pusios.pots.media.decodeMediaProject
import com.pusios.pots.media.encodeMediaProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The text layer: its box, its wrapping, its formatting and its project file.
 *
 * The measurement runs for real — a `TextMeasurer` over the platform font
 * resolver, exactly the one the screen and the renderer use. The serif slot is
 * filled with a different family here (the bundled TTF is a resource and needs
 * a composition to load), which is enough to prove the family map reaches the
 * layout and changes the result.
 */
class MediaTextLayerTest {

    private val fonts = mapOf(TEXT_FONT_SANS to FontFamily.Default, TEXT_FONT_SERIF to FontFamily.Monospace)
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr)

    private fun measure(layer: TextLayer): TextLayoutResult = textLayout(layer, measurer, fonts)

    private fun layer(text: String = "", widthPx: Float = DEFAULT_TEXT_WIDTH): TextLayer =
        newTextLayer("txt", 40f, 60f).copy(text = text, widthPx = widthPx)

    // -- Creation and hit target ---------------------------------------------

    @Test
    fun aNewTextLayerIsAppendedOnTopAndOwnsItsBox() {
        val existing = listOf(ImageLayer("img", LayerTransform()))
        val created = newTextLayer("txt", 40f, 60f)
        val next = existing + created

        // Draw order is the list: the new layer is last, i.e. on top.
        assertEquals(listOf("img", "txt"), next.map { it.id })
        assertEquals(MediaElementKind.Text, created.kind)
        assertTrue(next.last() is TextLayer)

        // The hit target and the frame are the measured box at the layer's
        // position — a tap in the middle of it lands on the layer.
        val box = canvasLayerBox(created, null, measurer, fonts)
        assertEquals(DEFAULT_TEXT_WIDTH, box.width)
        assertTrue(box.height > 0f, "an empty text layer still has a line height")
        val tapX = created.transform.x + box.width / 2f
        val tapY = created.transform.y + box.height / 2f
        assertTrue(tapX > created.transform.x && tapX < created.transform.x + box.width)
        assertTrue(tapY > created.transform.y && tapY < created.transform.y + box.height)
    }

    // -- Measurement ---------------------------------------------------------

    @Test
    fun anEmptyTextHasARealBox() {
        val layout = measure(layer())
        // The box is the stored width; the height is one line, never NaN and
        // never negative (the box is what a selection frame is drawn on).
        assertEquals(DEFAULT_TEXT_WIDTH.toInt(), layout.size.width)
        assertTrue(layout.size.height > 0, "empty text keeps one line of height")
        assertEquals(1, layout.lineCount)
        assertTrue(layout.multiParagraph.width.isFinite() && layout.multiParagraph.width >= 0f)
        assertTrue(layout.multiParagraph.height.isFinite() && layout.multiParagraph.height > 0f)
    }

    @Test
    fun aLongTextWrapsAndGrowsInHeight() {
        val short = measure(layer("Привет"))
        val long = measure(
            layer("Очень длинная подпись, которая не влезает в одну строку и должна переноситься по ширине рамки"),
        )

        assertEquals(1, short.lineCount)
        assertTrue(long.lineCount > 1, "a long caption wraps, got ${long.lineCount} lines")
        assertTrue(long.size.height > short.size.height, "wrapping makes the box taller")
        // The wrapping width is the stored box width, not the viewport.
        assertEquals(DEFAULT_TEXT_WIDTH.toInt(), long.size.width)
    }

    @Test
    fun aWiderBoxWrapsIntoFewerLines() {
        val text = "Очень длинная подпись, которая не влезает в одну строку и должна переноситься по ширине рамки"
        val narrow = measure(layer(text, widthPx = 160f))
        val wide = measure(layer(text, widthPx = 640f))

        assertTrue(narrow.lineCount > wide.lineCount, "narrow ${narrow.lineCount} vs wide ${wide.lineCount}")
        assertEquals(160, narrow.size.width)
        assertEquals(640, wide.size.width)
        assertTrue(narrow.size.height > wide.size.height)
    }

    @Test
    fun theFontAndTheSizeChangeTheMeasuredBox() {
        val base = layer("Подпись")

        val small = measure(base.copy(fontSizePx = 24f))
        val big = measure(base.copy(fontSizePx = 96f))
        assertTrue(big.size.height > small.size.height, "a bigger size makes a taller box")

        // The family map is what the renderer receives; a different family must
        // reach the layout, otherwise «выбор шрифта» would be decoration.
        val sans = measure(base.copy(fontId = TEXT_FONT_SANS))
        val serif = measure(base.copy(fontId = TEXT_FONT_SERIF))
        assertTrue(
            sans.getLineRight(0) != serif.getLineRight(0),
            "sans ${sans.getLineRight(0)} vs serif ${serif.getLineRight(0)}: the font did not reach the measurement",
        )
    }

    @Test
    fun theAlignmentMovesTheLineInsideTheBox() {
        val base = layer("Привет")
        val left = measure(base.copy(align = TextAlignKind.Left))
        val centre = measure(base.copy(align = TextAlignKind.Center))
        val right = measure(base.copy(align = TextAlignKind.Right))

        assertEquals(0f, left.getLineLeft(0), 0.5f)
        assertTrue(centre.getLineLeft(0) > left.getLineLeft(0))
        assertEquals(DEFAULT_TEXT_WIDTH, right.getLineRight(0), 0.5f)
    }

    // -- Project file --------------------------------------------------------

    @Test
    fun aTextLayerWithAllFieldsSurvivesTheProjectRoundTrip() {
        val layer = TextLayer(
            id = "txt",
            transform = LayerTransform(x = 3f, y = 4f, scale = 1.5f, rotation = 15f, opacity = 0.6f, visible = false),
            text = "Подпись «по-русски»",
            fontSizePx = 64f,
            colorArgb = -65536,
            fontId = TEXT_FONT_SERIF,
            widthPx = 320f,
            align = TextAlignKind.Right,
        )
        val project = MediaProjectFile(
            schemaVersion = MEDIA_PROJECT_SCHEMA_VERSION,
            name = "Текст",
            createdAtMs = 1_700_000_000_000L,
            updatedAtMs = 1_700_000_900_000L,
            elements = listOf<CanvasLayer>(layer).toProjectElements(),
        )

        // The flat DTO carries the formatting, not only the string.
        val dto = project.elements.single()
        assertEquals("serif", dto.fontId)
        assertEquals(320f, dto.textWidthPx)
        assertEquals("Right", dto.textAlign)

        val reopened = decodeMediaProject(encodeMediaProject(project)).elements.toCanvasLayers()
        assertEquals(listOf<CanvasLayer>(layer), reopened)
    }

    @Test
    fun aSchemaTwoTextLayerReadsWithTheFormattingDefaults() {
        // Schema 2 knew `text`, `fontSizePx` and `colorArgb` only.
        val v2 = """
            {"schemaVersion":2,"app":"$MEDIA_PROJECT_MARK","name":"Старый","createdAtMs":1,"updatedAtMs":2,
             "elements":[{"id":"t","kind":"Text","text":"Привет","fontSizePx":40.0,"colorArgb":-1,"zIndex":0}]}
        """.trimIndent()

        val layer = assertIs<TextLayer>(decodeMediaProject(v2).elements.toCanvasLayers().single())
        assertEquals("Привет", layer.text)
        assertEquals(40f, layer.fontSizePx)
        assertEquals(TEXT_FONT_SANS, layer.fontId)
        assertEquals(DEFAULT_TEXT_WIDTH, layer.widthPx)
        assertEquals(TextAlignKind.Left, layer.align)
    }

    @Test
    fun anUnknownFontKeyKeepsItsNameAndRendersWithTheDefaultFamily() {
        val v3 = """
            {"schemaVersion":3,"app":"$MEDIA_PROJECT_MARK","name":"Будущий","createdAtMs":1,"updatedAtMs":2,
             "elements":[{"id":"t","kind":"Text","text":"Привет","fontId":"comic","textAlign":"Justify","textWidthPx":200.0,"zIndex":0}]}
        """.trimIndent()

        val layer = assertIs<TextLayer>(decodeMediaProject(v3).elements.toCanvasLayers().single())
        assertEquals("comic", layer.fontId, "the key is kept as written")
        assertEquals(TextAlignKind.Left, layer.align, "an unknown alignment falls back to left")
        assertEquals(200f, layer.widthPx)
        // An unknown family key renders with the default family, never a crash.
        assertTrue(measure(layer).size.height > 0)
    }
}
