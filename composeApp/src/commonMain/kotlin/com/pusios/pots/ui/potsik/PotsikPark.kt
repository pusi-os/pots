package com.pusios.pots.ui.potsik

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sleeps until [deadlineMillis] passes or [signal] fires, whichever comes
 * first; a `null` deadline means "wait for the signal alone".
 *
 * This is what the parked frame clock waits on, and it exists instead of
 * `withTimeoutOrNull` for one concrete reason: `withTimeout` schedules its
 * timeout through the context's `Delay` element, and a Compose effect
 * context does not carry one — so under `runComposeUiTest` the timeout ran
 * on the wall clock while everything else (frames, plain `delay`) ran on
 * the test's virtual clock, and the parked loop never woke inside a test:
 * the character froze and the breathing assertions went red at random,
 * depending only on how slow the machine was.
 *
 * Plain `delay` — this helper's timer — follows the effect context's own
 * dispatcher, which is the virtual clock under the test and the wall clock
 * on a device, always the same clock the deadlines themselves are computed
 * on. On a device the behaviour is exactly the timeout's.
 */
internal suspend fun parkUntil(deadlineMillis: Long?, signal: suspend () -> Unit) {
    coroutineScope {
        val done = CompletableDeferred<Unit>()
        val signalJob = launch {
            signal()
            done.complete(Unit)
        }
        val timer = launch {
            if (deadlineMillis != null) delay(deadlineMillis) else awaitCancellation()
            done.complete(Unit)
        }
        done.await()
        signalJob.cancel()
        timer.cancel()
    }
}
