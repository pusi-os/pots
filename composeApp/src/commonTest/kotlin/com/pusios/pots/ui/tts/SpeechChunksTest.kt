package com.pusios.pots.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechChunksTest {
    @Test
    fun `blank text produces no utterances`() {
        assertEquals(emptyList(), chunkTextForSpeech("   \n\t"))
    }

    @Test
    fun `short text stays in one chunk`() {
        assertEquals(listOf("Hello Pots"), chunkTextForSpeech("Hello Pots"))
    }

    @Test
    fun `long text is split below the platform limit`() {
        val text = buildString {
            repeat(1000) { append("word").append(it % 10).append(' ') }
        }
        val chunks = chunkTextForSpeech(text, maxChars = 120)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(text.trim(), chunks.joinToString(" "))
    }

    @Test
    fun `a single oversized token is hard split`() {
        val chunks = chunkTextForSpeech("x".repeat(25), maxChars = 10)
        assertEquals(listOf("x".repeat(10), "x".repeat(10), "x".repeat(5)), chunks)
    }
}
