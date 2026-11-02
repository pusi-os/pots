package com.pusios.pots.screenshots

import com.pusios.pots.ui.tts.PotsTextToSpeech

/** Screenshot fake: never speaks, always "available". */
class FakeTextToSpeechInstance : PotsTextToSpeech {
    override val isAvailable: Boolean = true

    override suspend fun say(text: String, flush: Boolean, interrupt: Boolean) = Unit

    override fun stop() = Unit

    override fun close() = Unit
}
