package com.pusios.pots.ui.potsik

import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/*
 * The «облачко» — the capsule that opens on a double tap on Потсик while he
 * stands on somebody else's screen.
 *
 * Everything here is pure: the measurements, where the capsule goes, what the
 * pill says and how a double tap is counted. The window that draws it lives in
 * androidMain (`PotsikCloudWindow`), and the reason the arithmetic is here and
 * not there is the same as for [clampWindowOffset]: it can be tested without a
 * device, and a placement rule that is only checked by eye is a placement rule
 * that ends up half off the screen.
 */

/** The capsule's width: three 48 dp buttons, two 8 dp gaps, two 8 dp paddings. */
const val POTSIK_CLOUD_WIDTH_DP = 176f

/** The capsule's height: a 48 dp row inside an 8 dp padding. */
const val POTSIK_CLOUD_HEIGHT_DP = 64f

/** The tail that points at his head, drawn under the capsule. */
const val POTSIK_CLOUD_TAIL_WIDTH_DP = 12f
const val POTSIK_CLOUD_TAIL_HEIGHT_DP = 8f

/** The window is exactly the capsule plus the tail — no empty area to eat taps. */
const val POTSIK_CLOUD_WINDOW_HEIGHT_DP = POTSIK_CLOUD_HEIGHT_DP + POTSIK_CLOUD_TAIL_HEIGHT_DP

/**
 * The line above the capsule when the device has no recognizer: two lines of
 * `labelSmall` (16 dp each — «На этом устройстве нет распознавания речи» does
 * not fit on one at 176 dp wide) plus the 4 dp gap above the capsule.
 */
const val POTSIK_CLOUD_NOTICE_HEIGHT_DP = 36f

/** One action: the accessibility minimum, and what the layout is built from. */
const val POTSIK_CLOUD_BUTTON_DP = 48f

/** The gap between two buttons and the padding inside the capsule. */
const val POTSIK_CLOUD_GAP_DP = 8f

/** The capsule's corner radius, from the Pots canon (22/20/16/14/12/11). */
const val POTSIK_CLOUD_RADIUS_DP = 22f

/** How far the capsule keeps from the edges of the screen. */
const val POTSIK_CLOUD_EDGE_MARGIN_DP = 8f

/** The capsule's bottom sits this far above the top of his head. */
const val POTSIK_CLOUD_GAP_ABOVE_HEAD_DP = 8f

/** The gap between the capsule and his body when it opens to the side. */
const val POTSIK_CLOUD_SIDE_GAP_DP = 8f

/**
 * What the pill shows, and therefore what the capsule is: the menu, one of the
 * three voice states, the text field, or the reason something did not work.
 *
 * [Menu] and [TextInput] are static — the character's frame clock must stay
 * parked while they are on screen (the overlay's battery invariant); only the
 * three voice states ask him for frames. The text field's own window is not
 * parked: it holds a field with a blinking cursor.
 */
enum class PotsikCloudPhase {
    Menu,
    Listening,
    Thinking,
    Speaking,
    TextInput,
    Error,

    /** «Экран» was tapped and the system consent dialog is open: nothing is captured yet. */
    AwaitingConsent,

    /** A frame of the screen is in hand and is waiting for its question. */
    Shot,
}

/** Why the pill went into [PotsikCloudPhase.Error]. */
enum class PotsikCloudTrouble {
    /** No recognizer on this device: the voice button is dead, not the cloud. */
    NoRecognizer,

    /** Nothing was heard: not an error, the loop simply listens again. */
    NoMatch,

    /** The recognizer never called back within its watchdog. */
    ListenTimeout,

    /** The request could not be sent: the transcript is kept, not thrown away. */
    NoNetwork,

    /** The microphone permission was refused. */
    PermissionDenied,

    /** The screen went off — a background microphone is not allowed. */
    ScreenOff,

    /** The voice loop owns the microphone: the text field's speech waits for it. */
    MicBusy,

    /** No chat is loaded to answer: the app has to be opened once. */
    NoChat,

    /** The system consent dialog was refused, or dismissed by a tap outside it. */
    ScreenConsentDenied,

    /** The projection was taken away after it was granted: the lock screen, the status-bar chip. */
    ScreenConsentRevoked,

    /** The session never came up, or the mirror had no frame to give. */
    ScreenCaptureFailed,
}

