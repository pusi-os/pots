package com.pusios.pots.ui.potsik

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.time.TimeSource

/**
 * Where Потсик stands.
 *
 * The position is normalized so it survives everything the screen does to it:
 * a rotation, a resized desktop window, a phone going from portrait to
 * landscape. [x] is the character's horizontal centre as a fraction of the
 * screen's width (0 = left edge, 1 = right edge) and [y] is the line his feet
 * stand on as a fraction of the screen's height (0 = top edge, 1 = bottom edge).
 *
 * Feet rather than centre, because that is the point a character is placed by:
 * a spot on the floor. It also makes the "he grows" case read correctly — a
 * bigger Потсик grows upwards, out of the floor, instead of sinking into it.
 */
@Immutable
data class PotsikPlacement(
    val x: Float = DEFAULT_X,
    val y: Float = DEFAULT_Y,
    val scale: Float = 1f,
) {
    companion object {
        /** Dead centre. */
        const val DEFAULT_X = 0.5f

        /** A little above the middle, so he stands on something rather than floating. */
        const val DEFAULT_Y = 0.62f

        /**
         * Where he stands when the layout has no place for him.
         *
         * The sections outside the chat — the mini-app runner, the build
         * screen, the terminal — have no slot of their own, so he stands in
         * the corner rather than in the middle of somebody else's app.
         */
        const val FALLBACK_X = 0.86f

        /** Near the bottom, so he does not cover what is on the screen. */
        const val FALLBACK_Y = 0.92f
    }
}

/**
 * A point on the screen, in the same normalized coordinates [PotsikPlacement] uses.
 */
@Immutable
data class PotsikPoint(val x: Float, val y: Float)

/**
 * Where one of the character's places in the layout is.
 *
 * The chat screen has three of them: the empty screen, the tile that shows
 * while a request is running, and the footer under the last answer. Each
 * measures itself and reports where it ended up; the stage picks the one that
 * applies and puts the character there.
 *
 * The numbers are in window pixels, because that is what a layout reports.
 * The area the character moves in does not start at the window's origin, so
 * the stage moves them across when it works out where he stands.
 */
@Immutable
data class PotsikAnchorSpec(
    /** The horizontal centre of the character's box. */
    val centerX: Float,

    /** The line his feet stand on. */
    val feetY: Float,

    /** How tall he stands here — the size of the box he is drawn into. */
    val sizePx: Float,
)

/**
 * The places the character can be in the chat layout.
 *
 * The order is the priority: the tile that shows while a request is running
 * wins over the footer under the last answer, which wins over the empty
 * screen. That is the order the character appears in — he leaves the footer
 * when the next request starts and comes back when it is done.
 */
enum class PotsikAnchorKind(val priority: Int) {
    /** The empty screen, before anything has been said. */
    EMPTY(0),

    /** The footer under the last answer. */
    FOOTER(1),

    /** The tile shown while a request is running. */
    WORKING(2),
}

/**
 * The character's live state on the chat screen: where it stands, how big it is
 * and what it is doing.
 *
 * One instance for the whole app (a Koin singleton), so the agent's tool, the
 * chat screen and the character's own behaviour all talk about the same
 * character: the tool writes, the screen reads. Nothing here touches Compose
 * — it is plain state, so it can be driven from a tool, from a gesture or
 * from a test.
 *
 * The bounds are enforced here rather than at the call site, because the call
 * sites are three different ones (a drag, the agent's tool and the screen's
 * own defaults) and they all have to obey the same rule: Потсик is never half
 * off the screen. [clampPlacement] is the single place that decides what "on
 * the screen" means.
 */
class PotsikStage {

    private val _area = MutableStateFlow(IntSize.Zero)
    private val _areaOrigin = MutableStateFlow(Offset.Zero)
    private val _baseCharacterSize = MutableStateFlow(0f)
    private val _sizePreference = MutableStateFlow(1f)
    private val _anchors = MutableStateFlow<Map<PotsikAnchorKind, PotsikAnchorSpec>>(emptyMap())
    private val _pinnedPosition = MutableStateFlow<PotsikPoint?>(null)
    private val _isPinned = MutableStateFlow(false)
    private val _userScale = MutableStateFlow(1f)
    private val _placement = MutableStateFlow(PotsikPlacement())
    private val _settings = MutableStateFlow(PotsikSettings())
    private val _topInset = MutableStateFlow(0f)

