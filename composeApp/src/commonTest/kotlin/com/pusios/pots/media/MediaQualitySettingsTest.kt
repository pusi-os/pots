package com.pusios.pots.media

import com.pusios.pots.data.AppSettings
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The image-quality selection persists per model in settings (spec §19.1):
 * switching models, clearing the draft or a failed generation never loses it.
 */
class MediaQualitySettingsTest {

    @Test
    fun qualitySelectionRoundTripsPerModel() {
        val settings = AppSettings(MapSettings())
        assertNull(settings.getMediaQuality("gpt-image-2-6"))
        settings.setMediaQuality("gpt-image-2-6", "medium")
        settings.setMediaQuality("seedream-5", "ultra")
        assertEquals("medium", settings.getMediaQuality("gpt-image-2-6"))
        assertEquals("ultra", settings.getMediaQuality("seedream-5"))
        // A fresh instance over the same storage reads the persisted choice.
        val raw = MapSettings()
        AppSettings(raw).setMediaQuality("flux-3", "draft")
        assertEquals("draft", AppSettings(raw).getMediaQuality("flux-3"))
    }

    @Test
    fun otherModelsStayUntouched() {
        val settings = AppSettings(MapSettings())
        settings.setMediaQuality("gpt-image-2-6", "low")
        assertNull(settings.getMediaQuality("gpt-image-1-5"))
    }
}
