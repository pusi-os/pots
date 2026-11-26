// The LAN scan's fan-out, checked with a fake probe: only daemons are reported, the concurrency is
// bounded, a host is probed once, and cancelling the caller stops the rest. The socket half lives in
// androidMain and is exercised on a device, not here.

package com.pusios.pots.adb

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbLanScanTest {

    private fun hosts(count: Int): List<String> = (1..count).map { last -> "192.168.1.$last" }

    @Test
    fun `only hosts that answer as ADB daemons are reported`() = runTest {
        val answers = mapOf(
            "192.168.1.2" to AdbProbeResult.AuthRequired,
            "192.168.1.3" to AdbProbeResult.Connected,
            "192.168.1.4" to AdbProbeResult.Silent,
            "192.168.1.5" to AdbProbeResult.PortClosed,
            "192.168.1.6" to AdbProbeResult.NotAdb("HTTP"),
        )

        val found = scanAdbHosts(answers.keys.toList()) { host -> answers.getValue(host) }

        assertEquals(listOf("192.168.1.2", "192.168.1.3"), found)
    }

    @Test
    fun `at most the configured number of probes run at once`() = runTest {
        var inFlight = 0
        var peak = 0

        val found = scanAdbHosts(hosts(40), concurrency = 5) { host ->
            inFlight++
            peak = maxOf(peak, inFlight)
            delay(10)
            inFlight--
            if (host.endsWith(".7")) AdbProbeResult.AuthRequired else AdbProbeResult.PortClosed
        }

        assertEquals(listOf("192.168.1.7"), found)
        assertTrue(peak <= 5, "peak concurrency was $peak with a limit of 5")
        assertEquals(5, peak, "the scan must actually use the whole budget")
    }

    @Test
    fun `a host listed twice is probed once`() = runTest {
        var probes = 0

        scanAdbHosts(listOf("192.168.1.2", "192.168.1.2", "192.168.1.3")) {
            probes++
            AdbProbeResult.PortClosed
        }

        assertEquals(2, probes)
    }

    @Test
    fun `cancelling the caller stops the scan`() = runTest {
        var started = 0
        val job = launch {
            scanAdbHosts(hosts(50), concurrency = 4) {
                started++
                delay(60_000)
                AdbProbeResult.Connected
            }
        }
        runCurrent()
        assertEquals(4, started, "only the four probes that hold a permit may have started")

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(4, started, "no new probe may start after cancellation")
    }
}