    /**
     * The character's behaviour — the firmware's engine.
     *
     * It lives here rather than in the composable because the agent's tool
     * plays poses into it and the chat screen reads the result, and both have
     * to be talking about the same character.
     */
    internal var engine: PotsikEngine = PotsikEngine(PotsikSettings()) { false }
    private var engineIsRussian: Boolean = false
        private set

    /** The size of the area the character moves in, in pixels. */
    val area: StateFlow<IntSize> = _area.asStateFlow()

    /**
     * Where the area starts, in window coordinates.
     *
     * The area is not the whole window — the screen's own bars take a bite out
     * of it — so a place in the layout, which reports itself in window
     * coordinates, has to be moved back into the area's own.
     */
    val areaOrigin: StateFlow<Offset> = _areaOrigin.asStateFlow()

    /** Where the character actually is, after the bounds have been applied. */
    val placement: StateFlow<PotsikPlacement> = _placement.asStateFlow()

    /** The character's normal size, in pixels, before any scaling. */
    val baseCharacterSize: StateFlow<Float> = _baseCharacterSize.asStateFlow()

    /** The user's own size setting for the character. */
    val sizePreference: StateFlow<Float> = _sizePreference.asStateFlow()

    /** The places in the layout where the character belongs. */
    val anchors: StateFlow<Map<PotsikAnchorKind, PotsikAnchorSpec>> = _anchors.asStateFlow()

    /** The spot the user or the agent put the character at, if any. */
    val pinnedPosition: StateFlow<PotsikPoint?> = _pinnedPosition.asStateFlow()

    /**
     * How big the character is, relative to the size the chat screen gives him
     * for the current phase. 1 is that size.
     *
     * The user and the agent both move this one number; the screen's own size
     * preference is folded into the character size before it gets here.
     */
    val userScale: StateFlow<Float> = _userScale.asStateFlow()

    /**
     * True when the user or the agent has put the character somewhere of their
     * own choosing, which pins him there instead of letting the chat layout
     * decide.
     */
    val isPinned: StateFlow<Boolean> = _isPinned.asStateFlow()

    /** The settings that change how he behaves. */
    val settings: StateFlow<PotsikSettings> = _settings.asStateFlow()

    /** Pixels from the top of the area that the character may not rise above. */
    val topInset: StateFlow<Float> = _topInset.asStateFlow()

    /**
     * How big the character is drawn, in pixels.
     *
     * It is the base size the layout gives him times the factor the
     * placement holds, so the drawing, the box the finger grabs and the
     * bounds are all looking at the same number.
     */
    val characterSizePx: Float
        get() = _baseCharacterSize.value * _placement.value.scale

    /**
     * How big he is drawn, against the size the chat layout would give him.
     *
     * One is that size. The user's own size settings are part of it, so the
     * number the agent reads back is the number it asked for.
     */
    val scaleRelativeToLayout: Float
        get() {
            val layout = defaultPlacement()?.scale ?: 1f
            return if (layout > 0f) _placement.value.scale / layout else _placement.value.scale
        }

    /**
     * Whether he may be moved about at all.
     *
     * The system mode says the chat puts him where it wants, so nothing —
     * not the finger, not the agent's tool — may move him.
     */
    val canMove: Boolean
        get() = _settings.value.placementMode != PotsikPlacementMode.SYSTEM

    /** The screen reports the size of the area the character moves in. */
    fun setArea(size: IntSize) {
        if (_area.value == size) return
        _area.value = size
        recompute()
    }

    /** The screen reports where the area starts, in window coordinates. */
    fun setAreaOrigin(origin: Offset) {
        if (_areaOrigin.value == origin) return
        _areaOrigin.value = origin
        recompute()
    }

    /**
     * The screen reports the character's normal size, in pixels — the size he
     * is drawn at before the user's own size setting and before any scaling.
     */
    fun setBaseCharacterSize(px: Float) {
        if (_baseCharacterSize.value == px) return
        _baseCharacterSize.value = px
        recompute()
    }

    /**
     * The screen reports the user's size setting for the character.
     *
     * It multiplies the size he is drawn at everywhere, so a bigger Потсик is
     * bigger in the empty screen, in the working tile and in the footer alike
     * — and the bounds follow him.
     *
     * The screen reports how far down the character must stay. */
    fun setTopInset(px: Float) {
        if (_topInset.value == px) return
        _topInset.value = px
        recompute()
    }

