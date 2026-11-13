package com.pusios.pots.media

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Result file extension detection and journal compatibility for audio entries. */
class MediaAudioHistoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun audioExtensionDetectedFromUrl() {
        assertEquals("mp3", mediaResultExtension("https://x/result.mp3?sig=abc", MediaKind.Audio))
        assertEquals("wav", mediaResultExtension("https://x/result.wav", MediaKind.Audio))
        assertEquals("opus", mediaResultExtension("https://x/result.opus", MediaKind.Audio))
        assertEquals("flac", mediaResultExtension("https://x/result.flac", MediaKind.Audio))
        assertEquals("mp3", mediaResultExtension("https://x/result.MP3", MediaKind.Audio))
    }

    @Test
    fun signedUrlWithoutExtensionFallsBackToKind() {
        assertEquals("mp3", mediaResultExtension("https://plusvibeapi.ru/api/media/file/abc/0?sig=xyz", MediaKind.Audio))
        assertEquals("mp3", mediaResultExtension("https://plusvibeapi.ru/api/media/file/abc/0?sig=xyz", MediaKind.Music))
        assertEquals("mp4", mediaResultExtension("https://plusvibeapi.ru/api/media/file/abc/0?sig=xyz", MediaKind.Video))
        assertEquals("png", mediaResultExtension("https://plusvibeapi.ru/api/media/file/abc/0?sig=xyz", MediaKind.Image))
    }

    @Test
    fun existingExtensionsUnchangedForImageVideo() {
        assertEquals("mp4", mediaResultExtension("https://x/clip.mp4", MediaKind.Video))
        assertEquals("png", mediaResultExtension("https://x/pic.png", MediaKind.Image))
        assertEquals("jpg", mediaResultExtension("https://x/pic.jpeg", MediaKind.Image))
    }

    @Test
    fun historyEntryDefaultsToNotAudio() {
        val entry = MediaHistoryEntry(localId = "a", createdAtMs = 1L, isVideo = false, model = "m", prompt = "p")
        assertFalse(entry.isAudio)
    }

    @Test
    fun oldJournalJsonWithoutIsAudioStillDecodes() {
        val old = """
        [{"localId":"x","createdAtMs":1,"isVideo":false,"model":"gpt-image-2","provider":"PlusVibeAPI",
          "prompt":"кот","opts":{},"references":[],"droppedReferences":0,"status":"Success","jobId":"j1"}]
        """.trimIndent()
        val entries = json.decodeFromString<List<MediaHistoryEntry>>(old)
        assertEquals(1, entries.size)
        assertFalse(entries[0].isAudio)
    }

    @Test
    fun audioEntryRoundTripsThroughJournalJson() {
        val entry = MediaHistoryEntry(
            localId = "y",
            createdAtMs = 2L,
            isVideo = false,
            isAudio = true,
            model = "gpt-4o-mini-tts",
            prompt = "скажи привет",
        )
        val text = json.encodeToString(MediaHistoryEntry.serializer(), entry)
        val back = json.decodeFromString(MediaHistoryEntry.serializer(), text)
        assertTrue(back.isAudio)
        assertFalse(back.isVideo)
        assertEquals("gpt-4o-mini-tts", back.model)
    }
}