/**
 * True for the troubles the screen mode owns.
 *
 * [Повторить] has to mean two different things: "listen again" for the voice
 * loop and "ask for the screen again" for the screenshot. This is how the
 * retry tells them apart without a second error enum.
 */
fun PotsikCloudTrouble?.isScreenTrouble(): Boolean = this == PotsikCloudTrouble.ScreenConsentDenied ||
    this == PotsikCloudTrouble.ScreenConsentRevoked ||
    this == PotsikCloudTrouble.ScreenCaptureFailed

/**
 * What the cloud is doing right now.
 *
 * [transcript] and [reply] are what the pill shows; [unsent] marks a question
 * that never left the device, so the pill can offer [Повторить] instead of
 * pretending it was sent (the rule from the spec: nothing is lost).
 */
data class PotsikCloudState(
    val phase: PotsikCloudPhase = PotsikCloudPhase.Menu,
    val transcript: String = "",
    val reply: String = "",
    val trouble: PotsikCloudTrouble? = null,
    /** The [Отмена] affordance of the thinking pill appears after three seconds. */
    val cancelOffered: Boolean = false,
    /** True when the text that failed to send is still there to be sent again. */
    val unsent: Boolean = false,
    /**
     * True while the text of the screen — taken at the assist invocation — is
     * still attached to the next question. The pill says so, because a context
     * nobody can see is a context nobody agreed to.
     */
    val assistContext: Boolean = false,
)

/** The voice button is dead without a recognizer — and says so, rather than failing at the tap. */
fun potsikCloudVoiceEnabled(recognizerAvailable: Boolean): Boolean = recognizerAvailable

/** An empty field sends nothing. */
fun potsikCloudSendEnabled(text: String): Boolean = text.isNotBlank()

/** The character's own look for each cloud state: the phases the app already has. */
data class PotsikPetLook(
    val phase: PotsikPhase,
    val toolName: String?,
    /** Tips his head as if listening — the idle roll's own lean. */
    val leaning: Boolean,
)

/**
 * What the character does while the cloud is open.
 *
 * The mapping is the app's own — the same phases the chat hands the engine —
 * because a second, cloud-only set of poses would be a second character. The
 * menu and the text field leave him breathing; thinking is the work phase the
 * chat uses for a running request; speaking is the speaking phase.
 */
fun potsikPetLook(cloud: PotsikCloudPhase?): PotsikPetLook = when (cloud) {
    null -> PotsikPetLook(PotsikPhase.IDLE, null, leaning = false)

    PotsikCloudPhase.Menu,
    PotsikCloudPhase.TextInput,
    // Waiting for the consent dialog is the same "nothing is happening yet" as
    // the menu: he breathes, and the pill is where the waiting is written.
    PotsikCloudPhase.AwaitingConsent,
    PotsikCloudPhase.Shot,
    -> PotsikPetLook(PotsikPhase.IDLE, null, leaning = false)

    PotsikCloudPhase.Listening -> PotsikPetLook(PotsikPhase.IDLE, null, leaning = true)

    PotsikCloudPhase.Thinking -> PotsikPetLook(PotsikPhase.WORKING, null, leaning = false)

    PotsikCloudPhase.Speaking -> PotsikPetLook(PotsikPhase.SPEAKING, null, leaning = false)

    PotsikCloudPhase.Error -> PotsikPetLook(PotsikPhase.ERROR, null, leaning = false)
}

/** Which side of the character the capsule ended up on. */
enum class PotsikCloudSide { Above, Right, Left }

/** Where the capsule's window goes, and why. */
data class PotsikCloudPlacement(val x: Int, val y: Int, val side: PotsikCloudSide)

/**
 * Where the capsule goes: over his head, or beside him when the head is too
 * close to the top edge to fit.
 *
 * The rules, in the order they are asked:
 *
 * 1. above — centred on his head, its bottom [POTSIK_CLOUD_GAP_ABOVE_HEAD_DP]
 *    above the top of it;
 * 2. not enough room above → to the right, if the capsule plus its gap fits
 *    between him and the right edge;
 * 3. otherwise to the left, if it fits there;
 * 4. otherwise back above, clamped: there is nowhere else to go, and half a
 *    capsule on the screen beats a capsule off it.
 *
 * Never below him: from below the capsule covers his body and his own speech
 * bubble, which is the one thing he needs to be seen with.
 *
 * The final position is clamped on both axes with [POTSIK_CLOUD_EDGE_MARGIN_DP]
 * of room at every edge — he can be dragged past the edge of the screen
 * (`FLAG_LAYOUT_NO_LIMITS`), the capsule cannot.
 *
 * All arguments are pixels; [density] only turns the dp margins into them.
 */
