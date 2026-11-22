// Draw mode without a UI: the session bookkeeping, the point thinning, the
// eraser hit test and the layer-list operations the view model calls.

package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaDrawSessionTest {

    @Test
    fun `a finished stroke lands on top and undo removes the newest one`() {
        var layers = emptyList<CanvasLayer>()
        var session = DrawSession()

        listOf("first", "second").forEach { id ->
            layers = layers.withStroke(StrokeLayer(id = id, points = listOf(Offset.Zero, Offset(40f, 40f))))
            session = session.with(id)
        }

        // The list is the z-order: the newest stroke is the last element, i.e. on top.
        assertEquals(listOf("first", "second"), layers.map { it.id })
        assertEquals(2, session.strokeCount)

        val newest = session.strokeIds.last()
        layers = layers.withoutStrokes(setOf(newest))
        session = session.undone()

        assertEquals(listOf("first"), layers.map { it.id })
        assertEquals(1, session.strokeCount)
    }

    @Test
    fun `undo never reaches strokes from an earlier session`() {
        val older = StrokeLayer(id = "older", points = listOf(Offset.Zero, Offset(10f, 0f)))
        val layers = listOf<CanvasLayer>(older)
        val session = DrawSession()

        // A fresh session has nothing to pop, so the older stroke stays put.
        assertNull(session.strokeIds.lastOrNull())
        assertEquals(layers, layers.withoutStrokes(emptySet()))
    }

    @Test
    fun `two points closer than the threshold collapse into one`() {
        val start = listOf(Offset(10f, 10f))

        // 1.4 units apart: the same ink, the point is dropped.
        assertEquals(1, start.withThinnedPoint(Offset(11f, 11f)).size)

        // At the threshold the point is kept: the line can actually change there.
        assertEquals(2, start.withThinnedPoint(Offset(10f + STROKE_POINT_MIN_DISTANCE, 10f)).size)
    }

    @Test
    fun `a stroke stops growing at the point cap`() {
        var points = listOf(Offset.Zero)
        repeat(MAX_STROKE_POINTS + 50) { index ->
            points = points.withThinnedPoint(Offset((index + 1) * 3f, 0f))
        }
        assertEquals(MAX_STROKE_POINTS, points.size)
    }

    @Test
    fun `the eraser takes whole strokes under the finger and nothing else`() {
        val ink = StrokeLayer(id = "ink", points = listOf(Offset(0f, 0f), Offset(100f, 0f)), widthPx = 6f)
        val other = StrokeLayer(id = "other", points = listOf(Offset(0f, 200f), Offset(100f, 200f)))

        // On the line, and within a finger's reach of it (6/2 + 8 units).
        assertTrue(ink.isStrokeHitBy(Offset(50f, 0f)))
        assertTrue(ink.isStrokeHitBy(Offset(50f, 10f)))
        // Inside the bbox but far from the line: a stroke is not a card.
        assertFalse(ink.isStrokeHitBy(Offset(50f, 12f)))
        assertFalse(ink.isStrokeHitBy(Offset(50f, 100f)))
        // Another stroke is not touched.
        assertFalse(other.isStrokeHitBy(Offset(50f, 0f)))
        // A hidden stroke is not composited, so it is not erasable either.
        assertFalse(ink.copy(transform = LayerTransform(visible = false)).isStrokeHitBy(Offset(50f, 0f)))
        // A card has no line to hit.
        assertFalse(ImageLayer(id = "card", transform = LayerTransform()).isStrokeHitBy(Offset(50f, 0f)))
    }

    @Test
    fun `the eraser removes every stroke it crossed in one pass`() {
        val layers = listOf<CanvasLayer>(
            StrokeLayer(id = "a", points = listOf(Offset(0f, 0f), Offset(100f, 0f))),
            StrokeLayer(id = "b", points = listOf(Offset(0f, 6f), Offset(100f, 6f))),
            StrokeLayer(id = "c", points = listOf(Offset(0f, 90f), Offset(100f, 90f))),
        )
        val hits = layers.filter { it.isStrokeHitBy(Offset(50f, 3f)) }.map { it.id }.toSet()

        assertEquals(setOf("a", "b"), hits)
        assertEquals(listOf("c"), layers.withoutStrokes(hits).map { it.id })
    }

    @Test
    fun `a drawn stroke survives the project round trip`() {
        // What the canvas commits is a plain StrokeLayer, so the file format has
        // to carry it: the points come back within a pixel (whole-pixel deltas).
        val stroke = StrokeLayer(
            id = "ink",
            transform = LayerTransform(x = 3f, y = -2f, opacity = 0.8f),
            points = listOf(Offset(10f, 20f), Offset(15f, 22f), Offset(12f, 18f)),
            colorArgb = 0xFFFDD835.toInt(),
            widthPx = 11f,
        )

        val reopened = stroke.toProjectElement(zIndex = 0).toCanvasLayer()

        assertEquals(stroke, reopened)
    }
}
