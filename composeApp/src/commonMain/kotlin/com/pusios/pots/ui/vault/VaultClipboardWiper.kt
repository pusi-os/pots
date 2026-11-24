// Process-level clipboard wiper (card 2/5): the owner of the wipe timer for
// values copied from vault screens. The timer must outlive the row (and the
// whole screen): a `rememberCoroutineScope` dies when the composable leaves
// composition, and the copied secret would stay in the clipboard forever. One
// instance holds ONE replannable job — every copy cancels the previous timer
// before scheduling its own, so two copies in a row can never wipe the newer
// value early. It is a plain top-level singleton rather than a Koin single:
// vault screens must compose without a Koin container (screenshot/UI tests),
// and the wiper has no dependencies to inject.

package com.pusios.pots.ui.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Minimal clipboard surface the wiper drives; the UI layer adapts the platform clipboard to it. */
fun interface VaultClipboardSink {
    suspend fun set(text: String)
}

class VaultClipboardWiper(
    private val clearAfter: Duration = DEFAULT_CLEAR_AFTER,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var job: Job? = null

    /** Copies [text] and (re)schedules the wipe of the clipboard after [clearAfter]. */
    fun copy(text: String, sink: VaultClipboardSink) {
        job?.cancel()
        job = scope.launch {
            sink.set(text)
            delay(clearAfter)
            sink.set("")
        }
    }

    companion object {
        /** The report's clipboard rule: wipe a copied value ~45 s after the copy. */
        val DEFAULT_CLEAR_AFTER = 45.seconds
    }
}

/** The process-level owner of the wipe timer: one instance for the whole app. */
internal val vaultClipboardWiper = VaultClipboardWiper()