fun potsikCloudPlacement(
    petX: Int,
    petY: Int,
    petWidth: Int,
    petHeight: Int,
    characterPx: Int,
    screenWidth: Int,
    screenHeight: Int,
    density: Float,
    cloudWidthPx: Int,
    cloudHeightPx: Int,
): PotsikCloudPlacement {
    val margin = (POTSIK_CLOUD_EDGE_MARGIN_DP * density).roundToInt()
    val aboveGap = (POTSIK_CLOUD_GAP_ABOVE_HEAD_DP * density).roundToInt()
    val sideGap = (POTSIK_CLOUD_SIDE_GAP_DP * density).roundToInt()

    // His head is the top of the character inside the window: the band above
    // it belongs to the speech bubble.
    val headTop = petY + (petHeight - characterPx)
    val headCentreX = petX + petWidth / 2

    val aboveX = headCentreX - cloudWidthPx / 2
    val aboveY = headTop - aboveGap - cloudHeightPx
    if (aboveY >= margin) {
        return clampCloud(aboveX, aboveY, cloudWidthPx, cloudHeightPx, screenWidth, screenHeight, margin, PotsikCloudSide.Above)
    }

    // Beside him: the capsule's middle lines up with the middle of his head.
    val sideY = headTop + characterPx / 2 - cloudHeightPx / 2
    val rightX = petX + petWidth + sideGap
    if (rightX + cloudWidthPx <= screenWidth - margin) {
        return clampCloud(rightX, sideY, cloudWidthPx, cloudHeightPx, screenWidth, screenHeight, margin, PotsikCloudSide.Right)
    }
    val leftX = petX - sideGap - cloudWidthPx
    if (leftX >= margin) {
        return clampCloud(leftX, sideY, cloudWidthPx, cloudHeightPx, screenWidth, screenHeight, margin, PotsikCloudSide.Left)
    }

    return clampCloud(aboveX, aboveY, cloudWidthPx, cloudHeightPx, screenWidth, screenHeight, margin, PotsikCloudSide.Above)
}

/**
 * Keeps the capsule on the screen with [margin] of room at every edge.
 *
 * The clamping itself is [clampWindowOffset] — the same rule the character's
 * window obeys — applied to the screen shrunk by the margin, so there is one
 * implementation of "stay inside" in the project and not two.
 */
private fun clampCloud(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    screenWidth: Int,
    screenHeight: Int,
    margin: Int,
    side: PotsikCloudSide,
): PotsikCloudPlacement {
    val inner = clampWindowOffset(
        x = x - margin,
        y = y - margin,
        windowWidth = width,
        windowHeight = height,
        screenWidth = (screenWidth - 2 * margin).coerceAtLeast(1),
        screenHeight = (screenHeight - 2 * margin).coerceAtLeast(1),
    )
    return PotsikCloudPlacement(inner.x + margin, inner.y + margin, side)
}

/** The size the capsule's window takes, in dp. */
data class PotsikCloudSize(val widthDp: Float, val heightDp: Float)

/**
 * How big the cloud's window is for a state.
 *
 * The menu is the fixed three-slot capsule; the pill is one row of text with
 * the same tail, so it is shorter and wider. The window is resized between the
 * two without animating the size — an overlay window that animates its own
 * bounds stutters, so the content crossfades inside a window that has already
 * jumped ([POTSIK_CLOUD_PILL_WIDTH_DP] wide).
 *
 * The text field is a window of its own size like the rest, and the one phase
 * that takes input focus; an overlay is drawn below the keyboard, so the field
 * is kept in the top half of the screen ([potsikCloudInputTop]) instead of
 * being lifted by insets it never receives.
 *
 * [voiceAvailable] is the one thing besides the phase that changes the height:
 * without a recognizer the menu carries a line of explanation above the capsule,
 * and the window has to grow by [POTSIK_CLOUD_NOTICE_HEIGHT_DP] or the line is
 * clipped by the window's own bounds.
 */
