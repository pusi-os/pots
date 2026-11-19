package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallLogTest {

    private fun InstallLog.feed(text: String) = appendText(text)

    @Test
    fun `carriage returns end a line like newlines`() {
        val log = InstallLog()
        // git and uv redraw progress with \r; without splitting on it the whole
        // download would be one line and the screen would show only its end.
        log.feed("Receiving objects: 41%\rReceiving objects: 82%\rReceiving objects: 100%\n")
        assertEquals(
            "Receiving objects: 41%\nReceiving objects: 82%\nReceiving objects: 100%\n",
            log.text(),
        )
    }

    @Test
    fun `an unfinished line is shown while it is being written`() {
        val log = InstallLog()
        log.feed("Installing dependencies")
        assertEquals("Installing dependencies", log.text())
        log.feed("...\n")
        assertEquals("Installing dependencies...\n", log.text())
    }

    @Test
    fun `blank progress redraws are not kept`() {
        val log = InstallLog()
        log.feed("a\r\r\n\n \nb\n")
        assertEquals("a\nb\n", log.text())
    }

    @Test
    fun `ansi colour and cursor sequences are stripped`() {
        val log = InstallLog()
        log.feed("\u001b[1A\u001b[2K\u001b[32m✓ venv\u001b[0m\n")
        assertEquals("✓ venv\n", log.text())
    }

    @Test
    fun `only the tail is kept and the screen is told it was cut`() {
        val log = InstallLog(maxChars = 40)
        repeat(20) { log.feed("line $it\n") }
        val text = log.text()
        assertTrue(log.dropped, "the head must be reported as dropped")
        assertTrue(text.length <= 40, "buffer grew past its cap: ${text.length}")
        assertTrue(text.endsWith("line 19\n"), "the newest line must survive: $text")
        assertFalse(text.contains("line 0\n"), "the oldest line must be gone: $text")
    }

    @Test
    fun `a line without any terminator cannot push out the buffer`() {
        val log = InstallLog(maxChars = 40)
        log.feed("kept\n")
        log.feed("x".repeat(5_000))
        val text = log.text()
        assertTrue(text.startsWith("kept\n") || log.dropped)
        assertTrue(text.length <= 40 + 200, "buffer grew past its cap: ${text.length}")
    }
}
