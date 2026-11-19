package com.pusios.pots.assistant

import android.app.assist.AssistStructure

/**
 * Walks an [AssistStructure] into the flat, Android-free tree [assistScreenText]
 * works on.
 *
 * This is the only Android-specific part of reading the screen: the platform
 * hands us a snapshot of the top activity's view hierarchy, and everything that
 * decides what text to keep is in `commonMain`, where it is tested.
 *
 * The snapshot is taken by the system **once per invocation** — there is no
 * public "give me the screen now" call — so this is called exactly once, from
 * `PotsVoiceInteractionSession`, and the result travels with the question.
 *
 * The walk has no budget of its own: the node limit lives in
 * [assistScreenText] and nowhere else, so exactly one place decides what was
 * cut and that same place adds the mark. Two independent limits on one path is
 * how a snapshot gets silently trimmed by one of them while the other says
 * nothing about it.
 *
 * A `FLAG_SECURE` window arrives as an empty stub and an
 * `importantForAssist=false` node arrives flagged as blocked; both end up as no
 * text, which is the honest answer.
 */
internal fun assistNodesFrom(structure: AssistStructure?): List<AssistNode> {
    if (structure == null) return emptyList()
    val roots = mutableListOf<AssistNode>()
    for (window in 0 until structure.windowNodeCount) {
        val root = runCatching { structure.getWindowNodeAt(window).rootViewNode }.getOrNull() ?: continue
        roots += assistNodeFrom(root)
    }
    return roots
}

/** [assistScreenText] of a whole snapshot: the one call the session makes. */
internal fun assistScreenTextFrom(structure: AssistStructure?): String = assistScreenText(assistNodesFrom(structure))

private fun assistNodeFrom(node: AssistStructure.ViewNode): AssistNode = AssistNode(
    text = node.text?.toString(),
    contentDescription = node.contentDescription?.toString(),
    hint = node.hint,
    blocked = node.isAssistBlocked,
    children = (0 until node.childCount).mapNotNull { index ->
        node.getChildAt(index)?.let { child -> assistNodeFrom(child) }
    },
)
