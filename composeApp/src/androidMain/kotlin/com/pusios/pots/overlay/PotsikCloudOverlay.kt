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
import android.view.inputmethod.InputMethodManager
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
import com.pusios.pots.assistant.questionWithScreenContext
import com.pusios.pots.data.AppLanguage
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.DataRepository
import com.pusios.pots.data.ThemeMode
import com.pusios.pots.handsfree.HandsFreeController
import com.pusios.pots.handsfree.HandsFreeStatus
import com.pusios.pots.handsfree.SpeechToTextError
import com.pusios.pots.handsfree.createSpeechToText
import com.pusios.pots.shared.R
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.PermissionController
import com.pusios.pots.ui.DarkColorScheme
import com.pusios.pots.ui.LightColorScheme
import com.pusios.pots.ui.potsik.PotsikCloudAsker
import com.pusios.pots.ui.potsik.PotsikCloudCapsule
import com.pusios.pots.ui.potsik.PotsikCloudDraft
import com.pusios.pots.ui.potsik.PotsikCloudPhase
import com.pusios.pots.ui.potsik.PotsikCloudState
import com.pusios.pots.ui.potsik.PotsikCloudTrouble
import com.pusios.pots.ui.potsik.PotsikCloudVoice
import com.pusios.pots.ui.potsik.PotsikScreenMode
import com.pusios.pots.ui.potsik.PotsikScreenPhase
import com.pusios.pots.ui.potsik.PotsikScreenStep
import com.pusios.pots.ui.potsik.PotsikShotHolder
import com.pusios.pots.ui.potsik.PotsikShotUi
import com.pusios.pots.ui.potsik.isScreenTrouble
import com.pusios.pots.ui.potsik.potsikCloudInputTop
import com.pusios.pots.ui.potsik.potsikCloudPlacement
import com.pusios.pots.ui.potsik.potsikCloudSendEnabled
import com.pusios.pots.ui.potsik.potsikCloudWindowSize
import com.pusios.pots.ui.potsik.potsikScreenshotSizeLabel
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
 * the system always draws below the status bar and below the keyboard — which
 * is why the text field's window is kept in the top half of the screen rather
 * than being lifted by insets an overlay never receives.
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

    /** A fade-out is running: the window is still there but already leaving. */
    private var fading = false

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
            // Every question leaves through here, so the assist snapshot — when
            // there is one — is attached to whichever question comes first.
            onSubmit = { question -> asker.ask(questionWithScreen(question)) },
            // An overlay window cannot raise the system permission dialog —
            // only an activity can. Without the permission the pill says so
            // and offers [Повторить]; the dialog itself lives in the app.
            requestMicrophonePermission = { hasMicrophone() },
        )
    }

    private val cloudState = mutableStateOf(PotsikCloudState())

    /** The pill's state; a Compose `State`, so the capsule redraws when it changes. */
    private val state: PotsikCloudState get() = cloudState.value

    /**
     * Flow 3: consent once per mode, a frame per tap.
     *
     * The mode lives as long as the cloud does — the pill is where it says what
     * it is doing, and a projection with no cloud on screen is a status-bar chip
     * nobody asked for. Closing the cloud ends it, which is also why the next
     * double tap asks for consent again.
     */
    private val screenMode = PotsikScreenMode(AndroidScreenProjector(context))

    /** The frame on the pill, already decoded for Compose. */
    private val screenShot = mutableStateOf<PotsikShotUi?>(null)

    /**
     * The text of the screen taken at the assist invocation, while it is still
     * owed to the next question.
     *
     * Null when there is none: the cloud was opened by a double tap, the
     * snapshot never arrived, or it has already gone out with a question. It is
     * a single moment of the screen — the platform takes it once — so it is
     * spent, not reused.
     */
    private var assistScreenText: String? = null

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
        val view = CloudShell(context).apply { onOutsideTouch = { close() } }
        val owner = CloudWindowOwner()
        view.attachContent(owner) { PotsikCloudRoot() }
        val windowParams = buildParams(params)
        try {
            windowManager.addView(view, windowParams)
        } catch (_: Throwable) {
            // No window after all (the permission was revoked between the tap
            // and now). The character is told, or he would keep suppressing his
            // own bubbles for a cloud that never appeared.
            owner.destroy()
            onClosed()
            return
        }
        view.windowParams = windowParams
        shell = view
        this.owner = owner
        owner.start()
        // Only now, with the window really up, is the cloud part of the screen.
        onPhase(PotsikCloudPhase.Menu)
        // Opening is the confirmation: the same haptic the app gives a tap.
        view.haptic(HapticFeedbackConstants.CONFIRM)
        watchVoice()
    }

    /**
     * The assist gesture: the cloud opens over the pet that is already standing
     * on somebody else's screen, with the text of that screen in hand, and the
     * microphone opens straight away — the same path the microphone button
     * takes.
     *
     * The text is the platform's one-shot snapshot of the top activity taken at
     * invocation time. It may also be null: a secure window, an app that blocks
     * assist, a device that never sends assist data. Then the cloud opens
     * exactly as a double tap opens it, which is the honest degradation — the
     * gesture is not a promise that the screen was readable.
     */
    fun onAssistInvocation(screenText: String?) {
        assistScreenText = screenText?.takeIf { it.isNotBlank() }
        // The menu state first, so the note above the capsule is up before the
        // microphone reports its own phase.
        setState(PotsikCloudState())
        onVoice()
    }

    /**
     * The question as the chat receives it: with the screen's text attached when
     * one is still in hand.
     *
     * The snapshot is spent by the first question — it is a picture of one
     * moment, and a second question asked a minute later is about a different
     * screen. The header is the app's own string, so the person reading the chat
     * sees where the block came from.
     */
    private fun questionWithScreen(question: String): String {
        val screen = assistScreenText ?: return question
        assistScreenText = null
        return questionWithScreenContext(
            question = question,
            screenContext = screen,
            header = context.getString(R.string.assist_screen_context_header),
        )
    }

    /**
     * The pill's [Настройки]: the microphone was refused, and an overlay window
     * cannot raise the system permission dialog — only the app's settings page
     * can. The user grants it there and taps the microphone again.
     */
    fun onOpenSettings() {
        runCatching { PermissionController(AppPermission.RECORD_AUDIO).openAppSettings() }
    }

    /** Takes the window down now — no fade: the caller is a rotation, the app coming back or screen off. */
    fun close() {
        val view = shell ?: return
        shell = null
        fading = false
        // A snapshot that was never asked about goes with the window: the next
        // cloud is a new invocation with a new moment of the screen.
        assistScreenText = null
        // The screen mode belongs to this window: its listener goes first, so a
        // session that lands after this point has nobody to hand a frame to.
        PotsikScreenProjection.listener = null
        screenMode.exit()
        screenShot.value = null
        // A fade that is still running ends here rather than on top of a
        // window that has already been taken down.
        view.animate().cancel()
        watcherJob?.cancel()
        watcherJob = null
        handsFree.stop()
        PotsikCloudVoice.setActive(false)
        asker.reset()
        // A question nobody will answer now must not leave a marker behind that
        // clears the draft when the same text is asked by voice later.
        PotsikCloudDraft.forgetSent()
        cloudState.value = PotsikCloudState()
        runCatching { windowManager.removeView(view) }
        owner?.destroy()
        owner = null
        onClosed()
    }

    /**
     * Takes the window down with a short fade.
     *
     * Only the drag uses it: a capsule that vanishes in one frame next to a
     * character that is being carried reads as a glitch. Rotation, the app
     * coming back and screen off keep [close] — nobody is watching a fade there.
     */
    fun closeWithFade() {
        val view = shell ?: return
        if (fading) return
        fading = true
        view.animate()
            .alpha(0f)
            .setDuration(CLOUD_FADE_MS)
            .withEndAction {
                fading = false
                close()
            }
            .start()
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
        val size = potsikCloudWindowSize(state, voiceAvailable)
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (size.widthDp * density).roundToInt(),
            (size.heightDp * density).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // The cloud must not appear in its own screenshot: a capsule
                // over the picture it took looks like a broken feature.
                WindowManager.LayoutParams.FLAG_SECURE,
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
        if (state.phase == PotsikCloudPhase.TextInput) {
            // An overlay is drawn below the IME, so a field under the keyboard
            // is a field nobody can see. The window moves up instead of the
            // keyboard moving the window: insets never reach an overlay.
            params.y = potsikCloudInputTop(
                y = params.y,
                windowHeightPx = params.height,
                screenHeightPx = metrics.heightPixels,
            )
        }
    }

    // ---- the state -----------------------------------------------------------

    private fun setState(requested: PotsikCloudState) {
        // The assist snapshot belongs to the cloud, not to one pill state: the
        // voice loop rebuilds the state on every status change, and the note
        // above the capsule must not vanish because the loop moved from
        // Listening to Thinking.
        val next = if (assistScreenText == null) requested else requested.copy(assistContext = true)
        val previous = cloudState.value
        cloudState.value = next
        if (previous.phase != next.phase) onPhase(next.phase)
        val view = shell ?: return
        val params = view.windowParams ?: return
        val size = potsikCloudWindowSize(next, voiceAvailable)
        val density = context.resources.displayMetrics.density
        val width = (size.widthDp * density).roundToInt()
        val height = (size.heightDp * density).roundToInt()
        val resized = params.width != width || params.height != height
        // The field is the only phase that takes the keyboard; everything else
        // must not steal focus (or the back key) from the app underneath.
        val refocused = applyInputFocus(params, next.phase == PotsikCloudPhase.TextInput)
        if (resized || refocused) {
            // The window's bounds jump; the content crossfades inside them.
            // Animating an overlay's own size stutters, so it is not done.
            params.width = width
            params.height = height
            petParams()?.let { place(params, it) }
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        if (previous.phase == PotsikCloudPhase.TextInput && next.phase != PotsikCloudPhase.TextInput) {
            hideKeyboard()
        }
        if (previous.phase != PotsikCloudPhase.Listening && next.phase == PotsikCloudPhase.Listening) {
            view.haptic(HapticFeedbackConstants.SEGMENT_TICK)
        }
    }

    /**
     * Makes the window focusable exactly while the text field is on it.
     *
     * `FLAG_NOT_FOCUSABLE` is what keeps the capsule from taking the keyboard
     * (and the back key) away from the app underneath while it is a menu or a
     * pill — but a window under that flag never receives the IME either, so the
     * field cannot be typed into. Returns true when the flags changed.
     */
    private fun applyInputFocus(params: WindowManager.LayoutParams, focused: Boolean): Boolean {
        val focusable = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        if (focusable == focused) return false
        params.flags = if (focused) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        params.softInputMode = if (focused) {
            // ADJUST_RESIZE is what the platform can offer a window of our own;
            // the keyboard itself is drawn above an overlay, so the field is
            // also kept out of its way in [place].
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        return true
    }

    /** Takes the keyboard down with the field. */
    private fun hideKeyboard() {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(shell?.windowToken, 0)
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

                            // «Не расслышал, повторите» — its own status, because the
                            // loop reopens the microphone by itself in two seconds.
                            HandsFreeStatus.NoMatch -> PotsikCloudState(
                                phase = PotsikCloudPhase.Error,
                                trouble = PotsikCloudTrouble.NoMatch,
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
        // Only the field's own question clears the field: a voice or screenshot
        // answer must not throw away what the user typed and left unsent.
        PotsikCloudDraft.clearIfAnswerTo(answer.question)
        setState(PotsikCloudState(phase = PotsikCloudPhase.Speaking, reply = answer.text))
        // A reply is spoken and the microphone reopened only when the voice loop
        // is running: onAssistantReply returns at once otherwise (a typed
        // question leaves it Idle), so the answer just stays on the pill.
        handsFree.onAssistantReply(answer.messageId, answer.text)
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

    /**
     * «Текст»: the field opens in the cloud itself, not in the app.
     *
     * The window keeps its place over his head (raised into the top half, where
     * the keyboard cannot reach it) and takes input focus for the phase. The
     * plain text flow asks no question about a picture: whatever the screen
     * mode left pending is not this question's attachment.
     */
    fun onText() {
        PotsikShotHolder.clear()
        setState(PotsikCloudState(phase = PotsikCloudPhase.TextInput))
    }

    /** [Отправить] on the field: the same asker the voice loop uses, so the answer lands on the same pill. */
    fun onSendText() {
        val draft = PotsikCloudDraft.text.value
        if (!potsikCloudSendEnabled(draft)) return
        // The assist snapshot, if any, goes out with this question — and the
        // field is marked with what was actually sent, so the answer to it
        // clears the field and nothing else does.
        val question = questionWithScreen(draft).trim()
        PotsikCloudDraft.markSent(question)
        asker.ask(question)
    }

    /** [×] on the field: back to the menu. The draft is kept for the next time. */
    fun onCloseText() {
        setState(PotsikCloudState())
    }

    /**
     * «Экран»: the frame, or the consent dialog that has to come first.
     *
     * Consent is granted once per mode — the system dialog is only raised when
     * no session lives, and the frame is taken when the dialog answers, not
     * before. While the dialog is up the pill says so and offers no buttons.
     */
    fun onScreen() {
        PotsikScreenProjection.listener = screenListener
        scope.launch {
            when (screenMode.tap()) {
                PotsikScreenStep.AskConsent -> {
                    setState(PotsikCloudState(phase = PotsikCloudPhase.AwaitingConsent))
                    context.startActivity(potsikScreenConsentIntent(context))
                }

                PotsikScreenStep.Captured -> showScreen(announce = true)
            }
        }
    }

    /** [×] on the shot pill: the frame goes, the session stays — the next tap takes another one. */
    fun onRemoveShot() {
        screenMode.clearShot()
        PotsikShotHolder.clear()
        showScreen()
    }

    /**
     * The question about the frame, in the same field Flow 2 uses.
     *
     * The frame is handed over in [PotsikShotHolder], because the field is
     * another activity; the cloud closes with it, and the session ends with the
     * cloud — the picture is already in hand, so nothing is lost, and no
     * projection is left running behind a closed capsule.
     */
    private fun openQuestionAboutShot() {
        val shot = screenMode.state.value.shot ?: return
        PotsikShotHolder.set(shot)
        close()
        onOpenText()
    }

    /**
     * The session's answers. They arrive on the main thread, where the cloud's
     * own state lives, so nothing has to be posted.
     */
    private val screenListener = object : PotsikScreenProjection.Listener {
        override fun onScreenReady() {
            scope.launch {
                screenMode.onConsentGranted()
                showScreen(announce = true)
            }
        }

        override fun onScreenDenied() {
            screenMode.onConsentDenied()
            showScreen()
        }

        override fun onScreenFailed() {
            screenMode.onSessionFailed()
            showScreen()
        }

        override fun onScreenRevoked() {
            screenMode.onRevoked()
            showScreen()
        }
    }

    /**
     * Draws what the screen mode has: the wait, the frame, or the reason it did
     * not work. Every branch of the mode goes through here, so the pill and the
     * state machine cannot disagree about what is happening.
     */
    private fun showScreen(announce: Boolean = false) {
        val screen = screenMode.state.value
        when (screen.phase) {
            PotsikScreenPhase.Shot -> {
                screenShot.value = screen.shot?.let { shot ->
                    PotsikShotUi(preview = shot.preview(), label = potsikScreenshotSizeLabel(shot.width, shot.height))
                }
                setState(PotsikCloudState(phase = PotsikCloudPhase.Shot))
                if (announce) shell?.haptic(HapticFeedbackConstants.CONFIRM)
            }

            PotsikScreenPhase.AwaitingConsent -> setState(PotsikCloudState(phase = PotsikCloudPhase.AwaitingConsent))

            PotsikScreenPhase.Failed -> setState(
                PotsikCloudState(phase = PotsikCloudPhase.Error, trouble = screen.trouble),
            )

            // Back to the menu: the session is the mode's own business and
            // survives a removed frame.
            PotsikScreenPhase.Off -> {
                screenShot.value = null
                setState(PotsikCloudState())
            }
        }
    }

    /** A tap on the pill: the barge-in while he is talking, the question on a shot, a fresh attempt otherwise. */
    fun onPillTap() {
        when (state.phase) {
            PotsikCloudPhase.Shot -> openQuestionAboutShot()

            PotsikCloudPhase.Speaking, PotsikCloudPhase.Listening ->
                // The voice loop's barge-in — but a reply to a typed question
                // has no loop behind it, and [Стоп] must still dismiss it.
                if (handsFree.isActive) handsFree.interrupt() else setState(PotsikCloudState())

            else -> Unit
        }
    }

    /** [Отмена] / [×] on the pill: the loop stops, the cloud goes back to the menu. */
    fun onCancel() {
        handsFree.stop()
        PotsikCloudVoice.setActive(false)
        asker.reset()
        PotsikCloudDraft.forgetSent()
        setState(PotsikCloudState())
    }

    fun onRetry() {
        if (state.unsent) {
            asker.retry()
            return
        }
        // A screen failure is retried the way it was started: ask for consent
        // again if the session is gone, take a frame if it is still there.
        if (state.trouble.isScreenTrouble()) {
            onScreen()
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
        // Every tap inside the cloud is the confirmation the app gives a tap. The
        // capsule lives in commonMain and knows nothing about Android, so the
        // haptic is added here, where the window is: it covers the three buttons
        // and the pill's own [Стоп] / [×], which go through the same callbacks.
        val tapped: (() -> Unit) -> () -> Unit = { action ->
            {
                shell?.haptic(HapticFeedbackConstants.CONFIRM)
                action()
            }
        }
        CompositionLocalProvider(LocalAppLanguageCode provides languageCode) {
            MaterialTheme(colorScheme = if (dark) DarkColorScheme else LightColorScheme) {
                val draft by PotsikCloudDraft.text.collectAsState()
                PotsikCloudCapsule(
                    state = state,
                    voiceAvailable = voiceAvailable,
                    shot = screenShot.value,
                    draft = draft,
                    onDraftChange = PotsikCloudDraft::set,
                    onSendText = tapped(::onSendText),
                    onCloseText = tapped(::onCloseText),
                    onVoice = tapped(::onVoice),
                    onText = tapped(::onText),
                    onScreen = tapped(::onScreen),
                    onPillTap = tapped(::onPillTap),
                    onCancel = tapped(::onCancel),
                    onRetry = tapped(::onRetry),
                    onRemoveShot = tapped(::onRemoveShot),
                    onOpenSettings = tapped(::onOpenSettings),
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
        const val CLOUD_FADE_MS = 90L
        const val THINKING_CANCEL_MS = 3_000L
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
