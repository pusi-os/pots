package com.pusios.pots.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * One assist invocation: take the screen's text, hand it to Потсик, close.
 *
 * The shape of this class is the platform's, not ours:
 *
 * * [onShow] is the gesture. The session is bound like a visible foreground
 *   service and allowed to start activities from the background, which is what
 *   makes "the gesture opens Потсик already listening" possible at all;
 * * [onHandleAssist] is the screen's text, and it arrives **after** `onShow`,
 *   asynchronously, once per visible activity. It is a one-shot snapshot of the
 *   top activity taken at invocation time — there is no public API to re-read
 *   the screen later in the same session;
 * * the session draws no window of its own. Потсик is already on the screen as
 *   an overlay (or the app opens instead), and a full-screen session window
 *   would cover the very activity whose text we just read.
 *
 * So the session waits a moment for the snapshot and then delivers whatever it
 * has: with the text when the platform sent it, without it otherwise (a secure
 * window, a missing permission, an OEM that never sends assist data). The
 * gesture must never end in silence.
 */
internal class PotsVoiceInteractionSession(context: Context) : VoiceInteractionSession(context, Handler(Looper.getMainLooper())) {

    private val handler = Handler(Looper.getMainLooper())

    /** Delivered once per invocation: the second callback of a split screen is ignored. */
    private var delivered = false

    /** The snapshot did not come: open without it rather than keep the user waiting. */
    private val deliverWithoutScreen = Runnable { deliver(screenText = null) }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        handler.postDelayed(deliverWithoutScreen, ASSIST_SNAPSHOT_WAIT_MS)
    }

    /**
     * API 29+: the focused activity's snapshot. Split screen and picture-in-picture
     * arrive as several callbacks with an index; the focused one (`isFocused`) is
     * the screen the user was looking at.
     */
    override fun onHandleAssist(state: VoiceInteractionSession.AssistState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!state.isFocused) return
        deliver(assistScreenTextFrom(state.assistStructure))
    }

    /** API 26–28: the same data, before `AssistState` existed. */
    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        deliver(assistScreenTextFrom(structure))
    }

    override fun onHide() {
        handler.removeCallbacks(deliverWithoutScreen)
        super.onHide()
    }

    private fun deliver(screenText: String?) {
        if (delivered) return
        delivered = true
        handler.removeCallbacks(deliverWithoutScreen)
        // A failure here is a window that did not come up — never a crash in the
        // assistant the user has just invoked.
        runCatching { PotsAssistInvocation.launch(context, screenText) }
            .onFailure { Log.w(TAG, "assist invocation could not be shown", it) }
        finish()
    }

    private companion object {
        const val TAG = "PotsAssist"

        /**
         * How long the gesture waits for the snapshot before opening without it.
         *
         * The structure is collected on the app's own thread and arrives in tens
         * of milliseconds; a second of silence means the platform is not going to
         * send it (secure window, assist disabled for the app, an OEM that strips
         * it). A person waiting for the microphone notices a second — but not
         * less than one, because a late snapshot is still the screen they asked
         * about.
         */
        const val ASSIST_SNAPSHOT_WAIT_MS = 1_200L
    }
}
