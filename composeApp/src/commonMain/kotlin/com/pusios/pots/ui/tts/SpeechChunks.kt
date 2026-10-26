package com.pusios.pots.ui.tts

import nl.marc_apps.tts.TextToSpeechInstance

/** Android TTS rejects very long utterances; stay comfortably below the platform limit. */
const val MAX_SPEECH_CHUNK_CHARS = 3000

fun chunkTextForSpeech(
    text: String,
    maxChars: Int = MAX_SPEECH_CHUNK_CHARS,
): List<String> {
    if (text.isBlank()) return emptyList()
    require(maxChars > 0) { "maxChars must be positive" }

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (word in text.split(' ', '\n', '\t', '\r').filter { it.isNotBlank() }) {
        if (word.length > maxChars) {
            if (current.isNotEmpty()) {
                chunks += current.toString()
                current.clear()
            }
            chunks += word.chunked(maxChars)
            continue
        }
        if (current.isEmpty()) {
            current.append(word)
        } else if (current.length + 1 + word.length <= maxChars) {
            current.append(' ').append(word)
        } else {
            chunks += current.toString()
            current.clear()
            current.append(word)
        }
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
}

/** Speaks in platform-sized pieces while keeping barge-in and stop working between chunks. */
suspend fun TextToSpeechInstance.sayChunked(
    text: String,
    flush: Boolean = true,
    interrupt: Boolean = true,
) {
    val chunks = chunkTextForSpeech(text)
    chunks.forEachIndexed { index, chunk ->
        say(chunk, flush && index == 0, interrupt && index == 0)
    }
}
