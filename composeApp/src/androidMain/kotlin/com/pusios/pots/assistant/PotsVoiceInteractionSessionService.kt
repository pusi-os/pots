package com.pusios.pots.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Creates one [PotsVoiceInteractionSession] per assist invocation.
 *
 * The system binds this service when the gesture fires; it is bound with
 * `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` and treated as a visible foreground
 * service, which is what lets the session bring Потсик up and open the
 * microphone from a background process.
 */
class PotsVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession = PotsVoiceInteractionSession(this)
}
