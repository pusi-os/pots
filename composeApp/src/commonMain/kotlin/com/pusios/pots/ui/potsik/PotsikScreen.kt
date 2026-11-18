package com.pusios.pots.ui.potsik

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Flow 3 — «Экран»: one tap, one frame of the screen, one question about it.
 *
 * The platform half is a `MediaProjection` session ([PotsikScreenProjector]);
 * everything that decides *when* the session is asked for a frame lives here,
 * so the awkward parts — consent is granted per session, the token is spent
 * once, the system can take the projection back at any moment — are decided by
 * a state machine that runs in a test with a fake projector instead of on a
 * phone nobody has.
 */

/** One frame of the screen: what the pill shows and what the chat gets. */
data class PotsikShot(val width: Int, val height: Int, val png: ByteArray)

/** The pill's thumbnail and its caption, already turned into what Compose draws. */
data class PotsikShotUi(val preview: ImageBitmap?, val label: String)

/** The multiplication sign, not the letter: «Скриншот 1080×2400». */
fun potsikScreenshotSizeLabel(width: Int, height: Int): String = "$width×$height"

/** Bytes of one RGBA pixel — the format the reader is created with. */
private const val RGBA_PIXEL_BYTES = 4

/**
 * Copies an `ImageReader` RGBA plane into a tightly packed buffer.
 *
 * The plane the platform hands back is **not** `width * 4` bytes per row: the
 * hardware aligns rows to `rowStride`, which is usually wider than the picture.
 * Reading the buffer as one continuous block — the obvious implementation —
 * shears the image diagonally and tints it, which is the classic "the
 * screenshot is green noise" bug. So every row is copied on its own: `width`
 * pixels, [pixelStride] bytes each, starting at `y * rowStride`.
 *
 * [pixelStride] is 4 for the `RGBA_8888` reader this app creates. It stays a
 * parameter because the copy has to walk the row by the stride the platform
 * reported, and a plane packed differently should fail in a test rather than
 * produce a picture nobody can explain.
 *
 * The result is `width * 4 * height` bytes, ready for
 * `Bitmap.copyPixelsFromBuffer`.
 */
fun potsikRepackRgba(
    source: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): ByteArray {
    require(width > 0 && height > 0) { "an empty frame is not a screenshot: $width×$height" }
    require(pixelStride >= RGBA_PIXEL_BYTES) {
        "an RGBA plane cannot pack a pixel into $pixelStride bytes"
    }
    require(rowStride >= width * pixelStride) {
        "a row of $width pixels at $pixelStride bytes does not fit into $rowStride"
    }
    require(source.size >= (height - 1) * rowStride + width * pixelStride) {
        "the plane is ${source.size} bytes, $width×$height at row stride $rowStride needs more"
    }

    val rowBytes = width * RGBA_PIXEL_BYTES
    val out = ByteArray(rowBytes * height)
    // The common case: the platform aligned nothing and the plane is already
    // tightly packed. Copying it whole is the same result, without the walk.
    if (rowStride == width * pixelStride && pixelStride == RGBA_PIXEL_BYTES) {
        source.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = out.size)
        return out
    }
    for (y in 0 until height) {
        var src = y * rowStride
        var dst = y * rowBytes
        for (x in 0 until width) {
            out[dst] = source[src]
            out[dst + 1] = source[src + 1]
            out[dst + 2] = source[src + 2]
            out[dst + 3] = source[src + 3]
            src += pixelStride
            dst += RGBA_PIXEL_BYTES
        }
    }
    return out
}

/** Android 14 (API 34) is where «the whole display» can be asked for explicitly. */
const val POTSIK_SCREEN_WHOLE_DISPLAY_SDK = 34

/** What the consent dialog should offer. */
enum class PotsikScreenCaptureRequest {
    /** `createConfigForDefaultDisplay()`: the whole screen, not one app. */
    WholeDisplay,

    /** The platform's default; on Android 13 and below there is nothing else to ask for. */
    UserChoice,
}

/**
 * Which consent intent to build for this OS version.
 *
 * Android 14+ offers "a single app" by default, which is the wrong thing to
 * offer for "what is on my screen" — so the whole display is asked for
 * explicitly. The version check lives here, inside the decision, and not
 * around the whole feature: on 13 and below the plain intent is what the
 * platform has, and the screenshot works the same.
 */
fun potsikScreenCaptureRequest(sdkInt: Int): PotsikScreenCaptureRequest = if (sdkInt >= POTSIK_SCREEN_WHOLE_DISPLAY_SDK) {
    PotsikScreenCaptureRequest.WholeDisplay
} else {
    PotsikScreenCaptureRequest.UserChoice
}

/** Where the screen mode is. */
enum class PotsikScreenPhase {
    /** No screen mode: no session, no frame, nothing to show. */
    Off,

    /** The system consent dialog is open; nothing is captured until it answers. */
    AwaitingConsent,