    fun setSizePreference(factor: Float) {
        if (_sizePreference.value == factor) return
        _sizePreference.value = factor
        recompute()
    }

    /**
     * Called when the character is moved, so the app can remember where.
     *
     * It is only called for a move the user made — a drag or the agent's
     * tool — and only when the placement mode says a move is worth
     * remembering.
     */
    internal var onMoved: ((Float, Float) -> Unit)? = null

    /**
     * Called when the character is put back where the layout wants him, so
     * the app can forget the place he was in.
     */
    internal var onPlacementCleared: (() -> Unit)? = null

    /**
     * The settings screen reports the behaviour settings.
     *
     * The engine is rebuilt around them, because the tempo is baked into every
     * duration it keeps and the banter switch decides whether it talks at all.
     */
    fun setSettings(settings: PotsikSettings, isRussian: Boolean) {
        val wasPersistent = _settings.value.placementMode == PotsikPlacementMode.PERSISTENT
        val languageChanged = engineIsRussian != isRussian
        val changed = _settings.value != settings
        _settings.value = settings
        if (changed || languageChanged) {
            engine = PotsikEngine(settings) { isRussian }
            engineIsRussian = isRussian
        }
        // The system mode says the chat puts him where it wants, so a move
        // is forgotten as soon as the mode says so.
        if (settings.placementMode == PotsikPlacementMode.SYSTEM) {
            clearPinned()
            return
        }
        // Switching to the persistent mode is a promise that he stays where
        // he is put. The spot he stands in right now is that spot, so it is
        // written down — otherwise the promise would only be kept from the
        // next move onwards.
        if (settings.placementMode == PotsikPlacementMode.PERSISTENT && !wasPersistent) {
            val here = currentPoint()
            reportMoved(here.x, here.y)
        }
        recompute()
    }

    /**
     * Puts him back where the user left him.
     *
     * Null coordinates mean he was never moved, and the system mode means a
     * move is not kept at all.
     */
    fun restorePinned(x: Float?, y: Float?) {
        if (x == null || y == null) return
        // The system mode says the chat puts him where it wants. The session
        // mode says he stays where he was put until the app closes, and
        // nothing was put down to remember it, so there is nothing to
        // restore either.
        if (_settings.value.placementMode != PotsikPlacementMode.PERSISTENT) return
        _pinnedPosition.value = PotsikPoint(x, y)
        _isPinned.value = true
        recompute()
    }

    /** Forgets where he was put, so the layout decides again. */
    fun clearPinned() {
        if (_pinnedPosition.value == null && !_isPinned.value) return
        _pinnedPosition.value = null
        _isPinned.value = false
        recompute()
    }

    /**
     * A place in the layout where the character belongs, or null when that
     * place is not on the screen right now.
     */
    fun setAnchor(kind: PotsikAnchorKind, spec: PotsikAnchorSpec?) {
        val current = _anchors.value
        val updated = if (spec == null) current - kind else current + (kind to spec)
        if (updated == current) return
        _anchors.value = updated
        recompute()
    }

    /**
     * Moves the character to a spot, in the same normalized coordinates the
     * placement uses. The spot is clamped so he stays fully on the screen.
     */
    fun moveTo(x: Float, y: Float) {
        // The system mode says the chat decides where he stands, so a move
        // is not a move at all.
        if (_settings.value.placementMode == PotsikPlacementMode.SYSTEM) return
        val spot = boundedSpot(x, y)
        _pinnedPosition.value = spot
        _isPinned.value = true
        recompute()
        reportMoved(spot.x, spot.y)
    }

    /** Moves the character by an offset, in normalized coordinates. */
    fun moveBy(dx: Float, dy: Float) {
        if (_settings.value.placementMode == PotsikPlacementMode.SYSTEM) return
        val current = _pinnedPosition.value ?: currentPoint()
        val moved = boundedSpot(current.x + dx, current.y + dy)
        _pinnedPosition.value = moved
        _isPinned.value = true
        recompute()
        reportMoved(moved.x, moved.y)
    }

    /**
     * The spot, kept inside the screen the way the placement keeps him.
     *
     * The pinned spot is stored clamped, not just the drawn one: otherwise a
     * drag past the edge would leave him stuck outside the screen with no way
     * back until the next layout change.
     */
    private fun boundedSpot(x: Float, y: Float): PotsikPoint {
        val bounded = clampPlacement(
            requested = PotsikPlacement(x = x, y = y, scale = _placement.value.scale),
            areaWidth = _area.value.width.toFloat(),
            areaHeight = _area.value.height.toFloat(),
            baseCharacterSize = _baseCharacterSize.value,
            topInset = _topInset.value,
        )
        return PotsikPoint(bounded.x, bounded.y)
    }

