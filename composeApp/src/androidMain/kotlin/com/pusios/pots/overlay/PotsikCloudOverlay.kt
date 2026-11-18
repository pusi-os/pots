package com.pusios.pots.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.PowerManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import com.pusios.pots.data.DataRepository
import com.pusios.pots.data.ThemeMode
import com.pusios.pots.handsfree.HandsFreeController
import com.pusios.pots.handsfree.HandsFreeStatus
import com.pusios.pots.handsfree.SpeechToTextError
import com.pusios.pots.handsfree.createSpeechToText
import com.pusios.pots.ui.DarkColorScheme
import com.pusios.pots.ui.LightColorScheme
import com.pusios.pots.ui.potsik.PotsikCloudAsker
import com.pusios.pots.ui.potsik.PotsikCloudCapsule
import com.pusios.pots.ui.potsik.PotsikCloudPhase
import com.pusios.pots.ui.potsik.PotsikCloudState
import com.pusios.pots.ui.potsik.PotsikCloudTrouble
import com.pusios.pots.ui.potsik.PotsikCloudVoice
import com.pusios.pots.ui.potsik.potsikCloudPlacement
import com.pusios.pots.ui.potsik.potsikCloudWindowSize
import com.pusios.pots.ui.tts.DEFAULT_PIPER_VOICE_ID
import com.pusios.pots.ui.tts.PotsTextToSpeech
import com.pusios.pots.ui.tts.createPiperTextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The «облачко»: the capsule that opens on a double tap on the character while
 * he stands on somebody else's screen.
 *
 * It is its own window, not a bigger character window, and that is a platform
 * fact rather than a preference: the old trick of one full-screen transparent
 * window with `FLAG_NOT_TOUCHABLE` stopped working on Android 12 (untrusted
 * touch, the 0.8 opacity threshold), so a window that only needs a capsule is a
 * window exactly the capsule's size. The character's window keeps eating only
 * the taps meant for him.
 *
 * The window is [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY], which
 * the system always draws below the status bar and — the reason the text field
 * is a separate activity — below the keyboard.
 *
 * Everything that decides *what* the cloud shows lives in commonMain
 * ([PotsikCloudState], [potsikCloudPlacement], [PotsikCloudCapsule]); this file
 * is the window, the microphone and the lifetime.
 */
