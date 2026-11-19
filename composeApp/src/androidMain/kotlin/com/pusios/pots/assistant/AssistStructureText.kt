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
 * A `FLAG_SECURE` window arrives as an empty stub and an `importantForAssist=false`
 * node arrives flagged as blocked; both end up as no text, which is the honest
 * answer.
 */
internal fun assistNodesFrom(
    structure: AssistStructure?,
    nodeLimit: Int = ASSIST_NODE_LIMIT,
): List<AssistNode> {
    if (structure == null || nodeLimit <= 0) return emptyList()
    // A shared budget across windows: split screen is one screen, not two
    // screens' worth of prompt.
    val budget = intArrayOf(nodeLimit)
    val roots = mutableListOf<AssistNode>()
    for (window in 0 until structure.windowNodeCount) {
        if (budget[0] <= 0) break
        val root = runCatching { structure.getWindowNodeAt(window).rootViewNode }.getOrNull() ?: continue
        roots += assistNodeFrom(root, budget)
    }
    return roots
}

/** [assistScreenText] of a whole snapshot: the one call the session makes. */
internal fun assistScreenTextFrom(structure: AssistStructure?): String = assistScreenText(assistNodesFrom(structure))

private fun assistNodeFrom(node: AssistStructure.ViewNode, budget: IntArray): AssistNode {
    budget[0]--
    val children = if (budget[0] > 0) {
        (0 until node.childCount).mapNotNull { index ->
            node.getChildAt(index)?.let { child -> assistNodeFrom(child, budget) }
        }
    } else {
        emptyList()
    }
    return AssistNode(
        text = node.text?.toString(),
        contentDescription = node.contentDescription?.toString(),
        hint = node.hint,
        blocked = node.isAssistBlocked,
        children = children,
    )
}