    /**
     * Tells the app where he is, when a move is worth remembering.
     *
     * Only the persistent mode writes the position down: the session mode
     * keeps it in memory for as long as the app is open, and the system mode
     * does not keep it at all.
     *
     * A drag reports every frame, and the app writes the position down, so
     * only a move worth a pixel is reported: the last position is kept and
     * compared against the new one.
     */
    private fun reportMoved(x: Float, y: Float) {
        if (_settings.value.placementMode != PotsikPlacementMode.PERSISTENT) return
        val last = lastReported
        if (last != null && kotlin.math.abs(last.x - x) < MOVE_EPSILON && kotlin.math.abs(last.y - y) < MOVE_EPSILON) return
        lastReported = PotsikPoint(x, y)
        onMoved?.invoke(x, y)
    }

    /** The last position the app was told about. */
    private var lastReported: PotsikPoint? = null

    /**
     * Sets the character's size, relative to the size the chat layout would
     * give him right now. 1.0 is that size, 2.0 twice as big.
     */
    fun setScale(scale: Float) {
        // The number the agent sends is the size he should be drawn at,
        // against the size the chat layout would give him. The user's own
        // size settings are part of that comparison, so they are divided
        // out here rather than added on top of the answer.
        val others = sizeFactors()
        _userScale.value = if (others > 0f) scale / others else scale
        recompute()
    }

