package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The log as the row shows it: the end of the file, and the panel's own password
 * cut out of it. Both are pure functions, so the phone's own file reads only have
 * to hand the text over.
 */
class WebPanelLogTest {

    @Test
    fun `the tail is the end of the file, not the whole of it`() {
        val log = "first line\n" + "x".repeat(WebPanelLog.TAIL_CHARS) + "\nlast line"

        val tail = WebPanelLog.tail(log)

        assertFalse(tail.contains("first line"), "the beginning is what gets dropped")
        assertTrue(tail.contains("last line"), "the last thing the panel said is the point")
        assertTrue(tail.length <= WebPanelLog.TAIL_CHARS, "the row is not handed the whole file")
    }

    @Test
    fun `a log shorter than the tail is returned whole`() {
        assertEquals("hello\nworld", WebPanelLog.tail("\nhello\nworld\n\n"))
    }

    @Test
    fun `an empty log is said, not left as a blank pane`() {
        assertEquals(WebPanelLog.EMPTY, WebPanelLog.tail(""))
        assertEquals(WebPanelLog.EMPTY, WebPanelLog.tail("  \n\t\n "))
    }

    @Test
    fun `the password is masked wherever it appears`() {
        val password = "Xq7Rz2LmNp4Tv8Wk"
        val log = "env: PASSWORD=$password\nlistening on 4096\nretry with $password"

        val shown = WebPanelLog.redact(log, password)

        assertFalse(shown.contains(password), "the password reached the screen: $shown")
        assertEquals(2, Regex("\\*\\*\\*").findAll(shown).count())
        assertTrue(shown.contains("listening on 4096"), "the rest of the log is kept as it was")
    }

    @Test
    fun `nothing to mask leaves the log alone`() {
        assertEquals("no password here", WebPanelLog.redact("no password here", null))
        assertEquals("no password here", WebPanelLog.redact("no password here", ""))
        // Masking a one-character password would turn the log into asterisks.
        assertEquals("ab", WebPanelLog.redact("ab", "a"))
    }
}
