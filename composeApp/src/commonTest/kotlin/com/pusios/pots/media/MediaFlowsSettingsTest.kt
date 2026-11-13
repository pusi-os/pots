package com.pusios.pots.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.ui.media.MediaMode
import com.pusios.pots.ui.media.mediaDraftKey
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Flow selection, per-flow model ids and per-mode/flow prompt drafts.
 * All of it lives in the settings backend (MediaSettings), not in transient
 * composable `remember` state — switching flows or an error never loses text
 * (spec §13, §17).
 */
class MediaFlowsSettingsTest {

    @Test
    fun audioFlowRoundTripsAndDefaultsToTts() {
        val raw = MapSettings()
        val first = AppSettings(raw)
        assertEquals(AudioFlow.Tts, first.getMediaAudioFlow())
        first.setMediaAudioFlow(AudioFlow.Music)
        // A fresh instance over the same storage reads the persisted choice.
        assertEquals(AudioFlow.Music, AppSettings(raw).getMediaAudioFlow())
    }

    @Test
    fun unknownStoredFlowFallsBackToTts() {
        val raw = MapSettings()
        raw.putString("media_audio_flow", "karaoke")
        assertEquals(AudioFlow.Tts, AppSettings(raw).getMediaAudioFlow())
    }

    @Test
    fun modelSelectionIsPerFlowAndPerInstance() {
        val settings = AppSettings(MapSettings())
        settings.setInstanceMediaAudioTtsModelId("inst1", "gpt-4o-mini-tts")
        settings.setInstanceMediaAudioMusicModelId("inst1", "suno")
        assertEquals("gpt-4o-mini-tts", settings.getInstanceMediaAudioTtsModelId("inst1"))
        assertEquals("suno", settings.getInstanceMediaAudioMusicModelId("inst1"))
        // The two flows keep independent selections.
        assertNotEquals(settings.getInstanceMediaAudioTtsModelId("inst1"), settings.getInstanceMediaAudioMusicModelId("inst1"))
        // The other instance stays untouched.
        assertEquals("", settings.getInstanceMediaAudioTtsModelId("inst2"))
        assertEquals("", settings.getInstanceMediaAudioMusicModelId("inst2"))
    }

    @Test
    fun draftsAreSeparatePerModeAndFlow() {
        val settings = AppSettings(MapSettings())
        val ttsKey = mediaDraftKey(MediaMode.Audio, AudioFlow.Tts)
        val musicKey = mediaDraftKey(MediaMode.Audio, AudioFlow.Music)
        val imageKey = mediaDraftKey(MediaMode.Image, AudioFlow.Tts)
        assertNotEquals(ttsKey, musicKey)

        settings.setMediaDraft(ttsKey, "Текст для озвучки")
        settings.setMediaDraft(musicKey, "Ло-фай трек про дождь")
        // Switching flow/mode never erases the other draft.
        assertEquals("Текст для озвучки", settings.getMediaDraft(ttsKey))
        assertEquals("Ло-фай трек про дождь", settings.getMediaDraft(musicKey))
        assertEquals("", settings.getMediaDraft(imageKey))
    }

    @Test
    fun draftKeyIsUniqueForEveryModeFlowPair() {
        val keys = setOf(
            mediaDraftKey(MediaMode.Image, AudioFlow.Tts),
            mediaDraftKey(MediaMode.Video, AudioFlow.Tts),
            mediaDraftKey(MediaMode.Audio, AudioFlow.Tts),
            mediaDraftKey(MediaMode.Audio, AudioFlow.Music),
        )
        assertEquals(4, keys.size)
        // Image/video have no flow dimension.
        assertEquals(mediaDraftKey(MediaMode.Image, AudioFlow.Tts), mediaDraftKey(MediaMode.Image, AudioFlow.Music))
        assertEquals(mediaDraftKey(MediaMode.Video, AudioFlow.Tts), mediaDraftKey(MediaMode.Video, AudioFlow.Music))
    }
}