fun potsikCloudWindowSize(state: PotsikCloudState, voiceAvailable: Boolean = true): PotsikCloudSize {
    val size = baseCloudSize(state, voiceAvailable)
    // The assist note sits above the capsule like the no-recognizer line: the
    // window has to grow by it, or the line is clipped by the window's own
    // bounds and the user never learns the screen was read.
    if (!potsikCloudShowsAssistNote(state)) return size
    return PotsikCloudSize(size.widthDp, size.heightDp + POTSIK_CLOUD_NOTICE_HEIGHT_DP)
}

/**
 * True when the cloud carries the line about the screen text taken at the
 * assist invocation.
 *
 * Only the phases that draw the capsule plus a line above it: the text field
 * and the shot pill are windows of their own size, and the consent phase is the
 * system dialog's moment, not the context's.
 */
fun potsikCloudShowsAssistNote(state: PotsikCloudState): Boolean = state.assistContext && state.phase in ASSIST_NOTE_PHASES

private val ASSIST_NOTE_PHASES = setOf(
    PotsikCloudPhase.Menu,
    PotsikCloudPhase.Listening,
    PotsikCloudPhase.Thinking,
    PotsikCloudPhase.Speaking,
    PotsikCloudPhase.Error,
)

private fun baseCloudSize(state: PotsikCloudState, voiceAvailable: Boolean): PotsikCloudSize = when (state.phase) {
    PotsikCloudPhase.Menu ->
        if (voiceAvailable) {
            PotsikCloudSize(POTSIK_CLOUD_WIDTH_DP, POTSIK_CLOUD_WINDOW_HEIGHT_DP)
        } else {
            PotsikCloudSize(POTSIK_CLOUD_WIDTH_DP, POTSIK_CLOUD_WINDOW_HEIGHT_DP + POTSIK_CLOUD_NOTICE_HEIGHT_DP)
        }

    PotsikCloudPhase.Listening,
    PotsikCloudPhase.Thinking,
    PotsikCloudPhase.Speaking,
    PotsikCloudPhase.Error,
    PotsikCloudPhase.AwaitingConsent,
    -> PotsikCloudSize(POTSIK_CLOUD_PILL_WIDTH_DP, POTSIK_CLOUD_PILL_HEIGHT_DP + POTSIK_CLOUD_TAIL_HEIGHT_DP)

    // The shot pill holds a 64 dp thumbnail, so it is taller and wider than a
    // pill of text: the caption «Скриншот 1080×2400» has to fit beside it.
    PotsikCloudPhase.Shot -> PotsikCloudSize(POTSIK_CLOUD_SHOT_WIDTH_DP, POTSIK_CLOUD_SHOT_HEIGHT_DP + POTSIK_CLOUD_TAIL_HEIGHT_DP)

    // The field of Flow 2: wider than the menu (a sentence has to be readable
    // while it is typed) and taller, because an OutlinedTextField needs 56 dp of
    // its own inside the capsule's padding.
    PotsikCloudPhase.TextInput -> PotsikCloudSize(POTSIK_CLOUD_INPUT_WIDTH_DP, POTSIK_CLOUD_INPUT_HEIGHT_DP)
}

/**
 * The text field's width: the shot pill's 288 dp — the widest the capsule gets
 * — so a typed question stays readable and the send button keeps its 48 dp.
 */
const val POTSIK_CLOUD_INPUT_WIDTH_DP = 288f

/** The text field's window: a 56 dp field in an 8 dp padding, plus the tail. */
const val POTSIK_CLOUD_INPUT_HEIGHT_DP = 80f

/**
 * How much of the screen's height the keyboard is assumed to take.
 *
 * An overlay window is always drawn *below* the IME, so `adjustResize` cannot
 * save a field that sits under the keyboard: the only lever is where the window
 * is. The top half of the screen is above every stock keyboard on a phone, and
 * the capsule normally lives there anyway — it is anchored above his head.
 */
const val POTSIK_CLOUD_KEYBOARD_TOP_FRACTION = 0.5f

/**
 * Where the text field's window may start: high enough for its bottom to stay
 * above the keyboard.
 *
 * Same reason the placement itself lives here rather than in the overlay — a
 * rule that is only checked by eye is a rule that ends up under the keyboard on
 * the one device that matters.
 */