    /**
     * Makes the character bigger or smaller by a factor — a pinch, or a
     * second's worth of scrolling.
     */
    fun scaleBy(factor: Float) {
        if (factor <= 0f) return
        _userScale.value = (_userScale.value * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        recompute()
    }

    /**
     * Puts the character back where the chat layout wants him, at the size
     * the layout wants, dropping any move.
     */
    fun resetPlacement() {
        val had = _isPinned.value
        _pinnedPosition.value = null
        _isPinned.value = false
        _userScale.value = 1f
        lastReported = null
        recompute()
        // The place he was in is gone, so the app has to forget it too —
        // otherwise the next launch would put him back there.
        if (had && _settings.value.placementMode == PotsikPlacementMode.PERSISTENT) {
            onPlacementCleared?.invoke()
        }
    }

    /**
     * Plays an animation for [durationMs], overriding the one the phase would
     * pick. This is what the agent's tool calls.
     */
    fun playAnimation(action: PotsikAction, durationMs: Long = DEFAULT_ANIMATION_DURATION_MS) {
        engine.playAction(action, durationMs, currentTimeMs())
    }

    /**
     * The animation the agent asked for, or null when none is running.
     *
     * [nowMs] has to be the same clock [currentTimeMs] reads, so the caller
     * should pass it straight back rather than keeping its own.
     */
    fun activeAnimation(nowMs: Long): PotsikAction? = engine.activeAction(nowMs)

    /**
     * Milliseconds on the monotonic clock the animation deadlines are on.
     *
     * The clock is measured from [MONOTONIC_BASE] — the moment the stage was
     * made — so the numbers it hands out go up. A mark taken on every call
     * would measure the time between taking the mark and reading it, which is
     * always zero: every deadline would be in the past and every pose would
     * freeze where it was drawn.
     */
    fun currentTimeMs(): Long = MONOTONIC_BASE.elapsedNow().inWholeMilliseconds

    private fun currentPoint(): PotsikPoint {
        val placement = _placement.value
        return PotsikPoint(placement.x, placement.y)
    }

    /**
     * Where the layout wants the character, in the same normalized
     * coordinates the placement uses.
     *
     * Null when no place in the layout is on screen — an empty chat with no
     * messages has nowhere to put him.
     */
    private fun defaultPlacement(): PotsikPlacement? {
        val anchor = _anchors.value.maxByOrNull { it.key.priority }?.value ?: return null
        val width = _area.value.width.toFloat()
        val height = _area.value.height.toFloat()
        val base = _baseCharacterSize.value
        if (width <= 0f || height <= 0f || base <= 0f) return null
        val origin = _areaOrigin.value
        return PotsikPlacement(
            x = (anchor.centerX - origin.x) / width,
            y = (anchor.feetY - origin.y) / height,
            scale = anchor.sizePx / base,
        )
    }

    /**
     * The factors that make the character the size he is drawn at, apart from
     * the pinch the user makes with two fingers.
     *
     * The layout's own size for the place he is standing in, the user's size
     * slider and the firmware's own SIZE row, all in one number. Everything
     * that has to do with his size — the drawing, the touch box, the bounds —
     * works from this one factor, so they cannot disagree about how big he
     * is.
     */
    private fun sizeFactors(): Float {
        val layout = defaultPlacement()?.scale ?: 1f
        val percent = _settings.value.sizePercent.coerceIn(25, 400) / 100f
        return layout * _sizePreference.value * percent
    }

    private fun recompute() {
        val pinned = _pinnedPosition.value
        val base = defaultPlacement()
        val factors = sizeFactors()
        val requested = if (base == null) {
            // Nothing in the layout wants him, but the agent may still have
            // put him somewhere. Keep that, centred, until the layout has
            // something to say.
            PotsikPlacement(
                x = pinned?.x ?: _placement.value.x,
                y = pinned?.y ?: _placement.value.y,
                scale = factors * _userScale.value,
            )
        } else {
            PotsikPlacement(
                x = pinned?.x ?: base.x,
                y = pinned?.y ?: base.y,
                scale = factors * _userScale.value,
            )
        }
        _placement.value = clampPlacement(
            requested = requested,
            areaWidth = _area.value.width.toFloat(),
            areaHeight = _area.value.height.toFloat(),
            baseCharacterSize = _baseCharacterSize.value,
            topInset = _topInset.value,
        )
    }

    companion object {
        /** How far he has to move before the app is told about it. */
        const val MOVE_EPSILON = 0.004f

        /** How long an animation the agent asked for runs. */
        const val DEFAULT_ANIMATION_DURATION_MS = 10_000L

        /** The smallest the character may be scaled to, as a factor of his own size. */
        const val MIN_SCALE = 0.25f

        /** The largest the character may be scaled to, before the screen gets a say. */
        const val MAX_SCALE = 4f

        private val MONOTONIC = TimeSource.Monotonic

        /** The moment the clock starts, so the times it hands out go up. */
        private val MONOTONIC_BASE = MONOTONIC.markNow()
    }
}

/**
 * The rule for where Потсик may be.
 *
 * He is never allowed to leave the screen: the whole of him — the box the
 * renderer draws into, feet included — has to stay inside it. The scale is
 * capped by the same rule, so a large one cannot be scaled up into a shape
 * too big to fit at all.
 *
 * The character's feet are on [PotsikPlacement.y] and his centre is on
 * [PotsikPlacement.x], so the box spans from `y - height` to `y` vertically
 * and from `x - width / 2` to `x + width / 2` horizontally.
 *
 * Pure on purpose: the rule is worth a test of its own, and it is the one
 * thing the agent's tool must not be able to break.
 */
fun clampPlacement(
    requested: PotsikPlacement,
    areaWidth: Float,
    areaHeight: Float,
    baseCharacterSize: Float,
    topInset: Float = 0f,
): PotsikPlacement {
    val scale = requested.scale.coerceIn(PotsikStage.MIN_SCALE, PotsikStage.MAX_SCALE)
    if (areaWidth <= 0f || areaHeight <= 0f || baseCharacterSize <= 0f) {
        // Nothing to measure against yet — the screen has not reported its
        // size. Keep the request, still inside the unit square.
        return PotsikPlacement(
            x = requested.x.coerceIn(0f, 1f),
            y = requested.y.coerceIn(0f, 1f),
            scale = scale,
        )
    }

    // A character bigger than the screen cannot be shown whole, so the scale
    // is capped by the smaller side of the screen.
    val fitScale = min(areaWidth, areaHeight) / baseCharacterSize
    val effectiveScale = scale.coerceAtMost(fitScale.coerceAtLeast(PotsikStage.MIN_SCALE))

    val width = baseCharacterSize * effectiveScale
    val height = baseCharacterSize * effectiveScale

    val minX = width / 2f / areaWidth
    val maxX = 1f - minX
    val topLimit = (topInset + height) / areaHeight
    val minY = maxOf(height / areaHeight, topLimit).coerceAtMost(1f)

    return PotsikPlacement(
        x = if (minX > maxX) 0.5f else requested.x.coerceIn(minX, maxX),
        y = if (minY > 1f) 0.5f else requested.y.coerceIn(minY, 1f),
        scale = effectiveScale,
    )
}
