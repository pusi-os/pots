package com.pusios.pots.ui.tts

import com.pusios.pots.data.TtsEngine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class TextToSpeechSelectionTest {

    private class FakeTts(override val isAvailable: Boolean = true) : PotsTextToSpeech {
        override suspend fun say(text: String, flush: Boolean, interrupt: Boolean) = Unit

        override fun stop() = Unit

        override fun close() = Unit
    }

    @Test
    fun piperEnginePrefersPiperWhenAvailable() {
        val piper = FakeTts()
        val native = FakeTts()

        val resolved = resolveTextToSpeech(TtsEngine.Piper, piper = { piper }, native = { native })

        assertSame(piper, resolved)
    }

    @Test
    fun piperEngineFallsBackToNativeWhenPiperMissing() {
        val native = FakeTts()

        val resolved = resolveTextToSpeech(TtsEngine.Piper, piper = { null }, native = { native })

        assertSame(native, resolved)
    }

    @Test
    fun nativeEngineNeverCreatesPiper() {
        var piperRequested = false
        val native = FakeTts()

        val resolved = resolveTextToSpeech(
            TtsEngine.Native,
            piper = {
                piperRequested = true
                FakeTts()
            },
            native = { native },
        )

        assertSame(native, resolved)
        assertFalse(piperRequested)
    }

    @Test
    fun cloudEnginePlaceholderUsesNative() {
        val native = FakeTts()

        val resolved = resolveTextToSpeech(TtsEngine.Cloud, piper = { FakeTts() }, native = { native })

        assertSame(native, resolved)
    }

    @Test
    fun noEnginesAtAllIsNull() {
        assertNull(resolveTextToSpeech(TtsEngine.Piper, piper = { null }, native = { null }))
    }
}
