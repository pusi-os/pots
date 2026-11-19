package com.pusios.pots.assistant

import android.content.Context
import android.content.Intent
import android.util.Log
import com.pusios.pots.overlay.PotsOverlayService

/**
 * Where an assist invocation lands.
 *
 * Two surfaces, and the choice is made by what is on screen, not by a flag:
 *
 * * Потсик is standing on somebody else's screen (the overlay mode is on) —
 *   the cloud opens over him, already listening, with the text of the screen it
 *   was invoked over. This is the surface the owner asked for: a question about
 *   what is on the screen, asked without leaving that screen;
 * * there is no pet (the mode is off, the permission is gone, or the app is in
 *   front) — the app opens through its own assist path: a fresh chat that starts
 *   listening (`ChatViewModel.startHandsFreeRequested`). The app is a surface
 *   that always exists, so the gesture never does nothing.
 *
 * The screen's text only travels with the cloud: the app's chat is a different
 * conversation, and a snapshot pushed into it would attach the screen of one
 * moment to a question asked minutes later.
 */
internal object PotsAssistInvocation {

    fun launch(context: Context, screenText: String?) {
        if (PotsOverlayService.openAssistCloud(screenText)) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent == null) {
            Log.w("PotsAssist", "no launch intent for the app: nothing to open")
            return
        }
        // The activity already knows this action: it starts a fresh chat with the
        // microphone open. Keep it, so the app path and the QS tile share one entry.
        intent.action = Intent.ACTION_ASSIST
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w("PotsAssist", "the app could not be opened", it) }
    }
}
