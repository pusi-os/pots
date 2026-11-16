package com.pusios.pots.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pusios.pots.LocalAppLanguageCode
import com.pusios.pots.data.AppLanguage
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.ThemeMode
import com.pusios.pots.shared.R
import com.pusios.pots.ui.DarkColorScheme
import com.pusios.pots.ui.LightColorScheme
import com.pusios.pots.ui.potsik.DESKTOP_PET_BUBBLE_ROOM_DP
import com.pusios.pots.ui.potsik.DesktopPetWindow
import com.pusios.pots.ui.potsik.PotsikOverlayPet
import com.pusios.pots.ui.potsik.desktopPetCharacterSize
import org.koin.android.ext.android.inject
import org.koin.core.context.GlobalContext
import java.util.Locale

/**
 * Is our own UI on screen — the process-wide truth the overlay window obeys.
 *
 * The PixelTag rule, and the reason this exists: the floating window and the
 * in-app surface are mutually exclusive. Two surfaces drawing the character
 * at once doubled him and stuttered both, so the window exists ONLY while
 * the app is NOT in the foreground — minimizing puts him on the desktop,
 * opening the app takes him back inside. [com.pusios.pots.PotsApplication]
 * flips this from the process lifecycle; the service reads it directly,
 * same process, no IPC.
 */
object PotsAppForeground {

    @Volatile
    var foreground = false
        private set

    fun set(value: Boolean) {
        foreground = value
    }
}

/**
 * «Поверх окон»: Потсик standing on the desktop in a system overlay window
 * while the app is away.
 *
 * The mechanics are PixelTag's OverlayService, minus everything out of this
 * build's scope (no panel, no screenshot, no guests):
 *
 * - a foreground service, not an activity — since Android 10 the background
 *   cannot launch activities, and the window has to survive the app going
 *   home; the type is `specialUse`, because `dataSync` is capped at six
 *   hours a day on Android 15+ and a pet is not a sync;
 * - the window is exactly the character's size ([desktopPetCharacterSize])
 *   plus the band his bubble is drawn in: the OS delivers every tap inside
 *   the window's rect to us, transparent pixels included, so a bigger window
 *   would eat taps meant for the apps underneath;
 * - [applyVisibility] on every foreground flip: app in front → hidePet(),
 *   app away → showPet();
 * - the window is draggable by a finger past the touch slop, and remembers
 *   where it was put down.
 */
