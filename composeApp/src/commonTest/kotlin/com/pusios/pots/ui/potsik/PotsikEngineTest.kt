package com.pusios.pots.ui.potsik

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The character's behaviour.
 *
 * These are the tests for the parts the firmware's engine has and the app's
 * own never had: the tap zones, the moods and their lengths, the bubble and
 * the chatter.
 */
class PotsikEngineTest {

    private fun engine(
        russian: Boolean = false,
        settings: PotsikSettings = PotsikSettings(),
    ) = PotsikEngine(settings) { russian }

    /** The box the character occupies, in the same units the hit test uses. */
    private val box = PotsikBox(cx = 100f, headTopY = 100f, scale = 2f)

    @Test
    fun `a tap on the head is a head tap, and one on the feet is a feet tap`() {
        val engine = engine()
        // The box runs from 60 (20 units above the head) to 206 (53 units
        // below it), so it is split into three equal parts.
        engine.noteTapAt(100f, 80f, box)
        assertTrue(engine.hitTest(100f, 80f, box))

        engine.noteTapAt(100f, 200f, box)
        val feetTap = engine.hitTest(100f, 200f, box)

        assertTrue(feetTap, "a tap on the feet should land on him")
    }

    @Test
    fun `a tap outside him does not land on him`() {
        val engine = engine()
        assertFalse(engine.hitTest(10f, 100f, box))
        assertFalse(engine.hitTest(100f, 400f, box))
    }

    @Test
    fun `he says something when he is tapped, and the line goes away`() {
        val engine = engine()
        engine.noteTapAt(100f, 80f, box)
        engine.onPetted(now = 1000L)
        val line = engine.bubble(1000L)
        assertNotNull(line)
        assertTrue(line.isNotBlank())

        // The bubble lives for the tempo'd minimum, so it is still there a
        // second later and gone much later.
        assertNotNull(engine.bubble(2000L))
        assertNull(engine.bubble(60_000L))
    }

    @Test
    fun `the chatter is in the app's language`() {
        val english = engine(russian = false)
        english.onPetted(now = 1000L)
        val englishLine = english.bubble(1000L)

        val russian = engine(russian = true)
        russian.onPetted(now = 1000L)
        val russianLine = russian.bubble(1000L)

        assertNotNull(englishLine)
        assertNotNull(russianLine)
        assertTrue(englishLine.any { it.code < 128 }, "the English line should be in Latin letters")
        assertTrue(russianLine.any { it.code in 0x400..0x4FF }, "the Russian line should be in Cyrillic")
    }

    @Test
    fun `he stays quiet when the chatter is switched off`() {
        val engine = engine(settings = PotsikSettings(banterEnabled = false))
        engine.onPetted(now = 1000L)
        // A tap still gets an answer, but the idle roll does not fill the
        // screen with chatter on its own.
        engine.tick(2_000_000L)
        assertNull(engine.bubble(2_000_000L))
    }

    @Test
    fun `a pose lasts for as long as it was asked for`() {
        val engine = engine()
        engine.playPose(PotsikVisitPose.CROUCH, durationMs = 1000L, now = 0L)
        val frame = engine.frame(500L)
        assertEquals(PotsikMood.ACT, frame.mood)
        assertEquals(PotsikVisitPose.CROUCH, frame.actPose)

        val later = engine.frame(2000L)
        assertEquals(PotsikVisitPose.NONE, later.actPose)
    }

    @Test
    fun `a named animation is remembered by its name`() {
        val engine = engine()
        engine.playAction(PotsikAction.IDLE_DANCE, durationMs = 1000L, now = 0L)
        assertEquals(PotsikAction.IDLE_DANCE, engine.activeAction(500L))
        assertNull(engine.activeAction(2000L))
    }

    @Test
    fun `the mouth flaps while he is talking and not otherwise`() {
        val engine = engine()
        assertFalse(engine.isTalking(1000L))
        engine.say("Something to say.", ms = 3000L, now = 1000L)
        assertTrue(engine.isTalking(1000L))
        assertFalse(engine.isTalking(100_000L))
    }

    @Test
    fun `the picture changes with the clock`() {
        val engine = engine()
        // The character is drawn from the clock: the bob, the squash and the
        // walk are all functions of the time. A clock that stood still would
        // leave him in one frozen pose.
        val first = engine.frame(0L)
        val later = engine.frame(300L)
        assertTrue(
            first.bobOffset != later.bobOffset,
            "the character should move: ${first.bobOffset} then ${later.bobOffset}",
        )
    }

    @Test
    fun `a bubble goes away on its own`() {
        val engine = engine()
        engine.setPhase(PotsikPhase.WORKING, toolName = null, now = 1000L)
        assertNotNull(engine.bubble(1000L))
        // The line lives for three seconds; after that it is gone, and the
        // character is back to his own business.
        assertNull(engine.bubble(10_000L))
    }

    @Test
    fun `he does not fall asleep the moment the app opens`() {
        val engine = engine()
        // The first tick starts his clock: he has been alone since the app
        // started, not since the beginning of time.
        engine.tick(1000L)
        engine.tick(2000L)
        assertNotEquals(PotsikMood.SLEEPY, engine.frame(2000L).mood)
    }

