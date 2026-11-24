// Clipboard wiper tests (card 2/5, review round 2): the wipe timer must
// outlive the composable that started the copy, and a second copy must
// supersede the first timer instead of letting it wipe the newer value early.
// Both are pure coroutine-scope properties, so the test drives the real wiper
// class with virtual time — no composition involved, exactly the situation
// after the user has left the screen.

package com.pusios.pots.ui.vault

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class VaultClipboardWiperTest {

    @Test
    fun `wipe survives leaving the screen and the newest copy supersedes the old timer`() = runTest {
        val clipboard = mutableListOf<String>()
        val sink = VaultClipboardSink { text -> clipboard.add(text) }
        val wiper = VaultClipboardWiper(clearAfter = 45.seconds, scope = backgroundScope)

        wiper.copy("s3cret-42", sink)
        runCurrent()
        assertEquals(listOf("s3cret-42"), clipboard)

        // The composable leaves composition here. Nothing cancels the timer:
        // it lives in the wiper's own scope, not in a rememberCoroutineScope.
        advanceTimeBy(44_000)
        runCurrent()
        assertEquals(listOf("s3cret-42"), clipboard)

        // A second copy 44 s later cancels the first timer and starts its own.
        wiper.copy("newer-1", sink)
        runCurrent()
        assertEquals(listOf("s3cret-42", "newer-1"), clipboard)

        // t=45 s: the old deadline passes — the superseded timer must not have
        // wiped the clipboard (that was the second bug of the row-scoped scope).
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("s3cret-42", "newer-1"), clipboard)

        // t=89 s: the new timer wipes.
        advanceTimeBy(44_000)
        runCurrent()
        assertEquals(listOf("s3cret-42", "newer-1", ""), clipboard)
    }
}
