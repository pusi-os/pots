package com.pusios.pots.ui.potsik

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.sin

/**
 * Where the character is, for the purposes of a touch.
 *
 * The firmware's hit test works off where it last drew him and how big it
 * drew him; this is the same information in the same form.
 */
@Immutable
data class PotsikBox(
    /** The body's centre line, in pixels. */
    val cx: Float,
    /** The top of his head, in pixels. */
    val headTopY: Float,
    /** The scale he is drawn at: pixels per character unit. */
    val scale: Float,
) {
    /** The bottom of his feet. */
    val footBottom: Float get() = headTopY + 53f * scale
}

/**
 * The character's behaviour.
 *
 * This is the firmware's `potsy.cpp` engine: the moods, the timings, the
 * idle chatter and the reactions to being touched. It is kept separate from
 * the drawing, because the firmware keeps them separate too — the renderer
 * asks the engine what the character is doing and draws it, and never the
 * other way round.
 *
 * The engine is driven by a clock the caller owns ([tick]), so the same
 * engine can be stepped in a test, on a frame and in a screenshot.
 */
internal class PotsikEngine(
    /** The settings that change how he behaves. */
    private val settings: PotsikSettings,
    /** True when the app is in Russian. */
    private val isRussian: () -> Boolean,
) {

    // ---- the state ---------------------------------------------------

    private var mood: PotsikMood = PotsikMood.IDLE
    private var moodUntil: Long = 0
    private var actPose: PotsikVisitPose = PotsikVisitPose.NONE
    private var actPoseUntil: Long = 0
    private var reactPose: PotsikReactPose = PotsikReactPose.STARTLED

    private var bubbleText: String? = null
    private var bubbleStart: Long = 0
    private var bubbleUntil: Long = 0

    // A multi-line bit in progress. The list is reused between chains, so
    // nothing is allocated per frame; [chainNext] is the index of the line
    // still waiting to be shown (the one on screen is the one before it).
    private val chainQueue = ArrayList<String>(MAX_CHAIN_LINES)
    private var chainNext: Int = 0

    private var nextIdleAt: Long = 0
    private var lastInteraction: Long = 0

    /**
     * Whether anything has happened to him yet.
     *
     * Until something has, the clock still runs — he has been alone since the
     * app started, not since the beginning of time — so the first tick sets
     * the baseline.
     */
    private var interacted: Boolean = false
    private var lastTapAt: Long = 0
    private var napStart: Long = 0
    private var napCooldownUntil: Long = 0
    private var stretchStart: Long = 0
    private var gumStart: Long = 0
    private var binoc: Boolean = false

    private var walkStart: Long = 0
    private var walkBeat: Int = 0
    private var walkDir: Int = 1

    private var dtStart: Long = 0
    private var recoilK: Float = 0.55f
    private var shakeStart: Long = 0
    private var leanStart: Long = 0
    private var leanDir: Int = 1
    private var flickStart: Long = 0
    private var flickDir: Int = 1
    private var backSteps: Int = 0
    private var backAt: Long = 0

    private var tapZone: Int = 0

    private var grabbed: Boolean = false
    private var grabX: Float = 0f
    private var grabY: Float = 0f
    private var grabAt: Long = 0
    private var prevX: Float = 0f
    private var prevY: Float = 0f
    private var prevT: Long = 0
    private var velX: Float = 0f
    private var velY: Float = 0f
    private var dropStart: Long = 0
    private var dropX: Float = 0f
    private var dropY: Float = 0f
    private var dangle: Boolean = false
    private var carryOffsetX: Float = 0f
    private var carryOffsetY: Float = 0f

    // The room he is thrown around in: the width of the area, the line his
    // feet stand on and the line his head may not go above. The firmware
    // reads the same three numbers off the screen it is drawing on.
    private var roomWidth: Float = 0f
    private var roomFloor: Float = 0f
    private var roomCeiling: Float = 0f
    private var roomScale: Float = 1f

    // The throw: a finger that let go while it was moving throws him.
    private var throwPhase: ThrowPhase = ThrowPhase.NONE
    private var throwX: Float = 0f
    private var throwY: Float = 0f
    private var throwVX: Float = 0f
    private var throwVY: Float = 0f
    private var throwAt: Long = 0
    private var throwLast: Long = 0
    private var throwPhaseAt: Long = 0
    private var throwSqAt: Long = 0
    private var throwSqK: Float = 0f
    private var throwHard: Float = 0f
    private var throwBounces: Int = 0

    // What the app is doing, as far as the character is concerned: the
    // working playlist and the idle roll both hang off it.
    private var phase: PotsikPhase = PotsikPhase.IDLE
    private var workPlaylist: List<PotsikAction> = emptyList()
    private var workIndex: Int = 0
    private var workUntil: Long = 0

    private val random = PotsikRandom(0x5EED_1234L)

    // The animation the agent asked for, and how long it lasts.
    private var actionOverride: PotsikAction? = null
    private var actionUntil: Long = 0

    // ---- the clock ---------------------------------------------------

    /**
     * Advances the engine.
     *
     * The firmware only advances its state when the screen is redrawn, and
     * only once per logical frame — the banded renderers call `tick()`
     * several times for one picture. This does the same: [advance] is false
     * for the extra calls.
     */
    fun tick(now: Long, advance: Boolean = true) {
        if (advance) {
            // The clock starts when he does, so he is not treated as having
            // been alone for ten minutes the moment the app opens.
            if (!interacted) {
                lastInteraction = now
                interacted = true
            }
            if (mood != PotsikMood.IDLE && now > moodUntil) mood = PotsikMood.IDLE
            if (actPose != PotsikVisitPose.NONE && now > actPoseUntil) actPose = PotsikVisitPose.NONE
            if (binoc && now > binocUntil) binoc = false
            advanceChain(now)
            runIdleRoll(now)
            runWorkPlaylist(now)
        }
        updateCarry(now)
        stepThrow(now)
    }

    /**
     * The room he is thrown around in.
     *
     * The firmware reads the width of the screen, the line his feet stand on
     * and the line his head may not go above off the screen it draws on; the
     * app's screen tells the engine the same three numbers, so a throw ends
     * up in the same places a throw on the device does.
     */
    fun setRoom(width: Float, floorY: Float, ceilingY: Float, scale: Float) {
        roomWidth = width
        roomFloor = floorY
        roomCeiling = ceilingY
        roomScale = if (scale > 0f) scale else 1f
    }

    private var binocUntil: Long = 0

    // ---- the idle chatter ---------------------------------------------

    /**
     * The idle roll.
     *
     * The firmware rolls the dice every few seconds when nothing is going
     * on, and the roll picks one of a dozen ways to spend the time: a line of
     * chatter, a stretch, a walk, a dance, a nap. The app has the same
     * quiet moments — between messages — so the same roll runs here.
     */
    private fun runIdleRoll(now: Long) {
        if (!settings.banterEnabled) return
        // While a request is running the playlist is what he is doing; the
        // idle roll would only fight it.
        if (phase == PotsikPhase.WORKING) return
        if (mood != PotsikMood.IDLE) return
        if (now < nextIdleAt) return
        if (now < bubbleUntil) return

        val idleFor = if (interacted) now - lastInteraction else 0L
        val longIdle = idleFor > 90_000L

        when {
            // He has been alone for so long that he falls asleep, and wakes
            // up with a stretch.
            idleFor > SLEEPY_AFTER_MS && now >= napCooldownUntil && (napStart == 0L || now - napStart < NAP_DURATION_MS) -> {
                say(PotsikBanter.pick(PotsikBanter.sleepy, random, isRussian()), MIN_BUBBLE_MS, now)
                mood = PotsikMood.SLEEPY
                moodUntil = now + tempo(8000)
                nextIdleAt = now + tempo(8000)
                if (napStart == 0L) napStart = now
            }

            napStart != 0L -> {
                napStart = 0
                napCooldownUntil = now + SLEEPY_AFTER_MS
                say(PotsikBanter.pick(PotsikBanter.stretch, random, isRussian()), MIN_BUBBLE_MS, now)
                mood = PotsikMood.STRETCH
                stretchStart = now
                moodUntil = now + STRETCH_MS
                nextIdleAt = now + tempo(8000)
            }

            random.nextInt(10) == 0 -> {
                say(PotsikBanter.pick(PotsikBanter.dance, random, isRussian()), 3200, now)
                mood = PotsikMood.DANCE
                moodUntil = now + tempo(2400)
                nextIdleAt = now + tempo(9000) + random.nextInt(13000)
            }

            random.nextInt(7) == 0 -> {
                say(PotsikBanter.pick(PotsikBanter.walk, random, isRussian()), MIN_BUBBLE_MS, now)
                mood = PotsikMood.WALK
                moodUntil = now + WALK_DURATION_MS
                walkStart = now
                walkDir = if (random.nextInt(2) == 0) 1 else -1
                nextIdleAt = now + WALK_DURATION_MS + 12000 + random.nextInt(18000)
            }

            random.nextInt(10) == 0 -> {
                say(PotsikBanter.pick(PotsikBanter.gum, random, isRussian()), 3000, now)
                mood = PotsikMood.GUM
                gumStart = now
                moodUntil = now + GUM_GROW_MS + GUM_HOLD_MS + GUM_POP_MS + 400
                nextIdleAt = now + tempo(14000) + random.nextInt(18000)
            }

            random.nextInt(10) == 0 -> {
                say(PotsikBanter.pick(PotsikBanter.stretch, random, isRussian()), 3000, now)
                mood = PotsikMood.STRETCH
                stretchStart = now
                moodUntil = now + STRETCH_MS
                nextIdleAt = now + tempo(12000) + random.nextInt(16000)
            }

            random.nextInt(10) == 0 -> {
                say(PotsikBanter.pick(PotsikBanter.lean, random, isRussian()), MIN_BUBBLE_MS, now)
                mood = PotsikMood.LEAN
                leanStart = now
                leanDir = if (random.nextInt(2) == 0) 1 else -1
                moodUntil = now + tempo(LEAN_MS)
                nextIdleAt = now + tempo(9000) + random.nextInt(13000)
            }

            random.nextInt(13) == 0 -> {
                say(PotsikBanter.pick(PotsikBanter.wink, random, isRussian()), MIN_BUBBLE_MS, now)
                mood = PotsikMood.WINK
                moodUntil = now + tempo(1800)
                nextIdleAt = now + tempo(9000) + random.nextInt(13000)
            }

            random.nextInt(31) == 0 -> {
                say(PotsikBanter.pick(PotsikBanter.trip, random, isRussian()), MIN_BUBBLE_MS, now)
                mood = PotsikMood.TRIP
                dtStart = now
                recoilK = 0.8f
                moodUntil = now + tempo(1600)
                nextIdleAt = now + tempo(9000) + random.nextInt(13000)
            }

            longIdle && random.nextInt(6) == 0 -> {
                say(PotsikBanter.pick(PotsikBanter.sit, random, isRussian()), MIN_BUBBLE_MS, now)
                mood = PotsikMood.ACT
                actPose = PotsikVisitPose.CROUCH
                actPoseUntil = now + tempo(9000)
                moodUntil = now + tempo(9000)
                nextIdleAt = now + tempo(9000) + 8000 + random.nextInt(12000)
            }

            else -> {
                // The pool lottery: the line he says, and what he does while
                // he says it.
                val pool = when {
                    random.nextInt(5) == 0 -> PotsikBanter.noir
                    random.nextInt(4) == 0 -> PotsikBanter.encourage
                    longIdle && random.nextInt(3) == 0 -> PotsikBanter.bored
                    else -> PotsikBanter.idle
                }
                speakFrom(pool, MIN_BUBBLE_MS, now, chainable = true)
                mood = if (random.nextInt(2) == 0) PotsikMood.WAVE else PotsikMood.BOUNCE
                moodUntil = now + tempo(1200)
                nextIdleAt = now + tempo(9000) + random.nextInt(13000)
            }
        }
    }

    // ---- the working playlist -------------------------------------------

    /**
     * The animations a request runs through.
     *
     * The list is made when the request starts: twenty animations, picked at
     * random out of everything he knows how to do, and they play one after
     * another until the request is over. Nothing here costs a model a
     * thought — the list is drawn from the animation catalogue the app
     * already has, and the lines that go with it are the ones he already
     * says.
     *
     * The firmware rolls its dice the same way, a few times a minute, and
     * picks something to do from the same pool; here the roll happens once
     * per request and the list is played to the end, over and over.
     */
    private fun buildWorkPlaylist() {
        val pool = PotsikAnimationCatalog.workingActions + PotsikAnimationCatalog.idleActions
        workPlaylist = List(WORK_PLAYLIST_SIZE) { pool[random.nextInt(pool.size)] }
        workIndex = 0
    }

    /**
     * Plays the next animation of the playlist.
     *
     * Each one lasts a few seconds, and every now and then a line goes with
     * it: the request is long enough for a conversation, and a character
     * that only moves in silence reads as broken.
     */
    private fun runWorkPlaylist(now: Long) {
        if (phase != PotsikPhase.WORKING) return
        if (now < workUntil) return
        if (workPlaylist.isEmpty()) buildWorkPlaylist()

        val action = workPlaylist[workIndex % workPlaylist.size]
        workIndex = (workIndex + 1) % workPlaylist.size

        // The slot is the same number for the animation and for the wait: the
        // animation fills its slot exactly, so it is never cut off half way
        // through its cycle. Before, the mood lasted the unscaled 3.2 seconds
        // while the wait was the scaled one, so at the default tempo every
        // animation was cut a third of the way through.
        val slot = workSlotMs()

        // The animation itself: the mood it puts him in and the pose it
        // strikes.
        playMood(action.mood, slot, now)
        if (action.visitPose != PotsikVisitPose.NONE) {
            actPose = action.visitPose
            actPoseUntil = now + slot
        }
        workUntil = now + slot

        // The line that goes with it, every other time round.
        if (random.nextInt(2) == 0) {
            val pool = when (action.group) {
                "idle" -> PotsikBanter.idle
                else -> PotsikBanter.watching
            }
            say(PotsikBanter.pick(pool, random, isRussian()), MIN_BUBBLE_MS, now)
        }
    }

    // ---- the frame -----------------------------------------------------

    /**
     * The state of the character right now, for the renderer.
     *
     * Everything here is computed from the clock rather than stored, except
     * the parts the engine itself owns: the mood, the bubble and the gesture
     * state. The bob, the squash and the walk phase are pure functions of the
     * time, exactly as they are in the firmware.
     */
    fun frame(now: Long): PotsikFrame {
        // A mood and a pose end on their own; the frame is drawn after the
        // engine has been stepped, so the expiry is applied here too and the
        // two never disagree.
        if (mood != PotsikMood.IDLE && now > moodUntil) mood = PotsikMood.IDLE
        if (actPose != PotsikVisitPose.NONE && now > actPoseUntil) actPose = PotsikVisitPose.NONE

        val bobAmt = when (mood) {
            PotsikMood.BOUNCE -> 9f
            PotsikMood.SLEEPY -> 1.5f
            PotsikMood.DANCE -> 6f
            else -> 3f
        }
        val bobSpeed = when (mood) {
            PotsikMood.BOUNCE -> 220f
            PotsikMood.SLEEPY -> 2200f
            PotsikMood.DANCE -> 300f
            else -> 1100f
        }
        var bob = sin((now % bobSpeed.toLong()) / bobSpeed * PotsikPainter.TAU) * bobAmt
        if (mood == PotsikMood.BOUNCE) bob = -abs(bob)

        // The squash: how far the head sinks into the shoulders.
        val u = if (bobAmt > 0.01f) -bob / bobAmt else 0f
        var headDrop = if (mood == PotsikMood.BOUNCE) {
            ((0.35f - u) * 0.40f * bobAmt)
        } else {
            (-u * 0.16f * bobAmt)
        }.toInt()

        // The walk beat: a sniff, a look up, a scratch behind the ear.
        var walkSweep = 0f
        if (mood == PotsikMood.WALK) {
            val walkT = ((now - walkStart).toFloat() / WALK_DURATION_MS).coerceIn(0f, 1f)
            // The sweep: five strides across, the firmware's own.
            walkSweep = sin(walkT * PotsikPainter.TAU * WALK_CYCLES) * walkDir
            val cyc = walkT * WALK_CYCLES
            val ci = cyc.toInt()
            val cf = cyc - ci
            walkBeat = if (cf > 0.18f && cf < 0.36f) (potsikHash((ci + 1) * 2654435761.toInt() xor walkStart.toInt()) % 4) else 0
            if (walkBeat == 1) {
                headDrop += 4
            } else if (walkBeat == 2) {
                headDrop -= 3
            }
        } else {
            walkBeat = 0
        }

        // The lean: he tips over to listen to something.
        if (mood == PotsikMood.LEAN) {
            val le = now - leanStart
            val total = tempo(LEAN_MS)
            val k = when {
                le < 300L -> le.toFloat() / 300f
                le + 400L < total -> 1f
                le < total -> (total - le).toFloat() / 400f
                else -> 0f
            }
            val eased = k * k * (3f - 2f * k)
            headDrop -= (eased * 2f).toInt()
        }

        // The trip: a dip after the stumble.
        if (mood == PotsikMood.TRIP && dtStart != 0L) {
            val te = now - dtStart
            if (te > 380L && te < 700L) {
                headDrop += (5f * sin((te - 380L) / 320f * PotsikPainter.PI)).toInt()
            }
        }

        // The shake: a shudder that decays.
        if (shakeStart != 0L && now >= shakeStart && now - shakeStart < SHAKE_MS) {
            val k = 1f - (now - shakeStart).toFloat() / SHAKE_MS
            headDrop += (k * 2f).toInt()
        }

        // The landing squash after a drop.
        if (dropStart != 0L) {
            val de = now - dropStart
            if (de < DROP_MS + LAND_MS) {
                val k = 1f - (de - DROP_MS).toFloat() / LAND_MS
                headDrop = (6f * k).toInt()
            }
        }

        // The landing squash, off a throw: the same channel as the drop's.
        if (throwSqAt != 0L && now - throwSqAt < LAND_MS) {
            val k = (1f - (now - throwSqAt).toFloat() / LAND_MS) * throwSqK
            headDrop += (7f * roomScale * k).toInt()
        }

        // The double-take: he sees something and recoils.
        var bodyOffset = 0f
        if ((mood == PotsikMood.SHOCKED || mood == PotsikMood.TRIP) && dtStart != 0L && now - dtStart < DT_TOTAL_MS) {
            val e = now - dtStart
            val a = 2f + 7f * recoilK
            val b = -(3f + 10f * recoilK)
            bodyOffset = when {
                e < 140L -> a * e / 140f

                e < 380L -> a

                e < 580L -> a + (b - a) * ((e - 380L) / 200f)

                else -> {
                    val k = (e - 580L).toFloat() / (DT_TOTAL_MS - 580L)
                    b * (1f - k) * kotlin.math.cos(k * 6f)
                }
            }
        }

        // The flick: he slides into the wall and comes back.
        var flickOffset = 0f
        if (flickStart != 0L) {
            val fe = now - flickStart
            flickOffset = when {
                fe < FLICK_OUT_MS -> flickDir * (fe.toFloat() / FLICK_OUT_MS) * 10f

                fe < FLICK_OUT_MS + FLICK_HOLD_MS -> flickDir * 10f

                fe < FLICK_OUT_MS + FLICK_HOLD_MS + FLICK_BACK_MS ->
                    flickDir * 10f * (1f - (fe - FLICK_OUT_MS - FLICK_HOLD_MS).toFloat() / FLICK_BACK_MS)

                else -> 0f
            }
            if (fe > FLICK_OUT_MS + FLICK_HOLD_MS + FLICK_BACK_MS) flickStart = 0
        }

        // The backing away, after something got too close.
        var backOffset = 0f
        if (backSteps > 0 && backAt != 0L) {
            val be = now - backAt
            if (be < 600L) {
                backOffset = -(1f - be / 600f) * 6f * backSteps
            }
        }

        // The dazed wobble, after a hard landing.
        val landing = landingWobble(now)

        return PotsikFrame(
            nowMs = now,
            mood = mood,
            actPose = actPose,
            reactPose = reactPose,
            headDrop = headDrop,
            bobOffset = bob,
            bodyOffset = bodyOffset + flickOffset + backOffset + landing,
            shadeDrop = if (mood == PotsikMood.SHOCKED && dtStart != 0L && now - dtStart > 380L && recoilK > 0.45f) 2 else 0,
            reachDir = 1,
            dangle = dangle,
            walkBeat = walkBeat,
            walkSweep = walkSweep,
            talking = isTalking(now),
            stretchStartMs = stretchStart,
            gumStartMs = gumStart,
            binoc = binoc,
            thrown = throwPhase != ThrowPhase.NONE,
            thrownX = throwX,
            thrownY = throwY,
            landingWobble = landingWobble(now),
            landingSquash = landingSquash(now),
        )
    }

    // ---- the bubble -----------------------------------------------------

    /** The line he is saying, or null when he is saying nothing. */
    fun bubble(now: Long): String? {
        val text = bubbleText ?: return null
        return if (now < bubbleUntil) text else null
    }

    /** When the line that is up was said — the bubble pops in from there. */
    fun bubbleStartedAt(): Long = bubbleStart

    /** True while a line is up, so his mouth flaps with it. */
    fun isTalking(now: Long): Boolean = bubbleText != null && now < bubbleUntil

    /**
     * Says something.
     *
     * The bubble's lifetime is scaled by the tempo, the same way every other
     * duration in the firmware is.
     */
    fun say(text: String, ms: Long, now: Long) {
        if (text.isBlank()) return
        // A single line replaces whatever chain was running.
        cancelChain()
        bubbleText = text
        bubbleStart = now
        bubbleUntil = now + tempo(ms.coerceAtLeast(MIN_BUBBLE_MS))
    }

    /** Clears the bubble, the way the firmware does when a new line replaces it. */
    fun hush() {
        cancelChain()
        bubbleText = null
        bubbleUntil = 0
    }

    /**
     * Says a line from [pool], or — for a chainable event, with the chance
     * [CHAIN_CHANCE_PERCENT] — a multi-line chain whose last line is the
     * punchline. Urgent and working-state lines never go through here.
     */
    private fun speakFrom(pool: List<PotsikBanter.Line>, ms: Long, now: Long, chainable: Boolean) {
        if (chainable && random.nextInt(100) < CHAIN_CHANCE_PERCENT && startChain(now)) return
        say(PotsikBanter.pick(pool, random, isRussian()), ms, now)
    }

    /** Starts a chain: its lines are queued, and the first one is shown now. */
    private fun startChain(now: Long): Boolean {
        val lines = PotsikBanter.pickChain(random, isRussian())
        if (lines.isEmpty()) return false
        chainQueue.clear()
        chainQueue.addAll(lines)
        chainNext = 1
        showLine(chainQueue[0], now)
        return true
    }

    /**
     * Shows the next queued line once the current one has run its time.
     * Driven by [tick], so the chain moves with the engine's clock and not
     * with any timer of its own.
     */
    private fun advanceChain(now: Long) {
        if (chainNext >= chainQueue.size) return
        if (now < bubbleUntil) return
        showLine(chainQueue[chainNext], now)
        chainNext++
    }

    /** Drops whatever is left of a chain. */
    private fun cancelChain() {
        chainQueue.clear()
        chainNext = 0
    }

    /**
     * Puts one line of a chain up. Each line gets its own time, between
     * [CHAIN_LINE_MIN_MS] and [CHAIN_LINE_MAX_MS] before the tempo, so a chain
     * does not read as a metronome.
     */
    private fun showLine(text: String, now: Long) {
        bubbleText = text
        bubbleStart = now
        val span = CHAIN_LINE_MAX_MS - CHAIN_LINE_MIN_MS
        bubbleUntil = now + tempo(CHAIN_LINE_MIN_MS + random.nextInt(span.toInt() + 1))
    }

    // ---- the app driving him --------------------------------------------

    /**
     * The app tells the engine what it is doing, and the engine decides how
     * he shows it.
     *
     * This is the bridge between the chat's phases and the firmware's moods:
     * a request running is a set of tools, a tool running is a mood, and the
     * line in the bubble is what he says about it.
     */
    fun setPhase(phase: PotsikPhase, toolName: String?, now: Long) {
        // A new phase ends the one before it: the playlist stops, the idle
        // roll starts again, and the character's own business is his own.
        if (this.phase != phase) {
            this.phase = phase
            workUntil = 0
            if (phase == PotsikPhase.WORKING) {
                // A new request: a new list of animations to play through.
                buildWorkPlaylist()
                // The tool's own pose plays first; the playlist takes over a
                // moment later.
                workUntil = now + firstDelayMs()
            }
        }
        when (phase) {
            PotsikPhase.IDLE -> {
                // The app has nothing to show, so the engine goes back to his
                // own business — but only if the chat is not interrupting a
                // line he is in the middle of.
                if (mood == PotsikMood.SHOCKED || mood == PotsikMood.ACT) return
                if (now < moodUntil) return
                mood = PotsikMood.IDLE
                nextIdleAt = now + tempo(9000) + random.nextInt(13000)
            }

            PotsikPhase.WORKING -> {
                // The request has started: the list of animations is made
                // now, and played until the request is over.
                val tool = toolName?.trim()?.lowercase()
                if (tool == null) {
                    mood = PotsikMood.ACT
                    actPose = PotsikVisitPose.LOOK_AROUND
                    actPoseUntil = now + 60000
                    moodUntil = now + firstDelayMs()
                    say(appLine(AppLine.THINKING), 3000, now)
                    return
                }
                when (tool) {
                    "web_search", "fetch_url", "open_url", "search_email", "check_email" -> {
                        // The binoculars: he is looking at something far away.
                        mood = PotsikMood.IDLE
                        binoc = true
                        binocUntil = now + 8000
                        actPose = PotsikVisitPose.NONE
                        say(appLine(AppLine.SEARCHING), 3000, now)
                    }

                    "compose_email", "reply_email", "send_sms", "reply_sms" -> {
                        mood = PotsikMood.ACT
                        actPose = PotsikVisitPose.SELFIE
                        actPoseUntil = now + 60000
                        say(appLine(AppLine.WRITING), 3000, now)
                    }

                    "schedule_task", "cancel_task", "list_tasks" -> {
                        mood = PotsikMood.ACT
                        actPose = PotsikVisitPose.POINT
                        actPoseUntil = now + 60000
                        say(appLine(AppLine.CALENDAR), 3000, now)
                    }

                    "memory_store", "memory_forget", "memory_learn" -> {
                        mood = PotsikMood.ACT
                        actPose = PotsikVisitPose.WIGGLE
                        actPoseUntil = now + 60000
                        say(appLine(AppLine.NOTES), 3000, now)
                    }

                    else -> {
                        mood = PotsikMood.JUGGLE
                        moodUntil = now + firstDelayMs()
                        say(appLine(AppLine.THINKING), 3000, now)
                    }
                }
            }

            PotsikPhase.SPEAKING -> {
                if (now < moodUntil) return
                mood = PotsikMood.IDLE
                moodUntil = now + 2000
                say(appLine(AppLine.SPEAKING), 3000, now)
            }

            PotsikPhase.ERROR -> {
                mood = PotsikMood.SHOCKED
                reactPose = PotsikReactPose.STARTLED
                dtStart = now
                recoilK = 0.55f
                moodUntil = now + tempo(1400)
                say(errorLine(), MIN_BUBBLE_MS, now)
            }
        }
    }

    /** The line he says when something has gone wrong. */
    private fun errorLine(): String = appLine(AppLine.ERROR)

    /** The line for one of the app's own situations, in the app's language. */
    private fun appLine(line: AppLine): String = if (isRussian()) line.ru else line.en

    /** What the app is doing, as the character says it. */
    private enum class AppLine(val en: String, val ru: String) {
        THINKING("Thinking.", "Думаю."),
        SEARCHING("Looking it up.", "Ищу."),
        WRITING("Writing it.", "Пишу."),
        CALENDAR("Checking the calendar.", "Смотрю календарь."),
        NOTES("Writing it down.", "Записываю."),
        SPEAKING("Here it is.", "Вот, слушай."),
        ERROR("Something went sideways.", "Что-то пошло не так."),
    }

    /**
     * Plays one of the named animations — the agent's tool's `play_animation`.
     *
     * The name is kept as well as the mood, so the tool can be asked what is
     * playing and get the same name back.
     */
    fun playAction(action: PotsikAction, durationMs: Long, now: Long) {
        actionOverride = action
        actionUntil = now + durationMs
        playMood(action.mood, durationMs, now)
        if (action.visitPose != PotsikVisitPose.NONE) {
            playPose(action.visitPose, durationMs, now)
        }
    }

    /** The animation the agent asked for, if it is still running. */
    fun activeAction(now: Long): PotsikAction? = actionOverride?.takeIf { now < actionUntil }

    /**
     * Plays an emote.
     *
     * This is what the agent's tool calls: the same poses the firmware's
     * emote scripts use, with the same names.
     */
    fun playPose(pose: PotsikVisitPose, durationMs: Long, now: Long) {
        mood = PotsikMood.ACT
        actPose = pose
        actPoseUntil = now + durationMs
        moodUntil = now + durationMs
        lastInteraction = now
        interacted = true
    }

    /** Plays one of the plain moods, by name — the tool's `play_animation`. */
    fun playMood(mood: PotsikMood, durationMs: Long, now: Long) {
        this.mood = mood
        moodUntil = now + durationMs
        lastInteraction = now
        interacted = true
        when (mood) {
            PotsikMood.STRETCH -> stretchStart = now

            PotsikMood.GUM -> gumStart = now

            PotsikMood.WALK -> walkStart = now

            PotsikMood.SHOCKED -> {
                dtStart = now
                recoilK = 0.55f
            }

            else -> Unit
        }
    }

    // ---- the gestures -----------------------------------------------------

    /** Whether a touch at this point lands on him. */
    fun hitTest(x: Float, y: Float, box: PotsikBox): Boolean {
        val halfW = 24f * box.scale
        val top = box.headTopY - 20f * box.scale
        val bot = box.footBottom
        return x >= box.cx - halfW && x <= box.cx + halfW && y >= top && y <= bot
    }

    /**
     * Notes where a touch landed on him.
     *
     * The body is split in three: the head at the top, the belly in the
     * middle and the feet at the bottom, and each gets its own reaction.
     */
    fun noteTapAt(x: Float, y: Float, box: PotsikBox) {
        tapZone = 0
        val top = box.headTopY - 20f * box.scale
        val bot = box.footBottom
        if (y < top || y > bot) return
        val span = bot - top
        if (span <= 0f) return
        val k = ((y - top) * 3f / span).toInt()
        tapZone = if (k <= 0) {
            1
        } else if (k == 1) {
            2
        } else {
            3
        }
    }

    /**
     * A tap on him.
     *
     * He bounces, says something about it, and remembers that he was touched.
     */
    fun onPetted(now: Long) {
        lastInteraction = now
        interacted = true
        lastTapAt = now
        when (tapZone) {
            1 -> if (random.nextInt(2) == 0) {
                mood = PotsikMood.IDLE
                leanStart = now
                leanDir = 0
                say(PotsikBanter.pick(PotsikBanter.tickleHead, random, isRussian()), MIN_BUBBLE_MS, now)
                return
            }

            2 -> if (random.nextInt(2) == 0) {
                moodUntil = now + tempo(1600)
                say(PotsikBanter.pick(PotsikBanter.tickleBelly, random, isRussian()), MIN_BUBBLE_MS, now)
                return
            }

            3 -> if (random.nextInt(2) == 0) {
                moodUntil = now + tempo(700)
                say(PotsikBanter.pick(PotsikBanter.tickleFeet, random, isRussian()), MIN_BUBBLE_MS, now)
                return
            }
        }
        mood = PotsikMood.BOUNCE
        moodUntil = now + tempo(1200)
        speakFrom(PotsikBanter.pet, MIN_BUBBLE_MS, now, chainable = true)
    }

    /** A finger held on him. */
    fun onHeld(now: Long) {
        lastInteraction = now
        interacted = true
        mood = PotsikMood.BOUNCE
        moodUntil = now + tempo(1500)
        speakFrom(PotsikBanter.held, MIN_BUBBLE_MS, now, chainable = true)
    }

    /** A finger stroking him. */
    fun onPetting(now: Long) {
        lastInteraction = now
        interacted = true
        mood = PotsikMood.BOUNCE
        moodUntil = now + tempo(900)
        if (now - lastTapAt > 2000) {
            lastTapAt = now
            say(PotsikBanter.pick(PotsikBanter.petting, random, isRussian()), MIN_BUBBLE_MS, now)
        }
    }

    /**
     * Picks him up.
     *
     * The firmware smooths the finger's speed across the last few frames,
     * so one jittery sample cannot throw him across the room on its own.
     */
    fun grabTo(x: Float, y: Float, now: Long) {
        if (grabbed && prevT != 0L && now - prevT in 0 until 150) {
            val dt = (now - prevT).toFloat()
            velX = 0.5f * velX + 0.5f * (x - prevX) / dt
            velY = 0.5f * velY + 0.5f * (y - prevY) / dt
        } else if (!grabbed || now - prevT >= 150) {
            velX = 0f
            velY = 0f
        }
        if (now != prevT) {
            prevX = x
            prevY = y
            prevT = now
        }
        grabbed = true
        dropStart = 0
        // Caught in mid-air: a finger that reaches him while he is still
        // flying takes him out of the throw.
        throwPhase = ThrowPhase.NONE
        throwSqAt = 0
        grabX = x
        grabY = y
        grabAt = now
        lastInteraction = now
        interacted = true
    }

    /**
     * Lets go of him.
     *
     * The firmware's rule: a finger that was still moving when it let go
     * throws him, and one that had already stopped drops him. The throw is
     * what the fast flick across the screen does; the drop is what letting
     * go of a still finger does.
     */
    fun release(now: Long) {
        if (!grabbed) return
        val speed = velX * velX + velY * velY
        if (now - prevT < THROW_MIN_GAP_MS && speed > THROW_MIN_V * THROW_MIN_V) {
            launch(velX, velY, now)
            return
        }
        grabbed = false
        prevT = 0
        dropStart = now
        dropX = grabX
        dropY = grabY
        lastInteraction = now
        interacted = true
    }

    /**
     * Throws him.
     *
     * He leaves the finger with the finger's own speed, and from there the
     * physics do the rest: he falls, he bounces off the floor, and if he is
     * still moving when he lands he bounces again.
     */
    private fun launch(vx: Float, vy: Float, now: Long) {
        val limit = THROW_MAX_V
        val clampedX = vx.coerceIn(-limit, limit)
        val clampedY = vy.coerceIn(-limit, limit)
        grabbed = false
        dropStart = 0
        flickStart = 0
        throwPhase = ThrowPhase.AIR
        throwX = grabX
        throwY = grabY
        throwVX = clampedX
        throwVY = clampedY
        throwAt = now
        throwLast = now
        throwPhaseAt = now
        throwSqAt = 0
        throwSqK = 0f
        throwHard = 0f
        throwBounces = 0
        prevT = 0
        lastInteraction = now
        interacted = true
        mood = PotsikMood.SHOCKED
        moodUntil = now + 1600
        say(PotsikBanter.pick(PotsikBanter.thrown, random, isRussian()), MIN_BUBBLE_MS, now)
    }

    /**
     * The throw, one step at a time.
     *
     * The firmware's own physics: gravity, a bounce off the walls, a bounce
     * off the floor that gets weaker each time, and then he sits down,
     * dizzy if it was a hard landing.
     */
    private fun stepThrow(now: Long) {
        if (throwPhase != ThrowPhase.AIR) return
        // In the air he hangs, the way the firmware's does.
        dangle = true

        var dt = now - throwLast
        if (dt > 60) dt = 60
        throwLast = now

        val halfWidth = 24f * roomScale
        val leftX = halfWidth
        val rightX = if (roomWidth > 0f) roomWidth - halfWidth else leftX

        // Small fixed steps, so a slow frame cannot carry him through the
        // floor or a wall.
        while (dt > 0 && throwPhase == ThrowPhase.AIR) {
            val step = if (dt > 8) 8f else dt.toFloat()
            dt -= step.toInt()
            throwVY += THROW_G * step
            throwX += throwVX * step
            throwY += throwVY * step

            if (throwX < leftX && throwVX < 0) {
                throwX = leftX
                if (-throwVX > throwHard) throwHard = -throwVX
                throwVX = -throwVX * 0.55f
            } else if (throwX > rightX && throwVX > 0) {
                throwX = rightX
                if (throwVX > throwHard) throwHard = throwVX
                throwVX = -throwVX * 0.55f
            }
            if (throwY < roomCeiling && throwVY < 0) {
                throwY = roomCeiling
                throwVY = -throwVY * 0.4f
            }
            if (throwY >= roomFloor && throwVY > 0) {
                throwY = roomFloor
                throwSqAt = now
                throwSqK = if (throwVY > 0.9f) 1f else throwVY / 0.9f
                if (throwVY > throwHard) throwHard = throwVY
                if (throwVY > 0.22f && throwBounces < 4) {
                    throwVY = -throwVY * 0.42f
                    throwVX *= 0.7f
                    throwBounces++
                } else {
                    throwVY = 0f
                    throwPhase = ThrowPhase.SIT
                    throwPhaseAt = now
                    dangle = false
                    val hard = throwHard > 1f || throwBounces >= 2
                    mood = PotsikMood.IDLE
                    moodUntil = 0
                    val lines = if (hard) PotsikBanter.dizzy else PotsikBanter.land
                    say(PotsikBanter.pick(lines, random, isRussian()), MIN_BUBBLE_MS, now)
                }
            }
        }

        // Something went wrong if he is still up there after five seconds:
        // put him down.
        if (throwPhase == ThrowPhase.AIR && now - throwAt > 5000) {
            throwY = roomFloor
            throwPhase = ThrowPhase.SIT
            throwPhaseAt = now
        }
    }

    /** True while he is in the air, thrown by a finger that let go moving. */
    fun isThrown(): Boolean = throwPhase != ThrowPhase.NONE

    /** Where he is while he is thrown: the centre of his body, in pixels. */
    fun thrownX(): Float = throwX

    /** Where he is while he is thrown: the top of his head, in pixels. */
    fun thrownY(): Float = throwY

    /** The moment he came down, so the app can tell a fresh landing from an old one. */
    fun landedAt(): Long = throwPhaseAt

    /** True while he is sitting on the floor after a landing. */
    fun isSitting(): Boolean = throwPhase == ThrowPhase.SIT

    /** How hard the landing was: the wobble that dies away. */
    fun landingWobble(now: Long): Float {
        if (throwPhase != ThrowPhase.SIT) return 0f
        val hard = throwHard > 1f || throwBounces >= 2
        if (!hard) return 0f
        val k = 1f - (now - throwPhaseAt).toFloat() / THROW_SIT_MS
        if (k <= 0f) return 0f
        return sin((now - throwPhaseAt) / 45f) * 3f * roomScale * k
    }

    /** The squash of the landing itself. */
    fun landingSquash(now: Long): Float {
        if (throwSqAt == 0L || now - throwSqAt >= DROP_MS + LAND_MS) return 0f
        val k = (1f - (now - throwSqAt).toFloat() / LAND_MS) * throwSqK
        return 7f * roomScale * k
    }

    /** Where he is sitting, for the app to put him back where he landed. */
    fun landedPosition(): Pair<Float, Float> = throwX to throwY

    /**
     * Clears the throw, once the app has taken the landing into account.
     */
    fun clearThrow() {
        if (throwPhase == ThrowPhase.NONE) return
        throwPhase = ThrowPhase.NONE
        throwSqAt = 0
    }

    /** The phases of a throw: in the air, then down. */
    private enum class ThrowPhase { NONE, AIR, SIT }

    /** A fast swipe across him: he slides into the wall and wobbles back. */
    fun flick(dir: Int, now: Long) {
        flickStart = now
        flickDir = if (dir >= 0) 1 else -1
        lastInteraction = now
        interacted = true
        speakFrom(PotsikBanter.flick, MIN_BUBBLE_MS, now, chainable = true)
    }

    /**
     * The carry and the fall.
     *
     * While he is held he hangs from the finger and his legs kick; when the
     * finger lets go he drops, and lands with a squash.
     */
    private fun updateCarry(now: Long) {
        if (grabbed) {
            // A drag that has stopped is a drag that ended: the finger is
            // gone, and he is let go of rather than hanging in the air.
            if (now - grabAt > CARRY_TIMEOUT_MS) {
                grabbed = false
                dropStart = now
                dropX = grabX
                dropY = grabY
            } else {
                dangle = true
                return
            }
        }
        if (dropStart != 0L) {
            val de = now - dropStart
            if (de > DROP_MS + LAND_MS) {
                dropStart = 0
                dangle = false
            }
        }
    }

    /** The offset from where he was told to be, while he is carried or falling. */
    fun carryOffset(): Float = if (dangle) carryOffsetX else 0f

    /** The tempo: every duration in the firmware is a percentage of this. */
    fun tempo(ms: Long): Long = ms * settings.tempoPercent.coerceIn(50, 200) / 100

    /**
     * How long one animation of the working playlist is given.
     *
     * The slot is long enough for the animation to play its whole cycle: a
     * dance that is cut in half reads as a glitch, not as a dance. The tempo
     * still scales it — a slow character is slow all the way through — but
     * never so far that the slot drops under four seconds, which would make
     * the character look like it is twitching rather than moving, and never
     * so far that the request looks like it has stopped.
     */
    fun workSlotMs(): Long = tempo(WORK_ANIMATION_MS).coerceIn(WORK_MIN_ANIMATION_MS, WORK_MAX_ANIMATION_MS)

    /**
     * How long the tool's own pose is shown before the playlist takes over.
     *
     * The pose says *what* the agent is doing, so it is given the same floor
     * as an animation of the playlist: it has to be long enough to be seen.
     */
    fun firstDelayMs(): Long = tempo(WORK_FIRST_DELAY_MS).coerceIn(WORK_MIN_ANIMATION_MS, WORK_MAX_ANIMATION_MS)

    companion object {
        /** The floor on every bubble's life. */
        const val MIN_BUBBLE_MS = 5000L

        /** A chain: the chance it is picked for a chainable event, and its size cap. */
        const val CHAIN_CHANCE_PERCENT = 35
        const val MAX_CHAIN_LINES = 4

        /** How long one line of a chain stays up, before the tempo: 2.2 to 3.0 seconds. */
        const val CHAIN_LINE_MIN_MS = 2200L
        const val CHAIN_LINE_MAX_MS = 3000L

        /** How long a stretch lasts. */
        const val STRETCH_MS = 1900L

        /** How long the gum bubble grows, holds and pops. */
        const val GUM_GROW_MS = 1200L
        const val GUM_HOLD_MS = 600L
        const val GUM_POP_MS = 400L

        /** How long a walk lasts, and how many strides are in it. */
        const val WALK_DURATION_MS = 45_000L
        const val WALK_CYCLES = 5

        /** The double-take: how long the whole reaction takes. */
        const val DT_TOTAL_MS = 900L

        /** The shake, the lean, the flick. */
        const val SHAKE_MS = 650L
        const val LEAN_MS = 2600L
        const val FLICK_OUT_MS = 380L
        const val FLICK_HOLD_MS = 420L
        const val FLICK_BACK_MS = 1100L

        /**
         * How long a carry can go without a touch before he is let go of.
         *
         * The finger's own letting go ends a carry; this is the safety net
         * for the case where it never arrives, so it is measured in seconds
         * rather than in frames — a finger that pauses for a moment is still
         * holding him.
         */
        const val CARRY_TIMEOUT_MS = 5000L

        /** The drop and the landing. */
        const val DROP_MS = 260L
        const val LAND_MS = 220L

        /** How long he waits before he falls asleep, and how long a nap lasts. */
        const val SLEEPY_AFTER_MS = 600_000L
        const val NAP_DURATION_MS = 60_000L

        // ---- the working playlist ------------------------------------------

        /** How many animations a request is put together from. */
        const val WORK_PLAYLIST_SIZE = 20

        /**
         * How long one animation of the playlist lasts, at the normal tempo.
         *
         * Six seconds is long enough for a dance, a wave or a stretch to play
         * its whole cycle. The tempo scales it, but never below
         * [WORK_MIN_ANIMATION_MS] and never above [WORK_MAX_ANIMATION_MS].
         */
        const val WORK_ANIMATION_MS = 6000L

        /** The floor on one animation of the playlist: four seconds. */
        const val WORK_MIN_ANIMATION_MS = 4000L

        /** The ceiling on one animation of the playlist: twelve seconds. */
        const val WORK_MAX_ANIMATION_MS = 12000L

        /** How long the tool's own pose is shown before the playlist starts. */
        const val WORK_FIRST_DELAY_MS = 4500L

        // ---- the throw --------------------------------------------------------

        /** Below this speed a finger that lets go drops him rather than throws him. */
        const val THROW_MIN_V = 0.32f

        /** The fastest he can be thrown. */
        const val THROW_MAX_V = 1.7f

        /** The pull of the world on him, in pixels per millisecond squared. */
        const val THROW_G = 0.0038f

        /** How long a finger may be still before its release is a drop, not a throw. */
        const val THROW_MIN_GAP_MS = 120L

        /** How long he sits on the floor after a hard landing. */
        const val THROW_SIT_MS = 900L
    }
}
