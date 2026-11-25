// AdbGate's own behaviour, without a chat UI: a headless run is refused before any dialog, an
// unanswered request is refused when the window closes, and the dialog's answer reaches the call
// that is waiting for it.

package com.pusios.pots.adb

import com.pusios.pots.data.HeadlessRunElement
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class AdbGateTest {

    @Test
    fun headlessRunIsRefusedWithoutAsking() = runTest {
        val gate = AdbGate()
        // No chat surface to confirm in, so the refusal is immediate, not a timeout wait.
        val allowed = withTimeout(1.seconds) {
            withContext(HeadlessRunElement()) { gate.confirm("adb_install", "Install an APK") }
        }
        assertFalse(allowed)
        assertNull(gate.pending.value)
    }

    @Test
    fun noAnswerWithinTheWindowIsRefused() = runTest {
        val gate = AdbGate(confirmTimeout = 2.seconds)
        assertFalse(gate.confirm("adb_uninstall", "Uninstall com.example.app"))
        assertNull(gate.pending.value)
    }

    @Test
    fun theDialogsAnswerIsDeliveredToTheWaitingCall() = runTest {
        val gate = AdbGate()
        val call = async { gate.confirm("adb_shell", "Run: settings put global x 1") }
        runCurrent()
        val request = assertNotNull(gate.pending.value, "the call must publish a confirmation request")
        gate.respond(request.id, allowed = true)
        assertEquals(true, call.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun anAnswerToAnotherRequestIsIgnored() = runTest {
        val gate = AdbGate()
        val call = async { gate.confirm("adb_uninstall", "Uninstall com.example.app") }
        runCurrent()
        gate.respond("not-the-request-id", allowed = true)
        assertNotNull(gate.pending.value, "a stale answer must not clear the pending request")
        gate.respond(assertNotNull(gate.pending.value).id, allowed = false)
        assertEquals(false, call.await())
    }
}
