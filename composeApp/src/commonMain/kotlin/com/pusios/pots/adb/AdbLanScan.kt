// The LAN half of discovery: mDNS only shows daemons the phone can see through its own interfaces
// (libadb filters everything else out), so a TV that answers on 5555 is found by probing the /24
// the phone sits in. The probing itself is a bounded fan-out over an injected probe, which keeps
// the concurrency, the cancellation and the "is this ADB" decision testable on the JVM; only the
// socket and the address list live in androidMain.

package com.pusios.pots.adb

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Probes [hosts] with at most [concurrency] probes in flight and returns the hosts that answered
 * as ADB daemons.
 *
 * Cancellation is the caller's: the coroutine scope of the scan is cancelled by the screen's
 * "stop", and every in-flight probe is cancelled with it. Results are returned in the order the
 * hosts were given, so the list on screen does not reshuffle between scans.
 */
suspend fun scanAdbHosts(
    hosts: List<String>,
    concurrency: Int = AdbTimeouts.SCAN_CONCURRENCY,
    probe: suspend (String) -> AdbProbeResult,
): List<String> = coroutineScope {
    val permits = Semaphore(concurrency.coerceAtLeast(1))
    hosts.distinct()
        .map { host ->
            async {
                permits.withPermit {
                    if (adbProbeIsDaemon(probe(host))) host else null
                }
            }
        }
        .awaitAll()
        .filterNotNull()
}
