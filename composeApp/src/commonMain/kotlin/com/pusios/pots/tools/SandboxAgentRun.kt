package com.pusios.pots.tools

import com.pusios.pots.build.BuildAgent
import com.pusios.pots.build.BuildAgents

/**
 * The half of `ask_sandbox_agent` that can be reasoned about without a rootfs: how a
 * task becomes a command line, how a long answer is clipped, and how the provider key
 * is kept out of what comes back.
 *
 * It lives in commonMain because the tool itself needs a live proot and is only
 * compiled for Android, while every decision worth testing here is pure.
 */
object SandboxAgentRun {

    /** An excerpt, with [truncated] telling the caller that the rest is gone. */
    data class Clipped(val text: String, val truncated: Boolean)

    /** Stands in for a secret in anything that leaves the sandbox. */
    const val REDACTED = "[redacted]"

    /**
     * The one-shot invocation for [agent]. Flags come before the task text so the
     * message is always the last argument — `opencode run -c "…"`, never
     * `opencode run "…" -c`, which the CLI would read as part of the message.
     */
    fun argv(agent: BuildAgent, task: String, continueSession: Boolean = false): List<String> = when (agent.id) {
        BuildAgents.OPENCODE -> buildList {
            add(agent.binary)
            add("run")
            if (continueSession) add("-c")
            add(task)
        }

        BuildAgents.PI -> listOf(agent.binary, "-p", task)

        BuildAgents.HERMES -> listOf(agent.binary, "-z", task)

        // Refusing beats inventing a flag: the caller validates the id against the
        // catalog first, so reaching this is a programming error, not a model's.
        else -> throw IllegalArgumentException("${agent.id} has no known one-shot command")
    }

    /** [argv] as a single `/bin/sh` line. */
    fun commandLine(argv: List<String>): String = argv.joinToString(" ") { shellQuote(it) }

    /**
     * One argument exactly as `/bin/sh` reads it back: single quotes stop every
     * expansion — `$`, backticks, `;`, newlines — and the one character they cannot
     * hold, a quote, is closed, escaped and reopened. A task of `'; rm -rf /` stays
     * a piece of the task text instead of ending the command.
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * The script the sandbox runs.
     *
     * The agent's own streams go to [outFile] and [errFile] rather than to the
     * executor's pipes: the executor keeps the FIRST 15k characters it reads and
     * discards the rest, which for a chatty agent is its banner and not its answer.
     * Writing to files lets the caller read the end of the output afterwards.
     *
     * The run is the last statement, so the script's exit status is the agent's own.
     */
    fun runScript(command: String, outFile: String, errFile: String): String = buildString {
        appendLine(": > ${shellQuote(outFile)}")
        appendLine(": > ${shellQuote(errFile)}")
        appendLine("$command > ${shellQuote(outFile)} 2> ${shellQuote(errFile)}")
    }

    /**
     * The agent's answer as the model should see it: the end of its output, marked
     * when clipped. The tail is what matters — an agent's conclusion is at the end
     * and its progress log at the start.
     *
     * The secret is cut out BEFORE the clipping, not after: a key straddling the cut
     * would otherwise come back as half of itself, which is still a leak. The caller
     * reads a few bytes more than it shows, so the whole key is inside the window.
     */
    fun answer(raw: String, secrets: List<String>, maxBytes: Int): String = withNotice(tail(redact(raw, secrets), maxBytes), maxBytes, "last")

    /**
     * A failed run's explanation: the start of its own stderr, or the start of stdout
     * when stderr says nothing (a CLI that prints its error to stdout is common).
     * The fallback belongs here and only here — on a successful run, stdout is the
     * answer and must not be repeated under a second name.
     */
    fun failureDetail(stderr: String, stdout: String, secrets: List<String>, maxBytes: Int): String = withNotice(head(redact(stderr.ifBlank { stdout }, secrets), maxBytes), maxBytes, "first")

