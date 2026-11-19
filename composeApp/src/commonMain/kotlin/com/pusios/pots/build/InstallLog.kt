package com.pusios.pots.build

/**
 * The tail of an agent installer's console output, as the setup screen shows it.
 *
 * Both `\n` and `\r` end a line here: git redraws "Receiving objects" and uv
 * redraws its spinner with a carriage return, so splitting on `\n` alone would
 * collapse a long download into one unreadable string.
 *
 * Only the last [maxChars] characters are kept. An install runs for tens of
 * minutes and only its end explains a failure; [dropped] tells the screen that
 * earlier lines were cut, so a truncated log never reads as a complete one.
 *
 * Bytes arrive in 4 KB reads, so a chunk can end inside a multi-byte character.
 * The incomplete tail is held back and prepended to the next chunk instead of
 * being decoded into `U+FFFD` — the log is read by a human, and a mangled word
 * in a Russian installer message is the one line that mattered.
 *
 * Not thread-safe on purpose: the only writer is the single collector
 * coroutine in `BuildEnvironmentManager`, which is also what publishes it.
 */
class InstallLog(private val maxChars: Int = INSTALL_LOG_MAX_CHARS) {

    private val lines = ArrayDeque<String>()
    private var chars = 0

    /** The line still being written — no newline has ended it yet. */
    private val pending = StringBuilder()

    /** Bytes of a character the last chunk ended in the middle of. */
    private var carry: ByteArray = EMPTY_BYTES

    /** True once the head of the log has been cut to stay inside [maxChars]. */
    var dropped: Boolean = false
        private set

    fun append(bytes: ByteArray, length: Int = bytes.size) {
        if (length <= 0) return
        val chunk = if (carry.isEmpty()) bytes.copyOf(length) else carry + bytes.copyOf(length)
        carry = EMPTY_BYTES
        val cut = incompleteTailStart(chunk)
        if (cut < chunk.size) carry = chunk.copyOfRange(cut, chunk.size)
        if (cut > 0) appendText(chunk.decodeToString(0, cut, throwOnInvalidSequence = false))
    }

    fun appendText(text: String) {
        for (ch in text) {
            if (ch == '\n' || ch == '\r') {
                endLine()
            } else {
                pending.append(ch)
                // A progress line without a carriage return must not grow until
                // it pushes every finished line out of the buffer.
                if (pending.length >= MAX_LINE_CHARS) endLine()
            }
        }
    }

    /** Everything kept, oldest first, with the unfinished line last. */
    fun text(): String = buildString {
        for (line in lines) {
            append(line)
            append('\n')
        }
        if (pending.isNotEmpty()) append(stripAnsi(pending.toString()))
    }

    private fun endLine() {
        val line = stripAnsi(pending.toString()).trimEnd()
        pending.setLength(0)
        // Progress redraws leave a blank line behind on every tick.
        if (line.isEmpty()) return
        lines.addLast(line)
        chars += line.length + 1
        while (chars > maxChars && lines.size > 1) {
            chars -= lines.removeFirst().length + 1
            dropped = true
        }
    }
}

/** Bytes of console output kept for the screen — a screenful of a failure, not a file. */
const val INSTALL_LOG_MAX_CHARS = 64 * 1024

private val EMPTY_BYTES = ByteArray(0)

/**
 * Index of the first byte of a UTF-8 character that [bytes] ends in the middle
 * of, or `bytes.size` when it ends on a character boundary.
 *
 * Only a trailing run of continuation bytes can be such a tail: walk back over
 * them, read the lead byte, and compare the length it promises with what is
 * there. Anything that does not look like a lead byte is left to the decoder —
 * it is already broken input, and holding it back would swallow it for good.
 */
private fun incompleteTailStart(bytes: ByteArray): Int {
    var i = bytes.size - 1
    var continuations = 0
    while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80 && continuations < 3) {
        i--
        continuations++
    }
    if (i < 0) return bytes.size
    val needed = when (bytes[i].toInt() and 0xFF) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> return bytes.size
    }
    return if (bytes.size - i < needed) i else bytes.size
}

/** Longer than this a single line is cut: a log line is read, not scrolled. */
private const val MAX_LINE_CHARS = 200

private const val ESC = '\u001b'

/**
 * Drops the ANSI escapes installers emit when they think a terminal is
 * watching — colour codes and the cursor-up sequences uv uses for its spinner.
 * Left in, they would be shown as `[2K[1A` noise in a plain `Text`.
 */
internal fun stripAnsi(text: String): String {
    if (text.indexOf(ESC) < 0) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch != ESC) {
            out.append(ch)
            i++
            continue
        }
        i++
        if (i >= text.length) break
        when (text[i]) {
            // CSI: ESC [ params final-byte
            '[' -> {
                i++
                while (i < text.length && text[i] !in '@'..'~') i++
                i++
            }
            // OSC: ESC ] ... BEL | ESC \
            ']' -> {
                i++
                while (i < text.length && text[i] != '\u0007') {
                    if (text[i] == ESC && i + 1 < text.length && text[i + 1] == '\\') {
                        i++
                        break
                    }
                    i++
                }
                i++
            }
            else -> i++
        }
    }
    return out.toString()
}