fun potsikCloudInputTop(y: Int, windowHeightPx: Int, screenHeightPx: Int): Int {
    val ceiling = (screenHeightPx * POTSIK_CLOUD_KEYBOARD_TOP_FRACTION).roundToInt() - windowHeightPx
    return y.coerceAtMost(ceiling).coerceAtLeast(0)
}

/** The pill's fixed width: wide enough for two lines of an answer, not the whole screen. */
const val POTSIK_CLOUD_PILL_WIDTH_DP = 240f

/** The pill's height: one 48 dp row, the same as the capsule's. */
const val POTSIK_CLOUD_PILL_HEIGHT_DP = 48f

/** The shot pill's height: a 64 dp thumbnail in an 8 dp padding. */
const val POTSIK_CLOUD_SHOT_HEIGHT_DP = 80f

/** The shot pill's width: the thumbnail, its gap, and a caption that fits beside them. */
const val POTSIK_CLOUD_SHOT_WIDTH_DP = 288f

/** The thumbnail's side, both on the pill and in the question panel. */
const val POTSIK_CLOUD_THUMBNAIL_DP = 64f

/** The thumbnail's corner radius, from the Pots canon. */
const val POTSIK_CLOUD_THUMBNAIL_RADIUS_DP = 12f

/**
 * The text that has not been sent yet.
 *
 * Process-wide on purpose: the text field is an activity that the user closes
 * with back or by tapping away, and the spec is explicit that the draft is not
 * thrown away — opening the field again restores what was typed.
 */
object PotsikCloudDraft {

    private val _text = MutableStateFlow("")

    val text: StateFlow<String> = _text.asStateFlow()

    /** The question the field last sent; [clearIfAnswerTo] consumes it. */
    private var sentQuestion: String? = null

    fun set(value: String) {
        _text.value = value
    }

    fun clear() {
        _text.value = ""
    }

    /** Remembers [question] as sent from the field, so only its own answer clears the draft. */
    fun markSent(question: String) {
        sentQuestion = question
    }

    /** Forgets the marker when the question is abandoned (close, cancel). */
    fun forgetSent() {
        sentQuestion = null
    }

    /**
     * Clears the draft only when [question] is the one the field itself sent.
     *
     * Voice questions and screenshot questions travel through the same asker,
     * and their answers must not touch what the user typed — the draft is kept
     * on purpose when the field is closed. Returns true when it cleared.
     */
    fun clearIfAnswerTo(question: String): Boolean {
        if (sentQuestion == null || sentQuestion != question) return false
        sentQuestion = null
        clear()
        return true
    }
}

/**
 * True while the cloud's voice loop owns the microphone.
 *
 * The microphone has exactly one owner at a time — the Pots invariant — and
 * the two surfaces that can take it are the voice pill and the text field's
 * «Озвучить». This flag is how the second one knows to say
 * «Сначала остановите голосовой режим» instead of talking over the first.
 */
object PotsikCloudVoice {

    private val _active = MutableStateFlow(false)

    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(value: Boolean) {
        _active.value = value
    }
}

/** What a tap on the character turned out to be. */
enum class PotsikTap { Single, Double }

/**
 * Counts taps on the character by hand.
 *
 * Compose's `onDoubleTap` cannot be used here: with it, `detectTapGestures`
 * holds every single tap for the whole double-tap window before answering, and
 * petting him — the behaviour that already exists — would answer with up to
 * 300 ms of lag. So the single tap is answered at once, and the second tap
 * inside [doubleTapTimeoutMs] is what opens the cloud.
 *
 * The window comes from the platform (`ViewConfiguration.getDoubleTapTimeout()`),
 * not from a constant of our own.
 */
class PotsikTapCounter(private val doubleTapTimeoutMs: Long) {

    private var lastTapAt: Long? = null

    fun onTap(now: Long): PotsikTap {
        val previous = lastTapAt
        if (previous != null && now - previous in 0..doubleTapTimeoutMs) {
            // The pair is spent: a third tap starts counting from scratch,
            // otherwise three quick taps would be two opens.
            lastTapAt = null
            return PotsikTap.Double
        }
        lastTapAt = now
        return PotsikTap.Single
    }
}
