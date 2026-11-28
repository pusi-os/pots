package com.pusios.pots.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stored side of the third engine: old stores must still read as Vosk, a
 * name this build does not know must not crash, and the new choice must not
 * bloat the settings blob or the export.
 */
class SttSettingsTest {

    @Test
    fun `a store written before the neural engine existed still reads as Vosk`() {
        val appSettings = AppSettings(MapSettings())
        assertEquals(SttEngine.Vosk, appSettings.getSttEngine())
        assertEquals(AppSettings.DEFAULT_STT_NEURAL_MODEL, appSettings.getSttNeuralModel())
    }

    @Test
    fun `an engine name this build does not know falls back to Vosk`() {
        val settings = MapSettings()
        settings.putString("stt_engine", "SomeFutureEngine")
        assertEquals(SttEngine.Vosk, AppSettings(settings).getSttEngine())
    }

    @Test
    fun `the neural engine and its model survive a reopen`() {
        val settings = MapSettings()
        val appSettings = AppSettings(settings)
        appSettings.setSttEngine(SttEngine.Neural)
        appSettings.setSttNeuralModel("gigaam-v3-ctc-int8")

        val reopened = AppSettings(settings)
        assertEquals(SttEngine.Neural, reopened.getSttEngine())
        assertEquals("gigaam-v3-ctc-int8", reopened.getSttNeuralModel())
    }

    @Test
    fun `every engine is stored as its short enum name`() {
        // The settings store keeps the enum name, not a serialised object: a
        // third engine must not grow the stored value into a blob.
        val settings = MapSettings()
        val appSettings = AppSettings(settings)
        for (engine in SttEngine.entries) {
            appSettings.setSttEngine(engine)
            assertEquals(engine.name, settings.getString("stt_engine", ""))
            assertTrue(engine.name.length <= 16, "engine name too long: ${engine.name}")
        }
    }

    @Test
    fun `the engine choice stays out of the exported settings`() {
        val appSettings = AppSettings(MapSettings())
        appSettings.setSttEngine(SttEngine.Neural)
        appSettings.setSttNeuralModel("gigaam-v3-ctc-int8")

        val keys = appSettings.exportToJson(emptyList()).keys
        assertFalse("stt_engine" in keys)
        assertFalse("stt_neural_model" in keys)
    }
}
