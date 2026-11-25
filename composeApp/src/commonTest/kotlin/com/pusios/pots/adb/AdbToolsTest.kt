// The ADB tools' decisions, checked without a device: the six registered names, the untrusted
// read-only rule, the owner confirmation on every changing action, the LAN address check and the
// 8 KB output tail. The transport is a fake, so a passing test says the policy works, not that
// the socket does.

package com.pusios.pots.adb

import com.pusios.pots.network.tools.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class Fixture(devices: List<AdbDevice> = listOf(TEST_DEVICE), gateAllowed: Boolean = true) {
    val backend = FakeAdbBackend()
    val store = FakeAdbStore(devices)
    val gate = RecordingAdbGate(gateAllowed)
    val cache = FakeAdbApkCache()
    val tools: List<Tool> = AdbTools.create(AdbToolDependencies(backend, store, gate, cache))

    fun tool(name: String): Tool = tools.first { it.schema.name == name }

    suspend fun run(name: String, args: Map<String, Any>): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return tool(name).execute(args) as Map<String, Any>
    }
}

class AdbToolsTest {

    @Test
    fun registersExactlyTheSevenTools() {
        val names = Fixture().tools.map { it.schema.name }
        assertEquals(AdbTools.TOOL_NAMES, names)
        assertEquals(7, names.size)
        assertTrue("adb_diagnose" in names)
        // The names that are explicitly out of scope for 3.21 must not leak into the set.
        assertFalse(names.any { it == "adb_screencap" || it == "adb_forward" || it == "adb_pair" })
    }

    @Test
    fun diagnoseReportsTheProbeVerdictAndTheLogTailWithoutAskingTheOwner() = runTest {
        val fixture = Fixture()
        fixture.backend.probeResult = AdbProbeResult.AuthRequired
        PotsAdbLog.clear()
        PotsAdbLog.step("probe", "192.168.1.50:5555 -> AuthRequired")
        val result = fixture.run("adb_diagnose", mapOf("device_id" to TEST_DEVICE.id))
        assertEquals(true, result["success"])
        assertEquals(true, result["reachable"])
        assertEquals(true, result["daemon"])
        assertTrue((result["probe"] as String).contains("waits for the device owner"))
        assertTrue((result["hint"] as String).contains("«Allow»"))
        assertTrue((result["log"] as List<*>).any { line -> (line as String).contains("AuthRequired") })
        assertTrue(fixture.gate.calls.isEmpty())
    }

    @Test
    fun diagnoseOfAClosedPortSaysNothingListens() = runTest {
        val fixture = Fixture()
        fixture.backend.probeResult = AdbProbeResult.PortClosed
        val result = fixture.run("adb_diagnose", mapOf("endpoint" to "192.168.1.90:5555"))
        assertEquals(true, result["success"])
        assertEquals(false, result["reachable"])
        assertEquals(false, result["daemon"])
        assertTrue((result["probe"] as String).contains("nothing listens"))
    }

    @Test
    fun connectPassesTheProbeVerdictThroughInsteadOfNoConnection() = runTest {
        val fixture = Fixture()
        fixture.backend.connectFailure = AdbException(
            AdbErrorKind.TIMEOUT,
            "the device did not confirm the connection within 120s — tap «Allow» on its screen",
        )
        val result = fixture.run("adb_connect", mapOf("device_id" to TEST_DEVICE.id))
        assertEquals(false, result["success"])
        assertEquals("the operation timed out", result["error"])
        assertTrue((result["hint"] as String).contains("did not confirm"))
        assertTrue((result["hint"] as String).contains("«Allow»"))
    }

    @Test
    fun connectAsksForTheLongWindowOnlyOnAnUntrustedDevice() = runTest {
        val trusted = Fixture()
        trusted.run("adb_connect", mapOf("device_id" to TEST_DEVICE.id))
        assertEquals(listOf(false), trusted.backend.firstContacts)

        val untrusted = Fixture(devices = listOf(TEST_DEVICE.copy(trusted = false)))
        untrusted.run("adb_connect", mapOf("device_id" to TEST_DEVICE.id))
        assertEquals(listOf(true), untrusted.backend.firstContacts)
    }

