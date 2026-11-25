package com.pusios.pots.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which tools the model may call, per conversation, when the set is delivered lazily.
 *
 * Two sets with different lifetimes:
 * - **delivered** — what the last request actually carried. It is the gate that turns a call to
 *   a tool the model never saw into a readable "describe it first" answer instead of a silent
 *   execution. Rewritten on every planned request, so switching models or toggles re-scopes it.
 * - **activated** — tools the model pulled in with `describe_tool`. They stay callable and are
 *   re-delivered with their schema on the next turn, so the model does not have to describe the
 *   same tool twice.
 *
 * In-memory and keyed by conversation id: activation is a property of one dialogue and dies
 * with the process. Callers outside a conversation (headless runs, tests) share the "" key.
 * A [Mutex] rather than a concurrent map because common code has no such map and tool calls in
 * one batch run in parallel.
 */
internal object ToolActivation {

    private val mutex = Mutex()
    private val delivered = mutableMapOf<String, Set<String>>()
    private val activated = mutableMapOf<String, MutableSet<String>>()

    private fun key(conversationId: String?): String = conversationId ?: ""

    /** Records the names the request carried. An eager request records every enabled name. */
    suspend fun recordDelivered(conversationId: String?, names: Set<String>) = mutex.withLock {
        delivered[key(conversationId)] = names
    }

    /** Makes [name] callable for the rest of the conversation. */
    suspend fun activate(conversationId: String?, name: String) = mutex.withLock {
        activated.getOrPut(key(conversationId)) { mutableSetOf() }.add(name)
    }

    suspend fun activatedNames(conversationId: String?): Set<String> = mutex.withLock {
        activated[key(conversationId)]?.toSet().orEmpty()
    }

    /**
     * True when [name] was in the last delivered set, was activated through `describe_tool`, or
     * no delivery was ever recorded for this conversation (eager mode and callers that never
     * planned a request — the old behaviour, unrestricted).
     */
    suspend fun canExecute(conversationId: String?, name: String): Boolean = mutex.withLock {
        val conversationKey = key(conversationId)
        val sent = delivered[conversationKey] ?: return@withLock true
        name in sent || name in activated[conversationKey].orEmpty()
    }
}
