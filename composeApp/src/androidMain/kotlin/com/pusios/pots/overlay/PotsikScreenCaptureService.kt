package com.pusios.pots.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.pusios.pots.shared.R
import com.pusios.pots.ui.potsik.PotsikShot
import com.pusios.pots.ui.potsik.potsikRepackRgba
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The screen-capture session: a foreground service of type `mediaProjection`
 * that owns one projection and one virtual display, and hands out a frame on
 * demand.
 *
 * Why a service of its own instead of the cloud's window:
 *
 * - since Android 14 a projection has to live under a foreground service of
 *   type `mediaProjection`, started **after** the consent dialog answered and
 *   **before** `getMediaProjection()`; the overlay's service is `specialUse`
 *   and has to outlive the mode, so it cannot be the one;
 * - leaving the screen mode then has a literal meaning: this service ends, its
 *   notification goes, and the projection is released.
 *
 * The virtual display is created **once** and kept: the consent token is spent
 * by `createVirtualDisplay`, and a second call throws `SecurityException`. Every
 * tap on «Экран» inside the mode reads the latest frame from the same display
 * instead of asking again — which is the whole point of consent per mode.
 */
class PotsikScreenCaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var width = 0
    private var height = 0

    /** Set while we tear the session down, so our own `stop()` is not read as a revoke. */
    @Volatile
    private var stopping = false

    /** Set the moment the system takes the projection away. */
    @Volatile
    private var stopped = false

    /** True while the display mirrors: the mode can take frames. */
    val ready: Boolean get() = display != null && !stopped && !stopping

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The foreground service comes first: `getMediaProjection()` is allowed
        // only from a service of type `mediaProjection`, and a service that is
        // slow to become one is killed by the system.
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                // The type arrived in API 29; below it a projection needs no
                // foreground service at all.
                startForeground(NOTIFICATION_ID, notification())
            }
        }.isSuccess
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (!foreground || data == null || !startSession(resultCode, data)) {
            PotsikScreenProjection.onSessionFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /** The platform's order, exactly: callback, then projection, then display. */
    private fun startSession(resultCode: Int, data: Intent): Boolean {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return false
        val projection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull() ?: return false
        this.projection = projection
        // Mandatory before createVirtualDisplay: without a callback registered
        // the platform throws IllegalStateException.
        projection.registerCallback(callback, handler)
        val (w, h) = screenSize()
        width = w
        height = h
        val reader = runCatching { ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2) }.getOrNull()
        if (reader == null) {
            release()
            return false
        }
        this.reader = reader
        val display = runCatching {
            projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                w,
                h,
                resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        }.getOrNull()
        if (display == null) {
            release()
            return false
        }
        this.display = display
        PotsikScreenProjection.attach(this)
        PotsikScreenProjection.onReady()
        return true
    }

    /**
     * One frame, as a PNG.
     *
     * Null means "the mirror had nothing to give": the reader is empty, the
     * session is gone, or the plane could not be read. The caller turns that
     * into the pill's own error instead of sending a broken picture.
     */
    suspend fun capture(): PotsikShot? {
        if (!ready) return null
        val image = awaitImage() ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer.also { it.rewind() }
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val packed = potsikRepackRgba(
                    source = bytes,
                    width = width,
                    height = height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                )
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
                val png = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                bitmap.recycle()
                PotsikShot(width = width, height = height, png = png)
            } catch (_: Throwable) {
                null
            } finally {
                image.close()
            }
        }
    }

    /**
     * The first frame of a fresh mirror is often not there yet: the display has
     * to draw something before the reader has an image to give.
     */
    private suspend fun awaitImage(): Image? {
        val reader = reader ?: return null
        repeat(FIRST_FRAME_ATTEMPTS) {
            reader.acquireLatestImage()?.let { return it }
            delay(FIRST_FRAME_DELAY_MS)
        }
        return null
    }

    /**
     * The lock screen (Android 15 QPR1+), the status-bar chip, or the system
     * taking the projection away for its own reasons. Our own teardown is not
     * a revoke, and is filtered out by [stopping].
     */
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (stopping || display == null) return
            stopped = true
            PotsikScreenProjection.onRevoked()
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopping = true
        PotsikScreenProjection.detach(this)
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Releases the display, the reader and the projection — the token is spent here. */
    private fun release() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.let { projection ->
            runCatching { projection.unregisterCallback(callback) }
            runCatching { projection.stop() }
        }
        projection = null
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.potsik_screen_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = open?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.potsik_screen_notification))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "com.pusios.pots.screen.result_code"
        const val EXTRA_RESULT_DATA = "com.pusios.pots.screen.result_data"

        private const val CHANNEL = "potsik_screen"
        private const val NOTIFICATION_ID = 4242
        private const val VIRTUAL_DISPLAY_NAME = "potsik-screen"

        /** 20 × 50 ms: a second for the first frame, then an honest failure. */
        private const val FIRST_FRAME_ATTEMPTS = 20
        private const val FIRST_FRAME_DELAY_MS = 50L
    }
}
