package com.pusios.pots.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pusios.pots.ui.potsik.PotsikCarry
import com.pusios.pots.ui.potsik.PotsikCloudPhase
import com.pusios.pots.ui.potsik.PotsikOverlayPet
import com.pusios.pots.ui.potsik.PotsikPinch
import com.pusios.pots.ui.potsik.PotsikScreen
import com.pusios.pots.ui.potsik.clampWindowOffset
import com.pusios.pots.ui.potsik.desktopPetCharacterSize
import com.pusios.pots.ui.potsik.pinchedPotsikSize
import com.pusios.pots.ui.potsik.potsikSizeDp
import com.pusios.pots.ui.potsik.potsikSizeScale
import org.koin.android.ext.android.inject
import org.koin.core.context.GlobalContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * - the window is draggable by a finger past the touch slop and pinched
 *   bigger or smaller with two, and remembers both where it was put down and
 *   how big it was left.
 *
 * The service obeys the saved flag and the permission, never its own state:
 * [wanted] is asked before every window and on every start, so turning the
 * mode off — or losing the permission in the system — always takes the
 * window down, whichever order the switch and the service were touched in.
 */
class PotsOverlayService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var pet: OverlayPetShell? = null
    private var windowOwner: OverlayWindowOwner? = null

    /** The «облачко»: its own window, alive only while it is open. */
    private var cloud: PotsikCloudOverlay? = null

    /** What the cloud shows, as Compose state: the character's window redraws from it. */
    private val cloudPhase = mutableStateOf<PotsikCloudPhase?>(null)

    /**
     * Screen off closes the cloud and the microphone with it; both actions
     * also tell the common layer whether the screen is lit, which is the one
     * thing that lets the character's clock stop asking for frames.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    closeCloud()
                    PotsikScreen.setInteractive(false)
                }

                Intent.ACTION_SCREEN_ON -> PotsikScreen.setInteractive(true)
            }
        }
    }
    private var screenReceiverRegistered = false

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
        registerScreenReceiver()
        applyVisibility()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A stop asked for before this start was delivered has to win: the
        // toggle is a switch, and on → off within a second used to leave a
        // window nobody would ever take down, because the reconcile that
        // asked for the stop ran before the service existed.
        if (!wanted()) {
            stopSelf()
            return START_NOT_STICKY
        }
        applyVisibility()
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * A rotation, a fold or a display-size change happens under the window.
     *
     * The window keeps absolute x/y, so a character standing low in portrait
     * would sit past the bottom edge in landscape and be gone — the one place
     * `FLAG_LAYOUT_NO_LIMITS` really loses him. By the time this is called the
     * new metrics are already on `resources`, so the same clamp the gestures
     * use puts him back; the new place is written down, or the next start
     * would read the old coordinates out of the prefs and jump him off again.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The cloud's window is placed from the character's, so a new screen
        // size invalidates both: it goes down here rather than in an
        // observer of its own, and the capsule is opened again on the new
        // bounds by the next double tap.
        closeCloud()
        val view = pet ?: return
        val params = view.windowParams ?: return
        clampToScreen(params, resources.displayMetrics)
        runCatching { wm?.updateViewLayout(view, params) }
        prefs().edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
    }

    override fun onDestroy() {
        closeCloud()
        unregisterScreenReceiver()
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

    /**
     * Is the mode on and the permission there?
     *
     * Read from the stored look every time rather than cached: the settings
     * screen writes the flag, the system owns the permission, and a cached
     * answer is exactly how a mode that was turned off keeps a character on
     * somebody's screen.
     */
    private fun wanted(): Boolean = appSettings.getPotsikLook().overlayEnabled && canDraw(this)

    private fun applyVisibility() {
        if (!isRunning) return
        if (!wanted()) {
            // Turned off, or the permission was revoked while he stood there:
            // take the window down and go away quietly. The settings row
            // already shows what is missing.
            hidePet()
            stopSelf()
            return
        }
        if (PotsAppForeground.foreground) hidePet() else showPet()
    }

    private fun showPet() {
        if (pet != null) return
        val w = wm ?: return
        if (!wanted()) {
            stopSelf()
            return
        }
        val m = resources.displayMetrics
        val look = appSettings.getPotsikLook()
        val winW = (desktopPetCharacterSize(look).value * m.density).toInt().coerceAtLeast(1)
        val winH = winW + (DESKTOP_PET_BUBBLE_ROOM_DP * m.density).toInt()
        val prefs = prefs()
        val offset = clampWindowOffset(
            x = prefs.getInt(KEY_X, (m.widthPixels - winW).coerceAtLeast(0)),
            y = prefs.getInt(KEY_Y, m.heightPixels / 3),
            windowWidth = winW,
            windowHeight = winH,
            screenWidth = m.widthPixels,
            screenHeight = m.heightPixels,
        )
        val params = WindowManager.LayoutParams(
            winW,
            winH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                // He must not appear in the screenshot he takes himself: the
                // whole point of the screen mode is to look at somebody else's
                // screen, not at Потсик standing on it.
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = offset.x
            y = offset.y
        }

        val owner = OverlayWindowOwner()
        val shell = OverlayPetShell(this)
        shell.windowParams = params
        shell.onDragStart = {
            // A drag with the cloud open would drag the capsule's window after
            // it, frame by frame, and both would judder — it goes down with a
            // short fade instead of vanishing from under the finger.
            fadeOutCloud()
        }
        shell.onDragEnd = { nx, ny -> prefs.edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply() }
        shell.onDragMoved = { onPetMoved() }
        shell.onSizeChanged = { widthPx ->
            // The size a pinch ends on is the size he keeps: written into the
            // look, so it survives the app being closed and the window being
            // rebuilt — and the in-app character is the same size when the
            // app comes back.
            val sizeDp = widthPx / resources.displayMetrics.density
            val current = appSettings.getPotsikLook()
            val scale = potsikSizeScale(sizeDp)
            if (scale != current.sizeScale) {
                appSettings.setPotsikLook(current.copy(sizeScale = scale))
            }
            PotsikPinch.set(null)
        }
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
                    // Remembered: the gesture detector is keyed on the callback,
                    // and a new lambda on every recomposition would restart it.
                    val onDoubleTap = remember { { toggleCloud() } }
                    PotsikOverlayPet(
                        look = liveLook,
                        cloud = cloudPhase.value,
                        doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
                        onDoubleTap = onDoubleTap,
                    )
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
        // The app came back, or the mode went off: the cloud belongs to the
        // window it was opened over, and goes down with it — the PixelTag rule
        // says one Потсик, and the same rule says one surface of his.
        closeCloud()
        pet = null
        // Whatever the shell was doing ended with the window: the character
        // is not being carried any more, and no pinch is half-done.
        PotsikCarry.setCarried(false)
        PotsikPinch.set(null)
        DesktopPetWindow.setShowing(false)
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
            // The window is already gone; nothing to take down.
        }
        windowOwner?.destroy()
        windowOwner = null
    }

    // ---- the cloud ----------------------------------------------------------

    /**
     * A double tap on the character: open the capsule, or close it when it is
     * already up.
     *
     * The window is built fresh each time rather than hidden and shown: the
     * capsule is placed from where the character is *now*, and a remembered
     * window would open where he used to stand.
     */
    private fun toggleCloud() {
        if (cloud != null) {
            closeCloud()
            return
        }
        val w = wm ?: return
        val view = pet ?: return
        val overlay = PotsikCloudOverlay(
            context = this,
            windowManager = w,
            petParams = { pet?.windowParams },
            characterPx = {
                val look = appSettings.getPotsikLook()
                val sizeDp = PotsikPinch.sizeDp.value ?: potsikSizeDp(look)
                (sizeDp * resources.displayMetrics.density).toInt()
            },
            onOpenText = { startActivity(potsikTextInputIntent(this)) },
            onClosed = { cloudPhase.value = null },
            onPhase = { cloudPhase.value = it },
        )
        cloud = overlay
        overlay.open()
        // A window that refused to appear (permission revoked between the tap
        // and now) leaves nothing behind.
        if (!overlay.isOpen) cloud = null
        // The character's window has to be told, or he would keep drawing the
        // bubble band under a capsule that is already there.
        view.invalidate()
    }

    private fun closeCloud() {
        val overlay = cloud ?: return
        cloud = null
        overlay.close()
        cloudPhase.value = null
    }

    /**
     * The character is being carried: the capsule fades out instead of
     * disappearing in one frame. The service drops its reference at once, so
     * every other path (a rotation, screen off, the app coming back) still
     * closes a cloud it can see; a fade that is cut short only removes the
     * window a moment earlier.
     */
    private fun fadeOutCloud() {
        val overlay = cloud ?: return
        cloud = null
        cloudPhase.value = null
        overlay.closeWithFade()
    }

    /** The character moved under an open cloud: the capsule follows his head. */
    private fun onPetMoved() {
        cloud?.onPetMoved()
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
            )
            screenReceiverRegistered = true
            // The receiver only hears the flips, so the state it reports has
            // to start from the truth: a window born on a dark screen must not
            // breathe, and one born on a lit screen after a stop that happened
            // in the dark must not come back frozen.
            PotsikScreen.setInteractive(screenIsOn())
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
        // Nothing is left listening, so the flag goes back to its safe
        // default: a "dark" nobody can flip back is a character who never
        // wakes again.
        PotsikScreen.setInteractive(true)
    }

    /** `PowerManager.isInteractive` — true while the screen is lit. */
    private fun screenIsOn(): Boolean = (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

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
        // Types that need no runtime permission: the fallback the service can
        // always declare. SPECIAL_USE appears only in API 34, and the
        // three-argument startForeground only in API 29 — the choice itself is
        // `foregroundTypes`, pure and tested, because a wrong mask here is a
        // crash the compiler cannot see.
        val sdkInt = Build.VERSION.SDK_INT
        try {
            // The microphone is a while-in-use permission, not a normal one:
            // the system throws SecurityException when RECORD_AUDIO is not
            // granted, and also when this start comes from the background —
            // which is exactly what a START_STICKY restart after a process
            // death is. The pet must not disappear over the microphone, so the
            // type is attempted, not assumed.
            startForegroundWith(notification, foregroundTypes(sdkInt, withMicrophone = true))
        } catch (e: SecurityException) {
            // Without the microphone the voice flow reports «нет доступа к
            // микрофону» honestly (SpeechToTextError.PermissionDenied); the
            // window itself still exists.
            Log.w("PotsBuild", "microphone FGS type refused, starting without it", e)
            runCatching {
                startForegroundWith(notification, foregroundTypes(sdkInt, withMicrophone = false))
            }.onFailure { failed ->
                // Even the fallback was refused: nothing is left to declare,
                // and an uncaught throw here would crash the whole app.
                Log.w("PotsBuild", "overlay foreground start failed", failed)
                stopSelf()
            }
        }
    }

    /**
     * `startForeground` with the types, or the two-argument form when there are
     * none to declare: the three-argument call exists from API 29 only, and a
     * zero mask declares nothing anyway. The version is checked here as well
     * as in [foregroundTypes], so the call is guarded where lint can see it.
     */
    private fun startForegroundWith(notification: Notification, types: Int) {
        if (types != 0 && Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
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
         * the settings toggle, from the settings screen's ON_RESUME (the
         * return from the system permission screen) and from every app start,
         * so the mode turned on before the process died comes back, and a
         * revoked permission takes the window down.
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
 * The window's shell: a plain frame that owns the drag, the pinch and hosts
 * the Compose content.
 *
 * The gestures live here rather than in Compose on purpose: the window moves
 * in RAW screen coordinates, and a finger on a moving window looks still to
 * the composition inside it — the classic feedback loop. The shell watches the
 * raw events, takes the gesture over past the touch slop (so a micro-shake is
 * never a drag and a tap still reaches the character), and moves the window
 * from the raw delta, the way PixelTag's pet view does.
 *
 * Two fingers are a pinch, and it is the shell's too: it resizes the window
 * with `updateViewLayout` frame by frame — never by recreating the window,
 * which would break the touch stream and the composition — and reports the
 * size it ended on. The size itself is worked out by
 * [pinchedPotsikSize], from the distance between the fingers, so a window
 * moving under the fingers does not change it.
 */
private class OverlayPetShell(context: Context) : FrameLayout(context) {

    var windowParams: WindowManager.LayoutParams? = null
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: ((Int, Int) -> Unit)? = null

    /** The window is moving: whoever follows it (the cloud) is told every frame. */
    var onDragMoved: (() -> Unit)? = null

    /** The character's width in pixels, after a pinch. */
    var onSizeChanged: ((Float) -> Unit)? = null

    private val windowManager: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density: Float
        get() = resources.displayMetrics.density

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var dragging = false

    private var pinching = false
    private var pinchDone = false
    private var pinchStartSpan = 0f
    private var pinchStartSizeDp = 0f

    /** A window transaction is already queued for the next frame. */
    private var layoutPending = false

    /**
     * Applies the window's place and size at most once per frame.
     *
     * A finger reports dozens of moves between two frames, and every
     * `updateViewLayout` is a window-manager transaction on the UI thread —
     * the queue of them is what made the window trail the finger and judder
     * at the edges. The params are still written on every event (they are the
     * last place the finger put him), and the transaction runs on the next
     * frame with whatever the last event left there. One transaction per
     * frame is exactly what the screen can show.
     */
    private fun scheduleLayoutUpdate() {
        if (layoutPending) return
        layoutPending = true
        postOnAnimation {
            layoutPending = false
            val params = windowParams ?: return@postOnAnimation
            runCatching { windowManager.updateViewLayout(this, params) }
        }
    }

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
                pinching = false
                pinchDone = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) {
                    // The second finger turns the gesture into a pinch: take
                    // it over before the character inside sees a second touch.
                    beginPinch(ev)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !pinching && !pinchDone) {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (dx * dx + dy * dy > touchSlop * touchSlop.toFloat()) {
                        // The gesture declared itself a drag: take it over,
                        // the character inside sees a cancel, not a pet.
                        dragging = true
                        // The cloud closes the moment a drag starts: a capsule
                        // following a moving window frame by frame judders.
                        onDragStart?.invoke()
                        // The common layer draws the carry — both arms up —
                        // so the desktop and the app show the same thing.
                        PotsikCarry.setCarried(true)
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

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2 && !pinching) beginPinch(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pinching) {
                    updatePinch(event)
                    return true
                }
                if (dragging) {
                    moveDrag(event)
                    return true
                }
                return pinchDone
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // The second finger lifting ends the pinch: the finger left
                // does not turn into a carry.
                if (pinching) endPinch()
                return pinchDone
            }

            MotionEvent.ACTION_UP -> {
                if (pinching) endPinch()
                if (dragging) finishDrag(params, save = true)
                pinchDone = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // The gesture was taken away — the window is going down, the
                // app came forward. The size the pinch had already applied to
                // the window is kept (it is on screen, so it must be the size
                // he comes back at); a drag is not written down, because it
                // never ended where the finger meant to leave him.
                if (pinching) endPinch()
                pinchDone = false
                if (dragging) finishDrag(params, save = false)
                return true
            }
        }
        return false
    }

    // ---- the drag ------------------------------------------------------------

    private fun moveDrag(event: MotionEvent) {
        val params = windowParams ?: return
        params.x = downWinX + (event.rawX - downRawX).toInt()
        params.y = downWinY + (event.rawY - downRawY).toInt()
        // He stops at the edge instead of going over it: a pet dragged off the
        // screen is a pet the user cannot get back.
        clampToScreen(params, resources.displayMetrics)
        scheduleLayoutUpdate()
        onDragMoved?.invoke()
    }

    private fun finishDrag(params: WindowManager.LayoutParams, save: Boolean) {
        dragging = false
        PotsikCarry.setCarried(false)
        clampToScreen(params, resources.displayMetrics)
        scheduleLayoutUpdate()
        if (save) onDragEnd?.invoke(params.x, params.y)
    }

    // ---- the pinch -----------------------------------------------------------

    private fun beginPinch(event: MotionEvent) {
        val params = windowParams ?: return
        // A second finger during a carry is a pinch, not a throw: the drag
        // ends where he is and the size starts from here.
        if (dragging) finishDrag(params, save = true)
        pinching = true
        pinchDone = false
        pinchStartSpan = span(event)
        pinchStartSizeDp = params.width / density
        PotsikCarry.setCarried(false)
    }

    private fun updatePinch(event: MotionEvent) {
        val params = windowParams ?: return
        if (event.pointerCount < 2) return
        val sizeDp = pinchedPotsikSize(pinchStartSizeDp, pinchStartSpan, span(event))
        val widthPx = (sizeDp * density).roundToInt().coerceAtLeast(1)
        params.width = widthPx
        params.height = widthPx + (DESKTOP_PET_BUBBLE_ROOM_DP * density).roundToInt()
        clampToScreen(params, resources.displayMetrics)
        scheduleLayoutUpdate()
        // The character inside is drawn from this, so the window and the
        // drawing grow together instead of the window outrunning him.
        PotsikPinch.set(sizeDp)
    }

    private fun endPinch() {
        if (!pinching) return
        pinching = false
        pinchDone = true
        val params = windowParams ?: return
        clampToScreen(params, resources.displayMetrics)
        scheduleLayoutUpdate()
        onSizeChanged?.invoke(params.width.toFloat())
    }

    /** The distance between the first two fingers, in view pixels. */
    private fun span(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Keeps a window fully on the screen after a move, a resize or a rotation.
 *
 * One implementation for the whole file: the shell calls it from its gestures
 * and the service from [PotsOverlayService.onConfigurationChanged], and both
 * hand in the metrics they hold — the shell's view resources and the service's
 * resources, which the system has already refreshed when the callback arrives.
 * Top-level `private` keeps it off the API of both classes.
 */
private fun clampToScreen(
    params: WindowManager.LayoutParams,
    screen: DisplayMetrics,
) {
    val offset = clampWindowOffset(
        x = params.x,
        y = params.y,
        windowWidth = params.width,
        windowHeight = params.height,
        screenWidth = screen.widthPixels,
        screenHeight = screen.heightPixels,
    )
    params.x = offset.x
    params.y = offset.y
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
