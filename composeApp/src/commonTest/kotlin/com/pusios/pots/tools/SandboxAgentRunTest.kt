package com.pusios.pots.tools

import com.pusios.pots.build.BuildAgents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ask_sandbox_agent` spends the user's tokens on a real agent, so nothing here may
 * reach one: these tests drive the parts that decide what gets run and what gets
 * returned — the argv per agent, the shell quoting, and the clipping of a long answer.
 */
class SandboxAgentRunTest {

    private val opencode = BuildAgents.get(BuildAgents.OPENCODE)!!
    private val pi = BuildAgents.get(BuildAgents.PI)!!
    private val hermes = BuildAgents.get(BuildAgents.HERMES)!!

    // --- argv -------------------------------------------------------------

    @Test
    fun `each agent gets its own one-shot invocation`() {
        assertEquals(listOf("opencode", "run", "fix the build"), SandboxAgentRun.argv(opencode, "fix the build"))
        assertEquals(listOf("pi", "-p", "fix the build"), SandboxAgentRun.argv(pi, "fix the build"))
        assertEquals(listOf("hermes", "-z", "fix the build"), SandboxAgentRun.argv(hermes, "fix the build"))
    }

    @Test
    fun `continuing a session adds -c to OpenCode and to nothing else`() {
        assertEquals(
            listOf("opencode", "run", "-c", "and now the tests"),
            SandboxAgentRun.argv(opencode, "and now the tests", continueSession = true),
        )
        // The flag goes before the message, or the CLI would read it as part of it.
        assertEquals("and now the tests", SandboxAgentRun.argv(opencode, "and now the tests", true).last())

        assertEquals(listOf("pi", "-p", "and now the tests"), SandboxAgentRun.argv(pi, "and now the tests", true))
        assertEquals(listOf("hermes", "-z", "and now the tests"), SandboxAgentRun.argv(hermes, "and now the tests", true))
    }

    // --- shell quoting ----------------------------------------------------

    @Test
    fun `a task that reads like a command stays one argument`() {
        val task = "x'; rm -rf / #\n`id` \$(whoami) \\ --flag"
        val line = SandboxAgentRun.commandLine(SandboxAgentRun.argv(opencode, task))

        // Nothing survives outside quotes, so the shell has no metacharacter left to act on.
        assertEquals("", unquotedNonSpace(line))
        // And what the shell would hand the agent is exactly the argv we built.
        assertEquals(listOf("opencode", "run", task), shellWords(line))
    }

    @Test
    fun `every argument is quoted, including plain ones`() {
        assertEquals("'opencode' 'run' 'hello'", SandboxAgentRun.commandLine(listOf("opencode", "run", "hello")))
        assertEquals("'it'\\''s'", SandboxAgentRun.shellQuote("it's"))
        assertEquals("''", SandboxAgentRun.shellQuote(""))
    }

    @Test
    fun `the run script sends both streams to files and keeps the exit code`() {
        val script = SandboxAgentRun.runScript("'opencode' 'run' 'task'", "/tmp/out", "/tmp/err")
        val lines = script.trim().lines()

        assertEquals(3, lines.size)
        assertEquals(": > '/tmp/out'", lines[0])
        assertEquals(": > '/tmp/err'", lines[1])
        // Last statement: the script's exit status is the agent's own, and the
        // executor's pipes stay empty so its head-only capture cannot eat the answer.
        assertEquals("'opencode' 'run' 'task' > '/tmp/out' 2> '/tmp/err'", lines[2])
    }

    // --- clipping ---------------------------------------------------------

    @Test
    fun `short output is returned untouched`() {
        val short = "Done: 3 files changed."
        assertEquals(short, SandboxAgentRun.tail(short, 8_000).text)
        assertFalse(SandboxAgentRun.tail(short, 8_000).truncated)
        assertFalse(SandboxAgentRun.head(short, 8_000).truncated)
    }

    @Test
    fun `a long answer keeps its end, within the byte budget`() {
        val long = (1..5_000).joinToString("\n") { "line $it" }
        val clipped = SandboxAgentRun.tail(long, 1_000)

        assertTrue(clipped.truncated)
        assertTrue(clipped.text.encodeToByteArray().size <= 1_000)
        assertTrue(long.endsWith(clipped.text), "the tail has to be the end of the output")
        assertTrue(clipped.text.contains("line 5000"), "the conclusion is at the end")
    }

    @Test
    fun `cyrillic is cut between characters, never inside one`() {
        val text = "готово: файл переписан. ".repeat(400)
        val clipped = SandboxAgentRun.tail(text, 333)

        assertTrue(clipped.truncated)
        assertTrue(clipped.text.encodeToByteArray().size <= 333)
        assertTrue(text.endsWith(clipped.text))
        assertEquals(clipped.text, clipped.text.encodeToByteArray().decodeToString(), "a broken UTF-8 sequence survived")
    }

    @Test
    fun `a four-byte character is not split in half`() {
        val text = "🙂".repeat(100)
        val clipped = SandboxAgentRun.tail(text, 10)

        assertEquals("🙂🙂", clipped.text)
        assertTrue(clipped.truncated)
        // A lone surrogate would come back as '?' from the UTF-8 round-trip.
        assertEquals(clipped.text, clipped.text.encodeToByteArray().decodeToString())

        // Less room than one character: nothing is shown rather than half a glyph.
        assertEquals("", SandboxAgentRun.tail(text, 3).text)
        assertTrue(SandboxAgentRun.tail(text, 3).truncated)
    }

    @Test
    fun `the head is kept for a failure, and cut the same way`() {
        val text = "error: no such file\n".repeat(100)
        val clipped = SandboxAgentRun.head(text, 100)

        assertTrue(clipped.truncated)
        assertTrue(clipped.text.encodeToByteArray().size <= 100)
        assertTrue(text.startsWith(clipped.text))
        assertTrue(clipped.text.startsWith("error: no such file"))

        // A two-byte letter does not fit in one byte, so it is dropped whole.
        assertEquals("а", SandboxAgentRun.head("аб", 3).text)
    }

    @Test
    fun `clipping is announced, not silent`() {
        val long = "x".repeat(500)

        val answer = SandboxAgentRun.answer(long, emptyList(), 100)
        assertTrue(answer.startsWith("[Output truncated: showing the last 100 bytes]"))
        assertTrue(answer.endsWith("x".repeat(100)))

        val failure = SandboxAgentRun.failureDetail(long, "", emptyList(), 100)
        assertTrue(failure.startsWith("[Output truncated: showing the first 100 bytes]"))

        // Nothing to announce when nothing was cut.
        assertEquals("all of it", SandboxAgentRun.answer("all of it\n", emptyList(), 100))
        assertEquals("all of it", SandboxAgentRun.failureDetail("  all of it  ", "", emptyList(), 100))
    }

    // --- what comes back, and under which name ----------------------------

    @Test
    fun `a successful run never repeats its answer as a warning`() {
        // The answer lives in stdout; a run that says nothing on stderr has nothing to
        // add. Feeding stdout in here is exactly the defect: the same text twice, the
        // second time under a name that claims it is a warning.
        assertEquals("", SandboxAgentRun.successNote("", emptyList(), 2_000))
        assertEquals("", SandboxAgentRun.successNote("  \n\t ", emptyList(), 2_000))
    }

    @Test
    fun `warnings from a successful run are its own stderr, from the start`() {
        val stderr = "warning: unknown flag --foo\nfalling back to the default model"

        assertEquals(stderr, SandboxAgentRun.successNote(stderr, emptyList(), 2_000))

        val clipped = SandboxAgentRun.successNote("w".repeat(5_000), emptyList(), 100)
        assertTrue(clipped.startsWith("[Output truncated: showing the first 100 bytes]"))
    }

    @Test
    fun `a failed run with silent stderr falls back to the start of stdout`() {
        val stdout = "error: no such file: src/Main.kt\n" + "noise ".repeat(1_000)
        val detail = SandboxAgentRun.failureDetail("", stdout, emptyList(), 100)

        // The fallback still lives — an agent that prints its complaint to stdout must
        // not come back as an unexplained failure.
        assertTrue(detail.contains("error: no such file"))
        assertTrue(detail.startsWith("[Output truncated: showing the first 100 bytes]"))
    }

    @Test
    fun `a failed run with stderr of its own does not mix stdout in`() {
        val detail = SandboxAgentRun.failureDetail("boom: 401 unauthorized", "the whole answer", emptyList(), 2_000)

        assertEquals("boom: 401 unauthorized", detail)
        assertFalse(detail.contains("the whole answer"))
    }

    // --- secrets ----------------------------------------------------------

    @Test
    fun `the provider key never comes back in the output`() {
        val key = "sk-live-9f2c4b"
        val text = "auth: Bearer $key\nretry with $key if it fails"

        val redacted = SandboxAgentRun.redact(text, listOf(key))

        assertFalse(redacted.contains(key))
        assertTrue(redacted.contains(SandboxAgentRun.REDACTED))
        assertEquals("auth: Bearer [redacted]", SandboxAgentRun.redact("auth: Bearer $key", listOf(key)))
    }

    @Test
    fun `a blank secret leaves the text alone`() {
        val text = "nothing to hide"
        assertEquals(text, SandboxAgentRun.redact(text, listOf("", "   ")))
    }

    @Test
    fun `a key cut by the tail boundary never comes back as a fragment`() {
        // The cut lands ten characters inside the key, so clipping first and redacting
        // afterwards would return the rest of it — a leak all the same.
        val key = "sk-proj-00001111222233334444555566667777"
        // 13010 characters total, so the 8000-byte tail starts at 5010 — ten
        // characters into a key that begins at 5000.
        val raw = "y".repeat(5_000) + key + "z".repeat(13_010 - 5_000 - key.length)
        val answer = SandboxAgentRun.answer(raw, listOf(key), 8_000)

        assertTrue(answer.startsWith("[Output truncated: showing the last 8000 bytes]"))
        assertFalse(answer.contains(key.take(8)), "the start of the key survived the cut")
        assertFalse(answer.contains(key.takeLast(8)), "the end of the key survived the cut")
        assertFalse(answer.contains(key))
        assertTrue(answer.contains(SandboxAgentRun.REDACTED))
    }

    @Test
    fun `a key cut by the head boundary never comes back as a fragment`() {
        val key = "sk-proj-00001111222233334444555566667777"
        // head(2000) cuts ten characters into the key: the first ten would survive.
        val raw = "y".repeat(1_990) + key + "z".repeat(500)
        val detail = SandboxAgentRun.failureDetail(raw, "", listOf(key), 2_000)

        assertFalse(detail.contains(key.take(8)), "the start of the key survived the cut")
        assertFalse(detail.contains(key.takeLast(8)), "the end of the key survived the cut")
        assertTrue(detail.contains(SandboxAgentRun.REDACTED))
    }

    // --- not installed ----------------------------------------------------

    @Test
    fun `a missing agent is named, and points at the screen that installs it`() {
        val message = SandboxAgentRun.notInstalled("OpenCode")

        assertTrue(message.contains("OpenCode"))
        assertTrue(message.contains("not installed"))
        assertTrue(message.contains("Settings"))
        assertTrue(message.contains("Sandbox"))
    }

    // --- helpers ----------------------------------------------------------

    /** Characters `/bin/sh` would act on: everything the command line leaves unquoted. */
    private fun unquotedNonSpace(line: String): String = buildString {
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuote -> if (c == '\'') inQuote = false else Unit
                c == '\\' && i + 1 < line.length -> i++
                c == '\'' -> inQuote = true
                c != ' ' -> append(c)
            }
            i++
        }
    }

    /** Splits [line] the way `/bin/sh` would, for the subset [SandboxAgentRun.shellQuote] emits. */
    private fun shellWords(line: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var started = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuote -> {
                    if (c == '\'') inQuote = false else current.append(c)
                    i++
                }

                c == '\\' && i + 1 < line.length -> {
                    current.append(line[i + 1])
                    started = true
                    i += 2
                }

                c == '\'' -> {
                    inQuote = true
                    started = true
                    i++
                }

                c == ' ' -> {
                    if (started) {
                        words += current.toString()
                        current.clear()
                        started = false
                    }
                    i++
                }

                else -> {
                    current.append(c)
                    started = true
                    i++
                }
            }
        }
        if (started) words += current.toString()
        return words
    }
}