internal class PotsikCloudOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    /** Where the character's window is right now, or null when it is down. */
    private val petParams: () -> WindowManager.LayoutParams?,
    /** The character's drawn size inside his window, in pixels. */
    private val characterPx: () -> Int,
    /** Open the text field: a translucent activity, because the keyboard would cover an overlay. */
    private val onOpenText: () -> Unit,
    /** Told when the cloud goes down, so the service can drop its reference. */
    private val onClosed: () -> Unit,
    /** Told what the cloud shows, so the character's window can react to it. */
    private val onPhase: (PotsikCloudPhase?) -> Unit,
) {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settings: AppSettings? = GlobalContext.getOrNull()?.getOrNull()
    private val repository: DataRepository? = GlobalContext.getOrNull()?.getOrNull()

    private val asker = PotsikCloudAsker(scope, repository)

    /** The double-tap window is the platform's, never a constant of ours. */
    val doubleTapTimeoutMs: Long = ViewConfiguration.getDoubleTapTimeout().toLong()

    private var shell: CloudShell? = null
    private var owner: CloudWindowOwner? = null

    private val speechToText by lazy {
        val engine = settings?.getSttEngine()
        if (engine == null) null else createSpeechToText(engine, settings?.getSttVoskModel().orEmpty())
    }

    private val textToSpeech: PotsTextToSpeech? by lazy {
        val voice = settings?.getTtsPiperVoice().orEmpty().ifEmpty { DEFAULT_PIPER_VOICE_ID }
        createPiperTextToSpeech(voice)
    }

    /** False when the device has no recognizer at all: the voice button is drawn dead. */
    val voiceAvailable: Boolean
        get() = speechToText?.isAvailable == true

    private val handsFree: HandsFreeController by lazy {
        HandsFreeController(
            scope = scope,
            textToSpeech = textToSpeech,
            speechToText = speechToText,
            onSubmit = { question -> asker.ask(question) },
            // An overlay window cannot raise the system permission dialog —
            // only an activity can. Without the permission the pill says so
            // and offers [Повторить]; the dialog itself lives in the app.
            requestMicrophonePermission = { hasMicrophone() },
        )
    }

    private val cloudState = mutableStateOf(PotsikCloudState())

    /** The pill's state; a Compose `State`, so the capsule redraws when it changes. */
    private val state: PotsikCloudState get() = cloudState.value

    private var watcherJob: Job? = null

    val isOpen: Boolean get() = shell != null

    private fun hasMicrophone(): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** The screen is off or the device is locked: the microphone would not start anyway. */
    private fun screenInteractive(): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)

    // ---- opening and closing -------------------------------------------------

    /**
     * Opens the capsule over the character, or closes it when it is already up.
     *
     * A second double tap is the spec's own "close": the same gesture that
     * opened the cloud takes it away.
     */
    fun toggle() {
        if (isOpen) close() else open()
    }

    fun open() {
        if (isOpen) return
        val params = petParams() ?: return
        cloudState.value = PotsikCloudState()
        onPhase(PotsikCloudPhase.Menu)
        val view = CloudShell(context).apply { onOutsideTouch = { close() } }
        val owner = CloudWindowOwner()
        view.attachContent(owner) { PotsikCloudRoot() }
        val windowParams = buildParams(params)
        try {
            windowManager.addView(view, windowParams)
        } catch (_: Throwable) {
            owner.destroy()
            return
        }
        view.windowParams = windowParams
        shell = view
        this.owner = owner
        owner.start()
        // Opening is the confirmation: the same haptic the app gives a tap.
        view.haptic(HapticFeedbackConstants.CONFIRM)
        watchVoice()
    }

    /** Takes the window down now — no fade: the caller is a drag, a rotation or the app coming back. */
    fun close() {
        val view = shell ?: return
        shell = null
        watcherJob?.cancel()
        watcherJob = null
        handsFree.stop()
        PotsikCloudVoice.setActive(false)
        asker.reset()
        cloudState.value = PotsikCloudState()
        runCatching { windowManager.removeView(view) }
        owner?.destroy()
        owner = null
        onClosed()
    }

    /** The character moved: the capsule follows his head. */
    fun onPetMoved() {
        val view = shell ?: return
        val params = view.windowParams ?: return
        val pet = petParams() ?: return
        place(params, pet)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun buildParams(pet: WindowManager.LayoutParams): WindowManager.LayoutParams {
        val size = potsikCloudWindowSize(state)
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (size.widthDp * density).roundToInt(),
            (size.heightDp * density).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        place(params, pet)
        return params
    }

    private fun place(params: WindowManager.LayoutParams, pet: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val placement = potsikCloudPlacement(
            petX = pet.x,
            petY = pet.y,
            petWidth = pet.width,
            petHeight = pet.height,
            characterPx = characterPx(),
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            density = metrics.density,
            cloudWidthPx = params.width,
            cloudHeightPx = params.height,
        )
        params.x = placement.x
        params.y = placement.y
    }

    // ---- the state -----------------------------------------------------------

    private fun setState(next: PotsikCloudState) {
        val previous = cloudState.value
        cloudState.value = next
        if (previous.phase != next.phase) onPhase(next.phase)
        val view = shell ?: return
        val params = view.windowParams ?: return
        val size = potsikCloudWindowSize(next)
        val density = context.resources.displayMetrics.density
        val width = (size.widthDp * density).roundToInt()
        val height = (size.heightDp * density).roundToInt()
        if (params.width != width || params.height != height) {
            // The window's bounds jump; the content crossfades inside them.
            // Animating an overlay's own size stutters, so it is not done.
            params.width = width
            params.height = height
            petParams()?.let { place(params, it) }
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        if (previous.phase != PotsikCloudPhase.Listening && next.phase == PotsikCloudPhase.Listening) {
            view.haptic(HapticFeedbackConstants.SEGMENT_TICK)
        }
    }

    /**
     * Follows the voice loop and the chat, and folds them into one pill state.
     *
     * One collector tree, not one per surface: the pill shows exactly one thing
     * at a time, and a state machine spread over unrelated effects is how two
     * of them end up disagreeing.
     */
    private fun watchVoice() {
        watcherJob?.cancel()
        watcherJob = scope.launch {
            launch {
                handsFree.status.collect { status ->
                    PotsikCloudVoice.setActive(status != HandsFreeStatus.Idle)
                    setState(
                        when (status) {
                            HandsFreeStatus.Idle -> PotsikCloudState(phase = PotsikCloudPhase.Menu)

                            HandsFreeStatus.Listening -> PotsikCloudState(
                                phase = PotsikCloudPhase.Listening,
                                transcript = handsFree.transcript.value,
                            )

                            HandsFreeStatus.Thinking -> PotsikCloudState(
                                phase = PotsikCloudPhase.Thinking,
                                transcript = handsFree.transcript.value,
                            )

                            HandsFreeStatus.Speaking -> PotsikCloudState(
                                phase = PotsikCloudPhase.Speaking,
                                reply = state.reply,
                            )

                            HandsFreeStatus.Error -> PotsikCloudState(
                                phase = PotsikCloudPhase.Error,
                                trouble = when (handsFree.error.value) {
                                    SpeechToTextError.PermissionDenied -> PotsikCloudTrouble.PermissionDenied
                                    SpeechToTextError.NoMatch -> PotsikCloudTrouble.NoMatch
                                    SpeechToTextError.Failed -> PotsikCloudTrouble.ListenTimeout
                                    SpeechToTextError.Unavailable, null -> PotsikCloudTrouble.NoRecognizer
                                },
                            )
                        },
                    )
                }
            }
            launch {
                handsFree.transcript.collect { partial ->
                    if (state.phase == PotsikCloudPhase.Listening) {
                        cloudState.value = state.copy(transcript = partial)
                    }
                }
            }
            launch {
                asker.answer.collect { answer ->
                    when (answer) {
                        is PotsikCloudAsker.Answer.Thinking -> setState(
                            PotsikCloudState(phase = PotsikCloudPhase.Thinking, transcript = answer.question),
                        )

                        is PotsikCloudAsker.Answer.Answered -> onAnswered(answer)

                        is PotsikCloudAsker.Answer.Failed -> {
                            // Nothing is lost: the question stays on the pill
                            // marked as not sent, with [Повторить] beside it.
                            handsFree.stop()
                            PotsikCloudVoice.setActive(false)
                            setState(
                                PotsikCloudState(
                                    phase = PotsikCloudPhase.Error,
                                    transcript = answer.question,
                                    trouble = PotsikCloudTrouble.NoNetwork,
                                    unsent = true,
                                ),
                            )
                        }

                        PotsikCloudAsker.Answer.Idle -> Unit
                    }
                }
            }
            // The [Отмена] affordance of the thinking pill appears on the third second.
            launch {
                while (true) {
                    delay(THINKING_CANCEL_MS)
                    if (state.phase == PotsikCloudPhase.Thinking && !state.cancelOffered) {
                        cloudState.value = state.copy(cancelOffered = true)
                    }
                }
            }
        }
    }

    private suspend fun onAnswered(answer: PotsikCloudAsker.Answer.Answered) {
        setState(PotsikCloudState(phase = PotsikCloudPhase.Speaking, reply = answer.text))
        if (textToSpeech != null) {
            // The controller speaks the reply and reopens the microphone when
            // it is done — the loop closing itself, exactly as in the app.
            handsFree.onAssistantReply(answer.messageId, answer.text)
        } else {
            // No synthesizer: the answer stays on the pill long enough to be
            // read, then the loop goes back to listening.
            delay(NO_SPEECH_READING_MS)
            if (state.phase == PotsikCloudPhase.Speaking) handsFree.resume()
        }
    }

    // ---- the actions ---------------------------------------------------------

    fun onVoice() {
        if (!voiceAvailable) return
        if (!screenInteractive()) {
            setState(PotsikCloudState(phase = PotsikCloudPhase.Error, trouble = PotsikCloudTrouble.ScreenOff))
            return
        }
        if (!hasMicrophone()) {
            setState(PotsikCloudState(phase = PotsikCloudPhase.Error, trouble = PotsikCloudTrouble.PermissionDenied))
            return
        }
        handsFree.start()
    }

    fun onText() {
        // The field is an activity, and the cloud's window goes down with it:
        // one Потсик and one surface, as everywhere else in the app.
        close()
        onOpenText()
    }

    /** A tap on the pill: the barge-in while he is talking, and a fresh attempt otherwise. */
    fun onPillTap() {
        if (state.phase == PotsikCloudPhase.Speaking || state.phase == PotsikCloudPhase.Listening) {
            handsFree.interrupt()
        }
    }

    /** [Отмена] / [×] on the pill: the loop stops, the cloud goes back to the menu. */
    fun onCancel() {
        handsFree.stop()
        PotsikCloudVoice.setActive(false)
        asker.reset()
        setState(PotsikCloudState())
    }

    fun onRetry() {
        if (state.unsent) {
            asker.retry()
            return
        }
        handsFree.resume()
    }

    // ---- drawing -------------------------------------------------------------

    @Composable
    private fun PotsikCloudRoot() {
        val appSettings = settings
        val themeMode by (appSettings?.themeModeFlow ?: remember { MutableStateFlow(ThemeMode.System) }).collectAsState()
        val appLanguage by (appSettings?.appLanguageFlow ?: remember { MutableStateFlow(AppLanguage.System) }).collectAsState()
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
        // 120 ms in, per the spec: the content fades and grows inside a window
        // whose bounds were already set.
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            appear.animateTo(1f, tween(durationMillis = OPEN_MS, easing = FastOutSlowInEasing))
        }
        CompositionLocalProvider(LocalAppLanguageCode provides languageCode) {
            MaterialTheme(colorScheme = if (dark) DarkColorScheme else LightColorScheme) {
                PotsikCloudCapsule(
                    state = state,
                    voiceAvailable = voiceAvailable,
                    onVoice = ::onVoice,
                    onText = ::onText,
                    onPillTap = ::onPillTap,
                    onCancel = ::onCancel,
                    onRetry = ::onRetry,
                    modifier = Modifier.graphicsLayer {
                        alpha = appear.value
                        val scale = 0.92f + 0.08f * appear.value
                        scaleX = scale
                        scaleY = scale
                    },
                )
            }
        }
    }

    private companion object {
        const val OPEN_MS = 120
        const val THINKING_CANCEL_MS = 3_000L
        const val NO_SPEECH_READING_MS = 6_000L
    }
}

/**
 * The window's shell: it hosts the Compose content and turns a tap outside the
 * capsule into a close, which is the spec's «тап мимо».
 *
 * `FLAG_WATCH_OUTSIDE_TOUCH` delivers `ACTION_OUTSIDE` here; the capsule itself
 * keeps its own taps.
 */
private class CloudShell(context: Context) : FrameLayout(context) {

    var windowParams: WindowManager.LayoutParams? = null
    var onOutsideTouch: (() -> Unit)? = null

    fun attachContent(owner: CloudWindowOwner, content: @Composable () -> Unit) {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        val compose = ComposeView(context).apply { setContent(content) }
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** A haptic tick from a window that has no activity behind it. */
    fun haptic(constant: Int) {
        runCatching { performHapticFeedback(constant) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }
}

/**
 * The lifecycle a ComposeView in a system window asks for — the same three
 * owners the character's window provides, for the same reason: an overlay has
 * no activity behind it.
 */
private class CloudWindowOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

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

/** The intent that opens the text field; built here, read by the activity. */
internal fun potsikTextInputIntent(context: Context): Intent = Intent(context, PotsikTextInputActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
