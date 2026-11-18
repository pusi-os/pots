package com.pusios.pots.ui.potsik

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screen mode as a state machine, driven by a fake projector.
 *
 * The rules being checked are the ones a phone would only show once: consent is
 * asked for once per mode and not on every tap, the token is spent when the
 * mode is left, the system can take the projection back at any moment, and a
 * failure never costs the user their draft.
 */
class PotsikScreenModeTest {

    /** A projector that answers by hand: a session, and the frames it will give. */
    private class FakeProjector : PotsikScreenProjector {

        var active = false
        var captures = 0
        var stops = 0
        val frames = ArrayDeque<PotsikShot?>()

        override val sessionActive: Boolean get() = active

        override suspend fun capture(): PotsikShot? {
            captures++
            return frames.removeFirstOrNull()
        }

        override fun stop() {
            stops++
            active = false
        }
    }

    private fun shot(width: Int = 1080, height: Int = 2400) = PotsikShot(width, height, ByteArray(4))

    @Test
    fun `consent is asked once, then frames are taken without asking again`() = runTest {
        val projector = FakeProjector()
        val mode = PotsikScreenMode(projector)

        // No session: the dialog has to answer first.
        assertEquals(PotsikScreenStep.AskConsent, mode.tap())
        assertEquals(PotsikScreenPhase.AwaitingConsent, mode.state.value.phase)
        assertEquals(0, projector.captures)

        // The dialog said yes and the platform brought the session up.
        projector.active = true
        projector.frames += shot()
        mode.onConsentGranted()
        assertEquals(PotsikScreenPhase.Shot, mode.state.value.phase)
        assertNotNull(mode.state.value.shot)

        // A second tap inside the same mode: a frame, no dialog.
        projector.frames += shot()
        assertEquals(PotsikScreenStep.Captured, mode.tap())
        assertEquals(2, projector.captures)
        assertEquals(PotsikScreenPhase.Shot, mode.state.value.phase)
    }

    @Test
    fun `leaving the mode spends the token and the next entry asks again`() = runTest {
        val projector = FakeProjector()
        val mode = PotsikScreenMode(projector)
        projector.active = true
        projector.frames += shot()
        mode.onConsentGranted()

        mode.exit()

        assertEquals(1, projector.stops)
        assertEquals(PotsikScreenPhase.Off, mode.state.value.phase)
        assertNull(mode.state.value.shot)
        // The token is one-shot: without a new consent there is no session.
        assertEquals(PotsikScreenStep.AskConsent, mode.tap())
        assertEquals(PotsikScreenPhase.AwaitingConsent, mode.state.value.phase)
    }

    @Test
    fun `a refused consent is an error, and the mode does not pretend otherwise`() = runTest {
        val projector = FakeProjector()
        val mode = PotsikScreenMode(projector)
        mode.tap()

        mode.onConsentDenied()

        assertEquals(PotsikScreenPhase.Failed, mode.state.value.phase)
        assertEquals(PotsikCloudTrouble.ScreenConsentDenied, mode.state.value.trouble)
        assertEquals(0, projector.captures)
    }

    @Test
    fun `a revoked projection keeps the draft and says why`() = runTest {
        val projector = FakeProjector()
        val mode = PotsikScreenMode(projector)
        projector.active = true
        projector.frames += shot()
        mode.onConsentGranted()

        // The user was typing the question when the screen locked.
        PotsikCloudDraft.set("что тут на экране")
        mode.onRevoked()

        assertEquals(PotsikScreenPhase.Failed, mode.state.value.phase)
        assertEquals(PotsikCloudTrouble.ScreenConsentRevoked, mode.state.value.trouble)
        assertEquals("что тут на экране", PotsikCloudDraft.text.value, "the draft is not the screen mode's to throw away")
        PotsikCloudDraft.clear()
    }

    @Test
    fun `an empty frame is a failure, not an empty screenshot`() = runTest {
        val projector = FakeProjector()
        val mode = PotsikScreenMode(projector)
        projector.active = true
        // The mirror had nothing to give.
        projector.frames += null

        mode.onConsentGranted()

        assertEquals(PotsikScreenPhase.Failed, mode.state.value.phase)
        assertEquals(PotsikCloudTrouble.ScreenCaptureFailed, mode.state.value.trouble)
    }

    @Test
    fun `removing the frame keeps the session alive`() = runTest {
        val projector = FakeProjector()
        val mode = PotsikScreenMode(projector)
        projector.active = true
        projector.frames += shot()
        mode.onConsentGranted()

        mode.clearShot()

        assertEquals(PotsikScreenPhase.Off, mode.state.value.phase)
        assertEquals(0, projector.stops)
        // The mode is still on: the next tap takes another frame without a dialog.
        projector.frames += shot()
        assertEquals(PotsikScreenStep.Captured, mode.tap())
        assertEquals(2, projector.captures)
    }

    @Test
    fun `a session that never came up is a failure`() = runTest {
        val projector = FakeProjector()
        val mode = PotsikScreenMode(projector)
        mode.tap()

        mode.onSessionFailed()

        assertEquals(PotsikScreenPhase.Failed, mode.state.value.phase)
        assertEquals(PotsikCloudTrouble.ScreenCaptureFailed, mode.state.value.trouble)
    }

    @Test
    fun `the screen troubles are the ones retry re-asks for`() {
        assertTrue(PotsikCloudTrouble.ScreenConsentDenied.isScreenTrouble())
        assertTrue(PotsikCloudTrouble.ScreenConsentRevoked.isScreenTrouble())
        assertTrue(PotsikCloudTrouble.ScreenCaptureFailed.isScreenTrouble())
        assertFalse(PotsikCloudTrouble.NoNetwork.isScreenTrouble())
        assertFalse(PotsikCloudTrouble.NoMatch.isScreenTrouble())
        assertFalse(null.isScreenTrouble())
    }
}
