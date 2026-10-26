package com.pusios.pots.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** The service instance and model currently producing a turn. */
class ModelExecutionElement(
    val instanceId: String,
    val modelId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelExecutionElement>
}

/** Origin of the model call running in this coroutine, or null outside a model turn. */
suspend fun currentModelExecutionOrNull(): ModelExecutionElement? = coroutineContext[ModelExecutionElement]
