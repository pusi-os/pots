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

    @Test
    fun `a password cut by the tail boundary never comes back as a fragment`() {
        // 13010 characters total, so the 8000-character tail starts at 5010 — ten
        // characters into a password that begins at 5000. Masking after the cut
        // would return the rest of it, which is a leak all the same.
        val password = "Xq7Rz2LmNp4Tv8Wk"
        val raw = "y".repeat(5_000) + password + "z".repeat(13_010 - 5_000 - password.length)

        val shown = WebPanelLog.show(raw, password)

        assertFalse(shown.contains(password.drop(10)), "the rest of the password survived the cut")
        assertFalse(shown.contains(password))
        assertTrue(shown.contains("***"))
        assertTrue(shown.length <= WebPanelLog.TAIL_CHARS)
    }

    @Test
    fun `the row is shown the masked tail, not the whole file`() {
        val password = "Xq7Rz2LmNp4Tv8Wk"
        val raw = "starting\n" + "x".repeat(WebPanelLog.TAIL_CHARS) + "\nenv: PASSWORD=" + password

        val shown = WebPanelLog.show(raw, password)

        assertFalse(shown.contains("starting"), "the beginning is what gets dropped")
        assertFalse(shown.contains(password))
        assertTrue(shown.contains("***"))
    }

    @Test
    fun `a failure reason quotes the last thing the panel said`() {
        val log = "starting the server\nlistening on 4096\n\npermission denied\n\n"

        assertEquals("permission denied", WebPanelLog.brief(log, null))
    }

    @Test
    fun `a failure reason masks the password too`() {
        val password = "Xq7Rz2LmNp4Tv8Wk"
        val log = "env: PASSWORD=$password\nlistening failed"

        val reason = WebPanelLog.brief(log, password)

        assertFalse(reason.contains(password), "the password reached the status line: $reason")
        assertEquals("listening failed", reason)
    }

    @Test
    fun `a failure reason stays one short line`() {
        val reason = WebPanelLog.brief("head\n" + "e".repeat(5_000), null)

        assertEquals(WebPanelLog.BRIEF_CHARS + 1, reason.length)
        assertTrue(reason.startsWith("…"), "the front of the line is what gets dropped")
    }

    @Test
    fun `a failure reason says so when there is no log`() {
        assertEquals(WebPanelLog.NO_FILE, WebPanelLog.brief(WebPanelLog.NO_FILE, null))
        assertEquals(WebPanelLog.EMPTY, WebPanelLog.brief(" \n\t\n", null))
    }
}
