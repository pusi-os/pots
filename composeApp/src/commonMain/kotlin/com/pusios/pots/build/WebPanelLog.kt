package com.pusios.pots.build

/**
 * The panel's log as the row shows it: the tail of the file, with the panel's own
 * password cut out.
 *
 * The log is the first diagnostic there is. "The panel never started" and "the
 * panel started but serves an empty page" look identical in a browser — one gives
 * "connection refused", the other a blank tab — and only the log tells them
 * apart. So the row shows it, and the guest only has to hand over the file's text.
 */
object WebPanelLog {

    /**
     * How much of the file the row shows: about a hundred lines, enough to hold a
     * failed start from its first message to the moment it gave up. The 400
     * characters this replaced were sized for a one-line failure reason, and cut a
     * real error off just after its beginning.
     */
    const val TAIL_CHARS = 8_000

    /** The guest's answer when the panel never wrote a log at all. */
    const val NO_FILE = "no log"

    /** The guest's answer when the file is there but empty. */
    const val EMPTY = "no output"

    /**
     * How much of a line a failure reason quotes. The status line is a sentence;
     * the whole log belongs in the section under it, which scrolls.
     */
    const val BRIEF_CHARS = 200

    /** The last [TAIL_CHARS] characters of [text], or [EMPTY] when there are none. */
    fun tail(text: String): String = text.takeLast(TAIL_CHARS).trim().ifEmpty { EMPTY }

    /**
     * The log as the row shows it: [redact] first, [tail] second.
     *
     * The order matters. Cutting first would let a password that lies across the
     * cut survive as a fragment: the rest of it is still in the tail, and
     * [redact] no longer finds the whole value to cut out. Masking the whole file
     * and cutting afterwards leaves nothing of it behind. The sandbox's run
     * answer cuts its output the same way, for the same reason.
     */
    fun show(raw: String, password: String?): String = tail(redact(raw, password))

    /**
     * The one line a failure reason quotes: the last thing the panel said, masked,
     * with the front of an overlong line dropped.
     *
     * A start that times out is explained by its last message — "address already
     * in use", "permission denied" — and the whole tail in the status line would
     * turn a sentence into a wall of text duplicating the section under it.
     */
    fun brief(raw: String, password: String?): String {
        val masked = redact(raw, password)
        if (masked == NO_FILE || masked == EMPTY) return masked
        val line = masked.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return EMPTY
        return if (line.length <= BRIEF_CHARS) line else "…" + line.takeLast(BRIEF_CHARS)
    }

    /**
     * [log] with every occurrence of [password] replaced by `***`.
     *
     * The password reaches the panel through its environment, so it should not be
     * in the log at all — but a panel that echoes its environment, or a shell that
     * fails with its command line in the message, would put it there. This text is
     * rendered on screen, read over a shoulder and screenshotted, so it is cut out
     * before it leaves the runtime.
     *
     * Short values are left alone: masking a one- or two-character password would
     * turn the log into asterisks and make the diagnosis impossible. The generated
     * ones are [WebPanelPasswords.LENGTH] characters long.
     */
    fun redact(log: String, password: String?): String =
        if (password == null || password.length < MIN_PASSWORD_CHARS) log else log.replace(password, MASK)

    private const val MASK = "***"
    private const val MIN_PASSWORD_CHARS = 4
}
