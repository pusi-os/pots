package com.pusios.pots.media

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The journal keeps the «локально» mark of local TTS entries (spec §14, review round 1). */
class LocalTtsJournalTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun localTtsEntryKeepsLocalMarkThroughJournalRoundTrip() {
        val entry = MediaHistoryEntry(
            localId = "lt-1",
            createdAtMs = 3L,
            isVideo = false,
            isAudio = true,
            model = LOCAL_TTS_PIPER_ID,
            provider = LOCAL_TTS_PROVIDER_MARK,
            prompt = "привет",
            opts = mapOf("speed" to "1.0"),
            status = MediaHistoryStatus.Success,
            localPath = "media/local/lt-1.wav",
            priceRub = 0.0,
        )
        val text = json.encodeToString(MediaHistoryEntry.serializer(), entry)
        val back = json.decodeFromString(MediaHistoryEntry.serializer(), text)
        assertEquals(LOCAL_TTS_PROVIDER_MARK, back.provider)
        assertEquals("локально", back.provider)
        assertTrue(back.isAudio)
        assertEquals(0.0, back.priceRub)
        assertEquals(LOCAL_TTS_PIPER_ID, back.model)
    }

    @Test
    fun journalJsonMixesCloudAndLocalEntries() {
        val raw = """
        [{"localId":"c1","createdAtMs":1,"isVideo":false,"isAudio":true,"model":"gpt-4o-mini-tts",
          "provider":"PlusVibeAPI","prompt":"т","opts":{},"references":[],"droppedReferences":0,
          "status":"Success","jobId":"j1"},
         {"localId":"l1","createdAtMs":2,"isVideo":false,"isAudio":true,"model":"local:piper-irina",
          "provider":"локально","prompt":"т","opts":{},"references":[],"droppedReferences":0,
          "status":"Success","localPath":"media/local/l1.wav","priceRub":0.0}]
        """.trimIndent()
        val entries = json.decodeFromString<List<MediaHistoryEntry>>(raw)
        assertEquals(2, entries.size)
        assertEquals(MEDIA_PROVIDER_NAME, entries[0].provider)
        assertEquals(LOCAL_TTS_PROVIDER_MARK, entries[1].provider)
        assertEquals(0.0, entries[1].priceRub)
    }
}