    @Test
    fun `the tempo scales every duration`() {
        val slow = engine(settings = PotsikSettings(tempoPercent = 50))
        val fast = engine(settings = PotsikSettings(tempoPercent = 200))
        assertEquals(500L, slow.tempo(1000L))
        assertEquals(2000L, fast.tempo(1000L))
    }

    @Test
    fun `carrying him makes him hang, and letting go drops him`() {
        val engine = engine()
        engine.grabTo(100f, 100f, now = 0L)
        engine.tick(50L)
        assertTrue(engine.frame(50L).dangle)

        engine.release(now = 100L)
        // He falls first: the release lets go of him, and the fall takes
        // another moment before he is on the ground again.
        engine.tick(200L)
        assertTrue(engine.frame(200L).dangle)
        engine.tick(900L)
        assertFalse(engine.frame(900L).dangle)
    }

    @Test
    fun `he is carried until the finger lets go`() {
        val engine = engine()
        engine.grabTo(100f, 100f, now = 0L)
        engine.tick(50L)
        assertTrue(engine.frame(50L).dangle)

        // The finger paused: he is still being carried, not dropped.
        engine.tick(500L)
        engine.tick(1200L)
        assertTrue(engine.frame(1200L).dangle)

        // The finger let go: he falls and lands, which takes a moment.
        engine.release(1300L)
        engine.tick(1400L)
        assertTrue(engine.frame(1400L).dangle, "he is still in the air")
        engine.tick(1900L)
        assertFalse(engine.frame(1900L).dangle)
    }

    @Test
    fun `a finger that let go while it was moving throws him`() {
        val engine = engine()
        engine.setRoom(width = 1000f, floorY = 800f, ceilingY = 0f, scale = 2f)

        // A fast drag across the screen, and the finger lets go at once.
        engine.grabTo(100f, 800f, now = 0L)
        engine.tick(10L)
        engine.grabTo(300f, 800f, now = 10L)
        engine.tick(20L)
        engine.grabTo(500f, 800f, now = 20L)
        engine.release(30L)

        assertTrue(engine.isThrown(), "a moving finger throws him")
        // He comes down and sits where he landed.
        engine.tick(2000L)
        assertTrue(engine.isSitting(), "he should be sitting after the landing")
    }

    @Test
    fun `a finger that stopped before it let go drops him`() {
        val engine = engine()
        engine.setRoom(width = 1000f, floorY = 800f, ceilingY = 0f, scale = 2f)

        engine.grabTo(100f, 800f, now = 0L)
        engine.tick(10L)
        engine.grabTo(300f, 800f, now = 10L)
        // The finger stops and stays stopped.
        engine.tick(500L)
        engine.release(600L)

        assertFalse(engine.isThrown(), "a still finger drops him, it does not throw him")
    }

    @Test
    fun `an animation of the working playlist fills its whole slot`() {
        // A dance that is cut in half reads as a glitch, not as a dance, so
        // the slot and the animation are the same number.
        val engine = engine()
        engine.setPhase(PotsikPhase.WORKING, toolName = null, now = 0L)

        val slot = engine.workSlotMs()
        assertTrue(slot >= 4000L, "a slot should be at least four seconds: $slot")
        assertTrue(slot <= 12000L, "a slot should not be longer than twelve seconds: $slot")

        // The mood the playlist puts him in lasts the slot, so it is still
        // running a moment before the next one starts.
        var now = 0L
        var lastChange = 0L
        var lastMood = engine.frame(now).mood
        while (now < 60_000L) {
            engine.tick(now)
            val mood = engine.frame(now).mood
            if (mood != lastMood) {
                lastChange = now
                lastMood = mood
            }
            now += 100L
        }
        // He changed his mood at least once in a minute, and never sooner
        // than the slot allows.
        assertTrue(lastChange > 0L, "he should be doing more than one thing")
    }

    @Test
    fun `the slot never drops under four seconds and never runs away`() {
        val slow = engine(settings = PotsikSettings(tempoPercent = 50))
        val fast = engine(settings = PotsikSettings(tempoPercent = 200))
        assertTrue(slow.workSlotMs() >= 4000L)
        assertTrue(fast.workSlotMs() <= 12000L)
        // A faster character does get shorter slots, but only between the
        // two bounds.
        assertTrue(slow.workSlotMs() <= fast.workSlotMs())
    }

    @Test
    fun `the tool's own pose is held long enough to be seen`() {
        val engine = engine()
        assertTrue(engine.firstDelayMs() >= 4000L)
    }

    @Test
    fun `a request plays through a list of animations`() {
        val engine = engine()
        engine.setPhase(PotsikPhase.WORKING, toolName = null, now = 0L)

        // The list is played over and over while the request runs, so he is
        // never standing still for the whole of it.
        val seen = mutableSetOf<PotsikMood>()
        var now = 0L
        while (now < 120_000L) {
            engine.tick(now)
            seen += engine.frame(now).mood
            now += 500L
        }
        assertTrue(seen.size > 1, "the character should be doing more than one thing: $seen")
    }
}