    /**
     * Warnings from a successful run: its own stderr and nothing else. Silence stays
     * silence, so a quiet agent does not get its own answer quoted back as a warning.
     */
    fun successNote(stderr: String, secrets: List<String>, maxBytes: Int): String = withNotice(head(redact(stderr, secrets), maxBytes), maxBytes, "first")

    /** The last [maxBytes] of [text], cut on a character boundary. */
    fun tail(text: String, maxBytes: Int): Clipped = clip(text, maxBytes, fromEnd = true)

    /** The first [maxBytes] of [text], cut on a character boundary. */
    fun head(text: String, maxBytes: Int): Clipped = clip(text, maxBytes, fromEnd = false)

    /**
     * [text] with every occurrence of a secret in [secrets] replaced. A blank secret
     * is skipped: replacing an empty string would put a marker between every letter.
     */
    fun redact(text: String, secrets: List<String>): String {
        var out = text
        for (secret in secrets) {
            if (secret.isBlank()) continue
            out = out.replace(secret, REDACTED)
        }
        return out
    }

    /**
     * What a user is told when the agent they asked for is not there. Naming the
     * agent and the screen that installs it is the whole message: silently running
     * a different agent would answer a question nobody asked.
     */
    fun notInstalled(title: String): String = "$title is not installed in the sandbox. Install it in Settings → Sandbox."

    private fun withNotice(clipped: Clipped, maxBytes: Int, which: String): String {
        val text = clipped.text.trim()
        if (!clipped.truncated) return text
        return "[Output truncated: showing the $which $maxBytes bytes]\n$text"
    }

    private fun clip(text: String, maxBytes: Int, fromEnd: Boolean): Clipped {
        if (maxBytes <= 0) return Clipped("", text.isNotEmpty())
        if (text.encodeToByteArray().size <= maxBytes) return Clipped(text, false)
        val kept = if (fromEnd) keepTail(text, maxBytes) else keepHead(text, maxBytes)
        return Clipped(kept, true)
    }

    /**
     * Walks back from the end one code point at a time so a cut never lands inside
     * a character: half an emoji or half a Cyrillic letter is not text, and a lone
     * surrogate is not even representable.
     */
    private fun keepTail(text: String, maxBytes: Int): String {
        var bytes = 0
        var start = text.length
        while (start > 0) {
            val point = codePointEndingAt(text, start)
            if (bytes + point.bytes > maxBytes) break
            bytes += point.bytes
            start = point.start
        }
        // A `tail -c` that cut inside a character leaves the decoder's replacement
        // mark where the bytes it could not read were; it is not part of the answer.
        while (start < text.length && text[start] == REPLACEMENT_CHAR) start++
        return text.substring(start)
    }

    private fun keepHead(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val point = codePointStartingAt(text, end)
            if (bytes + point.bytes > maxBytes) break
            bytes += point.bytes
            end += point.chars
        }
        return text.substring(0, end)
    }

    /** A code point's UTF-16 length and its UTF-8 length — they differ for everything non-ASCII. */
    private class CodePoint(val start: Int, val chars: Int, val bytes: Int)

    private fun codePointEndingAt(text: String, end: Int): CodePoint {
        val last = text[end - 1]
        if (last.isLowSurrogate() && end > 1 && text[end - 2].isHighSurrogate()) {
            return CodePoint(end - 2, chars = 2, bytes = 4)
        }
        return CodePoint(end - 1, chars = 1, bytes = utf8Width(last))
    }

    private fun codePointStartingAt(text: String, start: Int): CodePoint {
        val first = text[start]
        if (first.isHighSurrogate() && start + 1 < text.length && text[start + 1].isLowSurrogate()) {
            return CodePoint(start, chars = 2, bytes = 4)
        }
        return CodePoint(start, chars = 1, bytes = utf8Width(first))
    }

    /** Bytes [char] takes in UTF-8. A lone surrogate is encoded as one replacement byte. */
    private fun utf8Width(char: Char): Int = when {
        char.isSurrogate() -> 1
        char.code < 0x80 -> 1
        char.code < 0x800 -> 2
        else -> 3
    }

    private const val REPLACEMENT_CHAR = '\uFFFD'
}
