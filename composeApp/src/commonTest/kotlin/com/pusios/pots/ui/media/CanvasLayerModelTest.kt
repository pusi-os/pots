package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.Offset
import com.pusios.pots.media.MEDIA_PROJECT_MARK
import com.pusios.pots.media.MEDIA_PROJECT_SCHEMA_VERSION
import com.pusios.pots.media.MediaElementKind
import com.pusios.pots.media.MediaProjectElement
import com.pusios.pots.media.MediaProjectFile
import com.pusios.pots.media.decodeMediaProject
import com.pusios.pots.media.encodeMediaProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The media-editor layer model and its project-file mapping.
 *
 * The file format is flat and versioned; the UI model is a sealed hierarchy.
 * These tests pin the two contracts that hold the pair together: a schema-1
 * file migrates through the defaults (kind from the flags, opacity/visible
 * from their defaults, order from `zIndex`), and a schema-2 file round-trips
 * strokes and text without losing a point.
 */
class CanvasLayerModelTest {

    private fun project(elements: List<MediaProjectElement>) = MediaProjectFile(
        schemaVersion = MEDIA_PROJECT_SCHEMA_VERSION,
        name = "Слои",
        createdAtMs = 1_700_000_000_000L,
        updatedAtMs = 1_700_000_900_000L,
        elements = elements,
    )

    /** Saves the layers through the real codec and reads them back. */
    private fun roundTrip(layers: List<CanvasLayer>): List<CanvasLayer> = decodeMediaProject(encodeMediaProject(project(layers.toProjectElements()))).elements.toCanvasLayers()

    // -- Schema 1 migration ---------------------------------------------------

    @Test
    fun schemaOneFileMigratesThroughDefaults() {
        val v1 = """
            {"schemaVersion":1,"app":"$MEDIA_PROJECT_MARK","name":"Старый","createdAtMs":1,"updatedAtMs":2,
             "elements":[
               {"id":"top","localPath":"media/a.png","zIndex":7,"x":10.0,"y":20.0},
               {"id":"bottom","localPath":"media/b.mp4","isVideo":true,"zIndex":1,"rotation":15.0},
               {"id":"track","localPath":"media/c.mp3","isAudio":true,"zIndex":4}
             ]}
        """.trimIndent()

        val layers = decodeMediaProject(v1).elements.toCanvasLayers()

        // v1 carries the draw order only in `zIndex`.
        assertEquals(listOf("bottom", "track", "top"), layers.map { it.id })
        assertIs<VideoLayer>(layers[0])
        assertIs<AudioLayer>(layers[1])
        val image = assertIs<ImageLayer>(layers[2])
        assertEquals(10f, image.transform.x, 0f)
        assertEquals(20f, image.transform.y, 0f)
        assertEquals(1f, image.transform.opacity, 0f)
        assertTrue(image.transform.visible)
        assertEquals(MediaElementKind.Image, image.kind)
        // A v1 video keeps its probed rotation and gains the default opacity.
        val video = assertIs<VideoLayer>(layers[0])
        assertEquals(15f, video.transform.rotation, 0f)
        assertEquals(1f, video.transform.opacity, 0f)
    }

    /** `kind` is the schema-2 source of truth; the flags are written for old readers. */
    @Test
    fun kindNeverDisagreesWithTheLegacyFlags() {
        val layers = listOf(
            ImageLayer("i", LayerTransform()),
            VideoLayer("v", LayerTransform(), aspectRatio = 1.5f),
            AudioLayer("a", LayerTransform()),
            TextLayer("t", LayerTransform(), text = "привет"),
            StrokeLayer("s", LayerTransform(), points = listOf(Offset.Zero, Offset(4f, 4f))),
            ArrowLayer("r", LayerTransform(), from = Offset.Zero, to = Offset(8f, 8f)),
        )
        val saved = layers.toProjectElements()

        assertEquals(
            listOf("Image", "Video", "Audio", "Text", "Stroke", "Arrow"),
            saved.map { it.kind },
        )
        saved.forEach { element ->
            assertEquals(element.kind == MediaElementKind.Video.name, element.isVideo, "isVideo of ${element.id}")
            assertEquals(element.kind == MediaElementKind.Audio.name, element.isAudio, "isAudio of ${element.id}")
        }
        assertEquals(saved, roundTrip(layers).toProjectElements())
    }

    // -- Schema 2 round trip --------------------------------------------------

    @Test
    fun strokesAndTextSurviveARoundTrip() {
        val layers = listOf(
            ImageLayer(
                id = "img",
                transform = LayerTransform(x = -12f, y = 34f, scale = 1.5f, rotation = 90f, opacity = 0.4f, visible = false),
                url = "https://example.com/cat.png",
                localPath = "media/jobs/job-9.png",
                aspectRatio = 1.5f,
            ),
            TextLayer(
                id = "txt",
                transform = LayerTransform(x = 5f, y = 6f),
                text = "Подпись «по-русски»",
                fontSizePx = 64f,
                colorArgb = -65536,
            ),
            StrokeLayer(
                id = "stroke",
                transform = LayerTransform(rotation = 12.5f),
                points = listOf(Offset(10f, 20f), Offset(15f, 22f), Offset(12f, 18f)),
                colorArgb = -16777216,
                widthPx = 4f,
            ),
            ArrowLayer(
                id = "arrow",
                transform = LayerTransform(),
                from = Offset(100f, 100f),
                to = Offset(240f, 180f),
                colorArgb = -256,
                widthPx = 3f,
            ),
        )

        assertEquals(layers, roundTrip(layers))
    }

