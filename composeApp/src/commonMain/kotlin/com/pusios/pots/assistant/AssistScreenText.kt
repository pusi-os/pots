package com.pusios.pots.assistant

/**
 * One node of an assist snapshot: what a view shows, not the view.
 *
 * The Android traversal (`androidMain`) fills these in from `AssistStructure`;
 * everything that decides *what text the screen has* lives here, so the rules
 * can be tested on a desktop. The tree is kept (children, not a flat list)
 * because a blocked node hides its whole subtree, and that is a rule, not a
 * detail of the platform.
 */
data class AssistNode(
    /** What the view shows — a label, a line of a list, a button's caption. */
    val text: String? = null,
    /** What a screen reader would say about it (an icon button's only text). */
    val contentDescription: String? = null,
    /** The grey placeholder of an empty field. */
    val hint: String? = null,
    /**
     * The app closed this node to assist (`importantForAssist=false`), or the
     * whole window is `FLAG_SECURE` and arrives as an empty stub. Nothing at or
     * below a blocked node is the user's screen content.
     */
    val blocked: Boolean = false,
    val children: List<AssistNode> = emptyList(),
)

/**
 * How many nodes one snapshot may contribute.
 *
 * A long list scrolls, and the assist snapshot is the whole hierarchy: without
 * a cap a chat screen can be tens of thousands of nodes, and the walk itself
 * becomes the cost.
 */
const val ASSIST_NODE_LIMIT = 1_500

/**
 * How much text one snapshot may contribute — a prompt, not a book.
 *
 * The snapshot is attached to a question, and every character of it is paid
 * for on every turn of the conversation; 6 KB is roughly two screens of text,
 * which is what a question "what does this say?" needs.
 */
const val ASSIST_SCREEN_TEXT_LIMIT = 6_000

/**
 * Appended when the snapshot was cut, so the model knows it is looking at a
 * part of the screen rather than at all of it.
 */
const val ASSIST_TEXT_TRUNCATED_MARK = "\n[… screen text truncated]"

private val WHITESPACE = Regex("\\s+")

/**
 * The text of an assist snapshot, as one compact block.
 *
 * The rules, in the order they are applied:
 *
 * * pre-order, left to right — the order a screen reader reads the screen in,
 *   which is also the order a person would read it;
 * * a blocked node is skipped with its whole subtree (the app said "not for
 *   assist", and a child of such a node is just as much the app's content);
 * * each node contributes its text, then its content description, then its
 *   hint — an icon button has only the second, an empty field only the third;
 * * whitespace is collapsed and empty lines are dropped: a snapshot is full of
 *   layout text that says nothing;
 * * a line seen before is dropped — the same word in a header and in a list
 *   row is one word of context, and duplicates are pure prompt cost;
 * * the walk stops at [nodeLimit] nodes or [limit] characters, whichever comes
 *   first, and [ASSIST_TEXT_TRUNCATED_MARK] says so. This is the *only* place
 *   that limits a snapshot: the Android walk builds the whole tree, so the cut
 *   and the mark always come from the same decision.
 *
 * An empty tree, a secure window's empty stub and a snapshot that was never
 * delivered all give `""` — the caller must treat that as "no context", not as
 * an empty screen. A snapshot that was cut before anything readable was found
 * is `""` too: a mark on its own would put «truncated» in the prompt as if the
 * screen had said it.
 */
fun assistScreenText(
    roots: List<AssistNode>,
    limit: Int = ASSIST_SCREEN_TEXT_LIMIT,
    nodeLimit: Int = ASSIST_NODE_LIMIT,
): String {
    if (roots.isEmpty() || limit <= 0 || nodeLimit <= 0) return ""

    val out = StringBuilder()
    val seen = HashSet<String>()
    var visited = 0
    var cut = false

    fun take(raw: String?) {
        if (cut) return
        val line = raw?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (line.isEmpty() || !seen.add(line)) return
        // +1 for the newline that joins it to what is already there.
        val extra = if (out.isEmpty()) line.length else line.length + 1
        if (out.length + extra > limit) {
            cut = true
            return
        }
        if (out.isNotEmpty()) out.append('\n')
        out.append(line)
    }

    val stack = ArrayDeque<AssistNode>()
    for (root in roots.asReversed()) stack.addLast(root)
    while (stack.isNotEmpty() && !cut) {
        val node = stack.removeLast()
        if (node.blocked) continue
        if (visited >= nodeLimit) {
            cut = true
            break
        }
        visited++
        for (child in node.children.asReversed()) stack.addLast(child)
        take(node.text)
        take(node.contentDescription)
        take(node.hint)
    }

    val text = out.toString()
    // Nothing readable is no context, cut or not: a mark on its own would put
    // "[… truncated]" in the prompt as if the screen had said it.
    if (text.isEmpty()) return ""
    return if (cut) text + ASSIST_TEXT_TRUNCATED_MARK else text
}

/**
 * The question with the snapshot attached, or the question alone.
 *
 * The snapshot goes *after* the question, under a [header] the user can read in
 * the chat: the question is what the person said, the block is what was on
 * their screen at the moment the assistant was invoked, and a message that
 * merged the two would misquote them.
 */
fun questionWithScreenContext(question: String, screenContext: String, header: String): String {
    val asked = question.trim()
    val screen = screenContext.trim()
    if (screen.isEmpty()) return asked
    return "$asked\n\n$header\n$screen"
}
