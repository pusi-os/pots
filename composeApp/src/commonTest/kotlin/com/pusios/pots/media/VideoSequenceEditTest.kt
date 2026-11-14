package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Editing operations of the sequence strip (add, remove, reorder). */
class VideoSequenceEditTest {

    private fun clip(id: String, durationMs: Long = 3000) = SequenceClip(
        elementId = id,
        relPath = "media/tests/$id",
        isVideo = false,
        durationMs = durationMs,
    )

    @Test
    fun addingFirstClipStartsWithoutJunctions() {
        val (clips, transitions) = sequenceAdd(emptyList(), emptyList(), clip("a"))
        assertEquals(listOf("a"), clips.map { it.elementId })
        assertTrue(transitions.isEmpty())
    }

    @Test
    fun addingClipAppendsDefaultCrossfadeJunction() {
        var (clips, transitions) = sequenceAdd(emptyList(), emptyList(), clip("a"))
        val added = sequenceAdd(clips, transitions, clip("b"))
        clips = added.first
        transitions = added.second
        assertEquals(listOf("a", "b"), clips.map { it.elementId })
        assertEquals(listOf(SequenceTransition.Crossfade), transitions)
    }

    @Test
    fun removingMiddleClipDropsItsRightJunction() {
        val clips = listOf(clip("a"), clip("b"), clip("c"))
        val transitions = listOf(SequenceTransition.Slide, SequenceTransition.Wipe)
        val (newClips, newTransitions) = sequenceRemoveAt(clips, transitions, 1)
        assertEquals(listOf("a", "c"), newClips.map { it.elementId })
        assertEquals(listOf(SequenceTransition.Slide), newTransitions)
    }

    @Test
    fun removingLastClipDropsItsLeftJunction() {
        val clips = listOf(clip("a"), clip("b"))
        val transitions = listOf(SequenceTransition.Wipe)
        val (newClips, newTransitions) = sequenceRemoveAt(clips, transitions, 1)
        assertEquals(listOf("a"), newClips.map { it.elementId })
        assertTrue(newTransitions.isEmpty())
    }

    @Test
    fun removingOnlyClipEmptiesSequence() {
        val (newClips, newTransitions) = sequenceRemoveAt(listOf(clip("a")), emptyList(), 0)
        assertTrue(newClips.isEmpty())
        assertTrue(newTransitions.isEmpty())
    }

    @Test
    fun removingOutOfRangeRefuses() {
        assertFailsWith<IndexOutOfBoundsException> {
            sequenceRemoveAt(listOf(clip("a")), emptyList(), 3)
        }
    }

    @Test
    fun movingClipSwapsNeighboursAndKeepsJunctionSlots() {
        val clips = listOf(clip("a"), clip("b"), clip("c"))
        val transitions = listOf(SequenceTransition.Slide, SequenceTransition.Wipe)
        val movedDown = sequenceMove(clips, transitions, 0, 1)
        assertEquals(listOf("b", "a", "c"), movedDown.first.map { it.elementId })
        // Junction slots stay positional, so the strip keeps its look.
        assertEquals(transitions, movedDown.second)
        val movedUp = sequenceMove(movedDown.first, movedDown.second, 2, 1)
        assertEquals(listOf("b", "c", "a"), movedUp.first.map { it.elementId })
    }

    @Test
    fun movingOutOfBoundsIsRefused() {
        val clips = listOf(clip("a"), clip("b"))
        assertFailsWith<IndexOutOfBoundsException> { sequenceMove(clips, listOf(SequenceTransition.Cut), 0, 5) }
        assertFailsWith<IndexOutOfBoundsException> { sequenceMove(clips, listOf(SequenceTransition.Cut), 0, -1) }
    }
}