    /** `zIndex` is the list index on save, so a schema-1 reader keeps the stacking. */
    @Test
    fun zIndexIsTheListIndexOnSave() {
        val layers = listOf(
            AudioLayer("a", LayerTransform()),
            ImageLayer("b", LayerTransform()),
            TextLayer("c", LayerTransform(), text = "x"),
        )
        val saved = layers.toProjectElements()
        assertEquals(listOf(0, 1, 2), saved.map { it.zIndex })

        val reopened = decodeMediaProject(encodeMediaProject(project(saved))).elements.toCanvasLayers()
        assertEquals(listOf("a", "b", "c"), reopened.map { it.id })
    }

    // -- Draw order -----------------------------------------------------------

    @Test
    fun orderOperationsMoveOneLayerOnly() {
        val layers = listOf(
            ImageLayer("bottom", LayerTransform()),
            ImageLayer("middle", LayerTransform()),
            ImageLayer("top", LayerTransform()),
        )
        assertEquals(listOf("bottom", "top", "middle"), layers.toTop("middle").map { it.id })
        assertEquals(listOf("middle", "bottom", "top"), layers.toBottom("middle").map { it.id })
        // Unknown ids and no-op moves leave the list as it was.
        assertEquals(layers, layers.toTop("ghost"))
        assertEquals(layers, layers.toBottom("ghost"))
        assertEquals(listOf("bottom", "middle", "top"), layers.toTop("top").map { it.id })
    }

    @Test
    fun layerOperationsKeepTheType() {
        val stroke = StrokeLayer("s", LayerTransform(x = 1f, y = 2f), points = listOf(Offset.Zero))
        val moved = assertIs<StrokeLayer>(stroke.moved(10f, -4f))
        assertEquals(11f, moved.transform.x, 0f)
        assertEquals(-2f, moved.transform.y, 0f)
        assertEquals(stroke.points, moved.points, "the payload must survive a transform change")

        val scaled = assertIs<StrokeLayer>(stroke.scaled(10f))
        assertEquals(4f, scaled.transform.scale, 0f)

        val rotated = assertIs<StrokeLayer>(stroke.rotated(15f))
        assertEquals(15f, rotated.transform.rotation, 0f)

        val copy = assertIs<StrokeLayer>(stroke.duplicated("s-copy"))
        assertEquals("s-copy", copy.id)
        assertEquals(25f, copy.transform.x, 0f)

        val video = VideoLayer("v", LayerTransform(), aspectRatio = null)
        assertEquals(1.5f, assertIs<VideoLayer>(video.withProbedAspect(1.5f)).aspectRatio)
        // A probed aspect is written once, never overwritten.
        assertEquals(1.5f, assertIs<VideoLayer>(video.withProbedAspect(1.5f).withProbedAspect(2f)).aspectRatio)
        // Layers without a frame aspect ignore the probe.
        assertIs<AudioLayer>(AudioLayer("a", LayerTransform()).withProbedAspect(1.5f))
    }

    // -- Stroke point encoding ------------------------------------------------

    /** 4 bytes per point after the anchor: 1000 points → 4 004 bytes → 5 340 base64 chars. */
    @Test
    fun strokePointsCostFourBytesPerPoint() {
        val points = List(1000) { index -> Offset((index % 512).toFloat(), (index % 300).toFloat()) }
        assertEquals(5_340, encodeStrokePoints(points).length)
        assertEquals(1_608, encodeStrokePoints(points.take(300)).length)
        assertEquals(points, decodeStrokePoints(encodeStrokePoints(points)))
    }

    @Test
    fun strokePointsUseTheDocumentedByteLayout() {
        // [x0=10][y0=20] then Int16 deltas (5,2) and (-3,-4), little-endian.
        val points = listOf(Offset(10f, 20f), Offset(15f, 22f), Offset(12f, 18f))
        val encoded = encodeStrokePoints(points)
        assertEquals("CgAAABQAAAAFAAIA/f/8/w==", encoded)
        assertEquals(points, decodeStrokePoints(encoded))
    }

    @Test
    fun unreadableStrokePointsDecodeEmpty() {
        assertTrue(decodeStrokePoints(null).isEmpty())
        assertTrue(decodeStrokePoints("").isEmpty())
        assertTrue(decodeStrokePoints("не base64").isEmpty())
        // A bare 8-byte header is a one-point stroke, not junk.
        assertEquals(listOf(Offset(10f, 20f)), decodeStrokePoints("CgAAABQAAAA="))
    }

    @Test
    fun emptyStrokeKeepsItsLayer() {
        val layers = listOf(StrokeLayer("s", LayerTransform(), points = emptyList()))
        val reopened = roundTrip(layers)
        assertEquals(layers, reopened)
        assertTrue(assertIs<StrokeLayer>(reopened.single()).points.isEmpty())
    }
}