class PotsOverlayService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var pet: OverlayPetShell? = null
    private var windowOwner: OverlayWindowOwner? = null

    private val appSettings: AppSettings by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        // The channel is created once in PotsApplication — nothing slow may
        // sit between the start call and startForeground below, or the system
        // kills the process (ForegroundServiceDidNotStartInTime).
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        applyVisibility()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyVisibility()
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        hidePet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- visibility: the pet only while the app is away ---------------------

    /** Called from [com.pusios.pots.PotsApplication] on every foreground flip. */
    private fun onForegroundChanged() {
        ui.post { applyVisibility() }
    }

    private fun applyVisibility() {
        if (!isRunning) return
        if (PotsAppForeground.foreground) hidePet() else showPet()
    }

    private fun showPet() {
        if (pet != null) return
        val w = wm ?: return
        if (!canDraw(this)) {
            // The permission was revoked while the mode was on: do not fight
            // the system, just go away; the settings row shows the button.
            stopSelf()
            return
        }
        val m = resources.displayMetrics
        val look = appSettings.getPotsikLook()
        val winW = (desktopPetCharacterSize(look).value * m.density).toInt().coerceAtLeast(1)
        val winH = winW + (DESKTOP_PET_BUBBLE_ROOM_DP * m.density).toInt()
        val prefs = prefs()
        val params = WindowManager.LayoutParams(
            winW,
            winH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, (m.widthPixels - winW).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, m.heightPixels / 3)
        }

        val owner = OverlayWindowOwner()
        val shell = OverlayPetShell(this)
        shell.windowParams = params
        shell.onDragEnd = { nx, ny -> prefs.edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply() }
        shell.attachContent(owner) {
            val liveLook by appSettings.potsikLookFlow.collectAsState()
            val themeMode by appSettings.themeModeFlow.collectAsState()
            val appLanguage by appSettings.appLanguageFlow.collectAsState()
            val systemInDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.System -> systemInDark
                ThemeMode.Light -> false
                ThemeMode.Dark, ThemeMode.OledBlack -> true
            }
            val languageCode = when (appLanguage) {
                AppLanguage.System -> Locale.getDefault().language
                AppLanguage.Russian -> "ru"
                AppLanguage.English -> "en"
            }
            CompositionLocalProvider(LocalAppLanguageCode provides languageCode) {
                MaterialTheme(colorScheme = if (dark) DarkColorScheme else LightColorScheme) {
                    PotsikOverlayPet(look = liveLook)
                }
            }
        }
        try {
            w.addView(shell, params)
        } catch (t: Throwable) {
            // No permission after all, or the window manager refused: stay
            // quiet rather than crash the process the chat lives in.
            owner.destroy()
            return
        }
        pet = shell
        windowOwner = owner
        owner.start()
        // The in-app overlay reads this and stands down: one character, ever.
        DesktopPetWindow.setShowing(true)
    }

    private fun hidePet() {
        val v = pet ?: return
        pet = null
        DesktopPetWindow.setShowing(false)
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
            // The window is already gone; nothing to take down.
        }
        windowOwner?.destroy()
        windowOwner = null
    }

    // ---- foreground notification --------------------------------------------

    private fun startAsForeground() {
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
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.potsik_overlay_notification))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    companion object {
        private const val PREFS = "potsik_overlay"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val NOTIF_ID = 9101

        /** The notification channel id; the channel itself is made in PotsApplication. */
        const val CHANNEL = "potsik_overlay"

        /** The channel's importance: as quiet as a notification can be. */
        const val CHANNEL_IMPORTANCE = NotificationManager.IMPORTANCE_MIN

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: PotsOverlayService? = null

        fun canDraw(c: Context): Boolean = runCatching { Settings.canDrawOverlays(c) }.getOrDefault(false)

        /** Foreground flip from [com.pusios.pots.PotsApplication], same process. */
        fun onAppForeground() {
            instance?.onForegroundChanged()
        }

        /**
         * Brings the service in line with the saved flag: mode on and the
         * permission granted → running; anything else → stopped. Called from
         * the settings toggle and from every app start, so the mode turned on
         * before the process died comes back, and a revoked permission takes
         * the window down.
         *
         * Only call this while the app is in the foreground: Android 12+
         * forbids starting a foreground service from the background, and a
         * start is one of the two things this does.
         */
        fun reconcile(context: Context) {
            val app = context.applicationContext
            val settings = GlobalContext.getOrNull()?.getOrNull<AppSettings>() ?: return
            val wanted = settings.getPotsikLook().overlayEnabled && canDraw(app)
            if (wanted && !isRunning) {
                runCatching { app.startForegroundService(Intent(app, PotsOverlayService::class.java)) }
            } else if (!wanted && isRunning) {
                runCatching { app.stopService(Intent(app, PotsOverlayService::class.java)) }
            }
        }
    }
}

/**
 * The window's shell: a plain frame that owns the drag and hosts the
 * Compose content.
 *
 * The drag lives here rather than in Compose on purpose: the window moves in
 * RAW screen coordinates, and a finger on a moving window looks still to the
 * composition inside it — the classic feedback loop. The shell watches the
 * raw events, takes the gesture over past the touch slop (so a micro-shake
 * is never a drag and a tap still reaches the character), and moves the
 * window from the raw delta, the way PixelTag's pet view does.
 */
private class OverlayPetShell(context: Context) : FrameLayout(context) {

    var windowParams: WindowManager.LayoutParams? = null
    var onDragEnd: ((Int, Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var dragging = false

    fun attachContent(owner: OverlayWindowOwner, content: @Composable () -> Unit) {
        // The three tree owners go on THIS view — the window's root — and not
        // on the ComposeView below: the window recomposer looks them up from
        // the root of the view hierarchy, and a ComposeView in a system window
        // has no activity behind it to provide them.
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        val compose = ComposeView(context).apply {
            setContent(content)
        }
        addView(
            compose,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                downWinX = windowParams?.x ?: 0
                downWinY = windowParams?.y ?: 0
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (dx * dx + dy * dy > touchSlop * touchSlop.toFloat()) {
                        // The gesture declared itself a drag: take it over,
                        // the character inside sees a cancel, not a pet.
                        dragging = true
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = windowParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                params.x = downWinX + (event.rawX - downRawX).toInt()
                params.y = downWinY + (event.rawY - downRawY).toInt()
                val w = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                runCatching { w.updateViewLayout(this, params) }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    onDragEnd?.invoke(params.x, params.y)
                }
                dragging = false
                return true
            }
        }
        return false
    }
}

/**
 * The lifecycle a ComposeView asks the window for.
 *
 * A system overlay window has no activity and therefore none of the three
 * owners Compose looks up through the view tree; this is the smallest thing
 * that can stand in for all three, moved to RESUMED while the window is up
 * and DESTROYED the moment it comes down, which is what disposes the
 * composition and stops the frame clock.
 */
private class OverlayWindowOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = registry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = store

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.STARTED
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
