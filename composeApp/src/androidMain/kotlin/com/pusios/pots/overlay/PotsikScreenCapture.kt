package com.pusios.pots.overlay

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.pusios.pots.ui.potsik.PotsikScreenProjector
import com.pusios.pots.ui.potsik.PotsikShot

/**
 * The channel between the three parties of the screen mode, which only meet in
 * this process: the consent activity, the capture service, and the cloud's
 * window.
 *
 * One session at a time and one listener, because there is one screen and one
 * character. The session lives in a foreground service ([PotsikScreenCaptureService]);
 * this object is the handle everyone else holds, so nobody has to bind to it.
 */
internal object PotsikScreenProjection {

    /** Who is waiting for the session: the cloud's window, while it is open. */
    interface Listener {
        fun onScreenReady()

        fun onScreenDenied()

        fun onScreenFailed()

        fun onScreenRevoked()
    }

    @Volatile
    private var session: PotsikScreenCaptureService? = null

    /** The cloud's window, or null when no cloud is open. */
    @Volatile
    var listener: Listener? = null

    /** The live session, or null when the screen mode is off. */
    val service: PotsikScreenCaptureService? get() = session

    fun attach(service: PotsikScreenCaptureService) {
        session = service
    }

    fun detach(service: PotsikScreenCaptureService) {
        if (session === service) session = null
    }

    /**
     * The dialog said yes: the service takes the consent and builds the session.
     *
     * The order is the platform's, not ours: the intent was created by the
     * activity, the service becomes a `mediaProjection` foreground service, and
     * only then `getMediaProjection` is called.
     */
    fun grant(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, PotsikScreenCaptureService::class.java)
            .putExtra(PotsikScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(PotsikScreenCaptureService.EXTRA_RESULT_DATA, data)
        runCatching { context.startForegroundService(intent) }
            .onFailure { listener?.onScreenFailed() }
    }

    fun onDenied() {
        listener?.onScreenDenied()
    }

    fun onReady() {
        val waiting = listener
        if (waiting == null) {
            // The cloud closed while the dialog was up: nobody wants the frame,
            // and a session nobody can see is a leak with a status-bar chip.
            session?.stopSelf()
            return
        }
        waiting.onScreenReady()
    }

    fun onSessionFailed() {
        listener?.onScreenFailed()
    }

    fun onRevoked() {
        listener?.onScreenRevoked()
    }
}

/**
 * [PotsikScreenProjector] over the capture service.
 *
 * The state machine in commonMain decides *when* a frame is taken; this is the
 * thin adapter that says whether a session lives, takes the frame, and ends the
 * session by stopping the service.
 */
internal class AndroidScreenProjector(private val context: Context) : PotsikScreenProjector {

    override val sessionActive: Boolean get() = PotsikScreenProjection.service?.ready == true

    override suspend fun capture(): PotsikShot? = PotsikScreenProjection.service?.capture()

    override fun stop() {
        context.stopService(Intent(context, PotsikScreenCaptureService::class.java))
    }
}

/** The frame as Compose draws it: decoded once, when the shot lands. */
internal fun PotsikShot.preview(): ImageBitmap? = runCatching {
    BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
}.getOrNull()

/**
 * The consent dialog, as an intent for the activity that owns the result.
 *
 * `NEW_TASK` is what lets a service start an activity at all. The activity has
 * a task affinity of its own (see the manifest), so this starts the dialog in a
 * task of its own instead of bringing the app's task to the front: the screen
 * under the dialog stays the one the user was on, and that is the screen the
 * frame has to show. There is no `CLEAR_TOP` any more — a second launch reuses
 * the single instance, and there is nothing above it to clear.
 */
internal fun potsikScreenConsentIntent(context: Context): Intent = Intent(context, PotsikScreenCaptureActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
