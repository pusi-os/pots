package com.pusios.pots.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class ConversationIdElement(val conversationId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationIdElement>
}

/**
 * Marks a run that has no chat UI in the foreground (heartbeat, scheduled task). Tools that
 * need the user on screen — the vault request form — refuse immediately instead of hanging
 * out the full timeout waiting for an answer nobody can give.
 */
class HeadlessRunElement : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HeadlessRunElement>
}

suspend fun currentConversationIdOrNull(): String? = coroutineContext[ConversationIdElement]?.conversationId

suspend fun currentIsHeadlessRun(): Boolean = coroutineContext[HeadlessRunElement] != null