    @Test
    fun listDevicesReadsTheRegistryWithoutScanning() = runTest {
        val fixture = Fixture()
        val result = fixture.run("adb_list_devices", emptyMap())
        assertEquals(true, result["success"])
        assertEquals(1, result["count"])
        assertEquals(0, fixture.backend.discoverCalls)
    }

    @Test
    fun untrustedDeviceRefusesAWriteCommandBeforeTouchingTheBackend() = runTest {
        val untrusted = TEST_DEVICE.copy(trusted = false)
        val fixture = Fixture(devices = listOf(untrusted))
        val result = fixture.run("adb_shell", mapOf("device_id" to TEST_DEVICE.id, "command" to "pm install -r /sdcard/x.apk"))
        assertEquals(false, result["success"])
        assertTrue((result["error"] as String).contains("read-only"))
        assertTrue(fixture.backend.shellCalls.isEmpty())
        assertTrue(fixture.gate.calls.isEmpty())
    }

    @Test
    fun untrustedDeviceAllowsAReadOnlyCommandWithoutAsking() = runTest {
        val untrusted = TEST_DEVICE.copy(trusted = false)
        val fixture = Fixture(devices = listOf(untrusted))
        fixture.backend.shellOutput = "uid=2000(shell) gid=2000(shell)"
        val result = fixture.run("adb_shell", mapOf("device_id" to TEST_DEVICE.id, "command" to "id"))
        assertEquals(true, result["success"])
        assertEquals(listOf("id"), fixture.backend.shellCalls)
        assertTrue(fixture.gate.calls.isEmpty())
    }

    @Test
    fun trustedDeviceAsksTheOwnerBeforeAWriteCommand() = runTest {
        val fixture = Fixture(gateAllowed = false)
        val result = fixture.run("adb_shell", mapOf("device_id" to TEST_DEVICE.id, "command" to "settings put global x 1"))
        assertEquals(false, result["success"])
        assertEquals("denied by user", result["error"])
        assertEquals(1, fixture.gate.calls.size)
        assertEquals("adb_shell", fixture.gate.calls.single().first)
        assertTrue(fixture.backend.shellCalls.isEmpty())
    }

    @Test
    fun installAsksTheOwnerAndPushesTheDownloadedApk() = runTest {
        val fixture = Fixture()
        val result = fixture.run(
            "adb_install",
            mapOf("device_id" to TEST_DEVICE.id, "url" to "https://example.com/app.apk"),
        )
        assertEquals(true, result["success"])
        assertEquals(1, fixture.gate.calls.size)
        assertEquals("adb_install", fixture.gate.calls.single().first)
        assertEquals(listOf("https://example.com/app.apk"), fixture.cache.urls)
        assertEquals(listOf("/data/cache/install.apk"), fixture.backend.installs)
    }

    @Test
    fun installOfAPathOnTheDeviceRunsPmInstallWithoutPush() = runTest {
        val fixture = Fixture()
        fixture.backend.shellOutput = "Success"
        val result = fixture.run(
            "adb_install",
            mapOf("device_id" to TEST_DEVICE.id, "path" to "/sdcard/Download/app.apk"),
        )
        assertEquals(true, result["success"])
        assertEquals(listOf("pm install -r /sdcard/Download/app.apk"), fixture.backend.shellCalls)
        assertTrue(fixture.backend.installs.isEmpty())
    }

    @Test
    fun installRejectsAShellMetacharacterInTheDevicePath() = runTest {
        val fixture = Fixture()
        val result = fixture.run(
            "adb_install",
            mapOf("device_id" to TEST_DEVICE.id, "path" to "/sdcard/app.apk; reboot"),
        )
        assertEquals(false, result["success"])
        assertTrue(fixture.gate.calls.isEmpty())
        assertTrue(fixture.backend.shellCalls.isEmpty())
    }

