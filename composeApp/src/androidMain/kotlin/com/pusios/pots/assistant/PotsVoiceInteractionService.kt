package com.pusios.pots.assistant

import android.service.voice.VoiceInteractionService

/**
 * Pots as the device assistant — the service the system looks for when it
 * builds the assistant list.
 *
 * Declaring it is the whole qualification for the role: the manifest entry
 * (`BIND_VOICE_INTERACTION` + the `android.voice_interaction` metadata) is what
 * makes Pots appear in Settings → Apps → Default apps → Digital assistant app,
 * and it is also what makes the assist gesture (long-press Home, swipe up from
 * a bottom corner) route to [PotsVoiceInteractionSession] instead of to the
 * `ACTION_ASSIST` activity.
 *
 * The service itself has no work to do: it is a handle, not a runtime. The
 * session is created per invocation by [PotsVoiceInteractionSessionService], and
 * the microphone is the existing hands-free loop, not a second recognizer.
 */
class PotsVoiceInteractionService : VoiceInteractionService()