    /** A frame is in hand, waiting for its question. */
    Shot,

    /** The mode could not deliver: refused consent, a revoked session, an empty frame. */
    Failed,
}

/** What the screen mode shows, and why. */
data class PotsikScreenState(
    val phase: PotsikScreenPhase = PotsikScreenPhase.Off,
    val shot: PotsikShot? = null,
    val trouble: PotsikCloudTrouble? = null,
)

/** What a tap on «Экран» turned out to need. */
sealed interface PotsikScreenStep {
    /** No session in this mode yet: the system dialog has to be shown. */
    data object AskConsent : PotsikScreenStep

    /** The session already lives: the frame was taken without asking again. */
    data object Captured : PotsikScreenStep
}

/**
 * The platform's half of the screen mode.
 *
 * A session is created once per mode — the consent token is spent the moment
 * the virtual display is created, so a session that is kept alive is the only
 * way to take a second screenshot without a second dialog. [stop] spends what
 * is left: the next entry asks again.
 */
interface PotsikScreenProjector {
    /** True while a consent-granted session lives; a stopped one never comes back. */
    val sessionActive: Boolean

    /** One frame, or null when the mirror had nothing to give. */
    suspend fun capture(): PotsikShot?

    /** Ends the session: the projection is released and the token is spent. */
    fun stop()
}

/**
 * The screen mode: consent once, a frame per tap, and an honest way out.
 *
 * The rules, and where they come from:
 *
 * - no session → ask for consent ([PotsikScreenStep.AskConsent]); the frame is
 *   taken after the dialog says yes ([onConsentGranted]);
 * - a session → a frame at once, no dialog: a dialog on every tap is a feature
 *   nobody uses;
 * - a refusal, a revoked projection or an empty frame → [PotsikScreenPhase.Failed]
 *   with a reason, and the mode stays where it is: the cloud does not close,
 *   the draft of the question is not touched;
 * - [exit] releases the session, and the next entry asks again — the token is
 *   one-shot, and a session reused after a stop throws `SecurityException`.
 */
class PotsikScreenMode(private val projector: PotsikScreenProjector) {

    private val _state = MutableStateFlow(PotsikScreenState())
    val state: StateFlow<PotsikScreenState> = _state.asStateFlow()

    /** A tap on «Экран»: ask for consent, or take a frame from the session that already lives. */
    suspend fun tap(): PotsikScreenStep {
        if (!projector.sessionActive) {
            _state.value = PotsikScreenState(phase = PotsikScreenPhase.AwaitingConsent)
            return PotsikScreenStep.AskConsent
        }
        capture()
        return PotsikScreenStep.Captured
    }

    /** The dialog said yes and the session is up: the first frame. */
    suspend fun onConsentGranted() {
        capture()
    }

    /** The dialog said no, or was dismissed: nothing was captured, and nothing is retried by itself. */
    fun onConsentDenied() {
        _state.value = PotsikScreenState(
            phase = PotsikScreenPhase.Failed,
            trouble = PotsikCloudTrouble.ScreenConsentDenied,
        )
    }

    /** The session never came up: no projection, no reader, no display. */
    fun onSessionFailed() {
        _state.value = PotsikScreenState(
            phase = PotsikScreenPhase.Failed,
            trouble = PotsikCloudTrouble.ScreenCaptureFailed,
        )
    }

    /** The system stopped the projection under us: the lock screen, the status-bar chip. */
    fun onRevoked() {
        _state.value = PotsikScreenState(
            phase = PotsikScreenPhase.Failed,
            trouble = PotsikCloudTrouble.ScreenConsentRevoked,
        )
    }

    /** [×] on the pill: the frame goes, the session stays — the next tap takes another one. */
    fun clearShot() {
        _state.value = PotsikScreenState()
    }

    /** Leaving the mode: the session is released and the next entry asks for consent again. */
    fun exit() {
        projector.stop()
        _state.value = PotsikScreenState()
    }

    private suspend fun capture() {
        val shot = runCatching { projector.capture() }.getOrNull()
        _state.value = if (shot != null) {
            PotsikScreenState(phase = PotsikScreenPhase.Shot, shot = shot)
        } else {
            PotsikScreenState(
                phase = PotsikScreenPhase.Failed,
                trouble = PotsikCloudTrouble.ScreenCaptureFailed,
            )
        }
    }
}

/**
 * The frame that has been taken and is waiting for its question.
 *
 * Process-wide for the same reason as [PotsikCloudDraft]: the field that asks
 * the question is another activity, and the picture has to survive the handover
 * from the cloud's window to it.
 */
object PotsikShotHolder {

    private val _shot = MutableStateFlow<PotsikShot?>(null)

    val shot: StateFlow<PotsikShot?> = _shot.asStateFlow()

    fun set(value: PotsikShot?) {
        _shot.value = value
    }

    fun clear() {
        _shot.value = null
    }
}