    @Test
    fun uninstallAlwaysAsksAndPassesTheBackendRefusalThrough() = runTest {
        val fixture = Fixture()
        val result = fixture.run("adb_uninstall", mapOf("device_id" to TEST_DEVICE.id, "package" to "com.example.app"))
        assertEquals(true, result["success"])
        assertEquals(listOf("com.example.app"), fixture.backend.uninstalls)
        assertEquals("adb_uninstall", fixture.gate.calls.single().first)

        // The backend refuses an invalid name; the tool must not swallow the wording.
        val refused = Fixture()
        val invalid = AdbException(AdbErrorKind.IO, "not an Android package name: com.x; reboot")
        val failing = object : AdbBackend by refused.backend {
            override suspend fun uninstall(host: String, port: Int, packageName: String): String = throw invalid
        }
        val tools = AdbTools.create(AdbToolDependencies(failing, refused.store, refused.gate, refused.cache))

        @Suppress("UNCHECKED_CAST")
        val error = tools.first { it.schema.name == "adb_uninstall" }
            .execute(mapOf("device_id" to TEST_DEVICE.id, "package" to "com.x; reboot")) as Map<String, Any>
        assertEquals(false, error["success"])
        assertEquals("not an Android package name: com.x; reboot", error["error"])
    }

    @Test
    fun connectRejectsAPublicAddress() = runTest {
        val fixture = Fixture()
        val result = fixture.run("adb_connect", mapOf("endpoint" to "203.0.113.7:5555"))
        assertEquals(false, result["success"])
        assertTrue((result["error"] as String).contains("private/LAN"))
        assertTrue(fixture.gate.calls.isEmpty())
        assertTrue(fixture.backend.connects.isEmpty())
    }

    @Test
    fun connectGatesANewEndpointAndStoresItUntrusted() = runTest {
        val fixture = Fixture(devices = emptyList())
        val result = fixture.run("adb_connect", mapOf("endpoint" to "192.168.1.77:5555"))
        assertEquals(true, result["success"])
        assertEquals("adb_connect", fixture.gate.calls.single().first)
        val stored = fixture.store.all().single()
        assertEquals("192.168.1.77:5555", stored.id)
        assertFalse(stored.trusted)
    }

    @Test
    fun connectOfAKnownTrustedDeviceDoesNotAsk() = runTest {
        val fixture = Fixture()
        val result = fixture.run("adb_connect", mapOf("device_id" to TEST_DEVICE.id))
        assertEquals(true, result["success"])
        assertTrue(fixture.gate.calls.isEmpty())
    }

    @Test
    fun shellOutputIsCutToTheLastEightKilobytes() = runTest {
        val fixture = Fixture()
        val tail = "TAIL-MARKER"
        fixture.backend.shellOutput = "A".repeat(10_000) + tail
        val result = fixture.run("adb_shell", mapOf("device_id" to TEST_DEVICE.id, "command" to "dumpsys activity"))
        assertEquals(true, result["success"])
        val output = result["output"] as String
        assertTrue(output.startsWith("[output truncated: showing the last "))
        assertTrue(output.endsWith(tail))
        assertEquals(true, result["truncated"])
    }

    @Test
    fun gateSummaryMasksTokenLikeArguments() = runTest {
        val fixture = Fixture()
        fixture.run(
            "adb_shell",
            mapOf("device_id" to TEST_DEVICE.id, "command" to "curl -H token=SECRET-VALUE http://x"),
        )
        val summary = fixture.gate.calls.single().second
        assertFalse(summary.contains("SECRET-VALUE"))
        assertTrue(summary.contains("***"))
    }

    @Test
    fun gatedToolBudgetsOutliveTheConfirmationWindow() {
        // ToolExecutor wraps execute in withTimeout(tool.timeout). A budget shorter than the gate's
        // window would kill the call while the owner is still deciding, and respond() would have
        // nowhere to deliver the answer. The longest path is gate + first connect (the owner tapping
        // «Allow» on a TV) + one shell command, so all three have to fit.
        val window = AdbGate().confirmTimeout
        val connectWindow = AdbTimeouts.CONNECT_FIRST_SEC.seconds
        val gated = listOf("adb_connect", "adb_shell", "adb_install", "adb_uninstall")
        val tools = Fixture().tools
        for (name in gated) {
            val budget = tools.first { it.schema.name == name }.timeout
            assertTrue(
                budget > window + connectWindow + AdbTimeouts.SHELL_SEC.seconds,
                "$name budget $budget must outlive the $window confirmation window, the " +
                    "$connectWindow first connect and a shell command",
            )
        }
    }

    @Test
    fun connectRecordsTheModelItReadsFromTheDevice() = runTest {
        val fixture = Fixture()
        fixture.backend.shellOutput = "Pixel 7\n"
        val result = fixture.run("adb_connect", mapOf("device_id" to TEST_DEVICE.id))
        assertEquals(true, result["success"])
        assertEquals(listOf("getprop ro.product.model"), fixture.backend.shellCalls)
        assertEquals("Pixel 7", fixture.store.all().single().lastModel)
    }

    @Test
    fun connectSucceedsWhenTheModelReadFails() = runTest {
        val fixture = Fixture()
        fixture.backend.shellFailure = AdbException(AdbErrorKind.IO, "connection closed")
        val result = fixture.run("adb_connect", mapOf("device_id" to TEST_DEVICE.id))
        assertEquals(true, result["success"])
        assertNull(fixture.store.all().single().lastModel)
    }

    @Test
    fun installPropagatesCancellationInsteadOfReportingAFailure() = runTest {
        val fixture = Fixture()
        val cancelling = object : AdbBackend by fixture.backend {
            override suspend fun installApk(host: String, port: Int, apkPath: String): String = throw CancellationException("cancelled")
        }
        val install = AdbTools.create(AdbToolDependencies(cancelling, fixture.store, fixture.gate, fixture.cache))
            .first { it.schema.name == "adb_install" }
        val caught = try {
            install.execute(mapOf("device_id" to TEST_DEVICE.id, "url" to "https://example.com/app.apk"))
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertNotNull(caught, "the tool must rethrow cancellation, not turn it into a failure result")
    }

    @Test
    fun anOwnerAnswerAtSixtySecondsStillReachesGatedTools() = runTest {
        // The reviewer's reproduction of the old budgets: the owner answers 60 s after the request,
        // which is inside the 120 s window but was past the 45 s / 30 s tool budgets.
        val fixture = Fixture()
        fixture.backend.shellOutput = "uid=2000(shell)"
        val slowGate = AdbConfirmation { _, _ ->
            delay(60.seconds)
            true
        }
        val tools = AdbTools.create(AdbToolDependencies(fixture.backend, fixture.store, slowGate, fixture.cache))
        val calls = listOf(
            "adb_shell" to mapOf("device_id" to TEST_DEVICE.id, "command" to "settings put global x 1"),
            "adb_uninstall" to mapOf("device_id" to TEST_DEVICE.id, "package" to "com.example.app"),
            "adb_connect" to mapOf("endpoint" to "192.168.1.77:5555"),
        )
        for ((name, args) in calls) {
            val tool = tools.first { it.schema.name == name }

            // ToolExecutor's own wrapper, so the test exercises the real ordering.
            @Suppress("UNCHECKED_CAST")
            val result = withTimeout(tool.timeout) { tool.execute(args) } as Map<String, Any>
            assertEquals(true, result["success"], "$name must survive a 60 s answer")
        }
    }

    @Test
    fun missingDeviceIdIsReportedWithAHint() = runTest {
        val fixture = Fixture()
        val result = fixture.run("adb_shell", mapOf("command" to "id"))
        assertEquals(false, result["success"])
        assertTrue((result["hint"] as String).contains("adb_list_devices"))
    }
}
