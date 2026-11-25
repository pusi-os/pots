// The ADB tools must never let an Error out of `execute`. The 3.21.0 crash was a StackOverflowError
// raised inside the backend's connect, and `catch (e: Exception)` does not see an Error — so every
// tool now wraps its backend calls and turns that failure into a tool result the model can read.

package com.pusios.pots.adb

import com.pusios.pots.network.tools.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A backend whose every transport call fails with the same [failure]. */
private class ErroringAdbBackend(private val failure: Throwable) : AdbBackend by FakeAdbBackend() {
    override suspend fun connect(host: String, port: Int, tls: Boolean): Boolean = throw failure

    override suspend fun disconnect(host: String, port: Int) {
        throw failure
    }

    override suspend fun shell(host: String, port: Int, command: String, timeoutSec: Int): String = throw failure

    override suspend fun installApk(host: String, port: Int, apkPath: String): String = throw failure

    override suspend fun uninstall(host: String, port: Int, packageName: String): String = throw failure
}

class AdbToolsErrorContainmentTest {

    private fun tools(failure: Throwable): List<Tool> = AdbTools.create(
        AdbToolDependencies(
            backend = ErroringAdbBackend(failure),
            store = FakeAdbStore(),
            gate = RecordingAdbGate(),
            apkCache = FakeAdbApkCache(),
        ),
    )

    private suspend fun run(failure: Throwable, name: String, args: Map<String, Any>): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return tools(failure).first { it.schema.name == name }.execute(args) as Map<String, Any>
    }

    @Test
    fun `adb_connect reports a StackOverflowError from the backend as a tool error`() = runTest {
        val result = run(StackOverflowError(), "adb_connect", mapOf("endpoint" to "192.168.1.243:5555"))
        assertEquals(false, result["success"], result.toString())
        assertTrue((result["error"] as String).contains("StackOverflowError"), result.toString())
    }

    @Test
    fun `adb_shell reports a StackOverflowError from the backend as a tool error`() = runTest {
        val result = run(StackOverflowError(), "adb_shell", mapOf("device_id" to TEST_DEVICE.id, "command" to "id"))
        assertEquals(false, result["success"], result.toString())
        assertTrue((result["error"] as String).contains("StackOverflowError"), result.toString())
    }

    @Test
    fun `adb_install reports a StackOverflowError from the backend as a tool error`() = runTest {
        val args = mapOf("device_id" to TEST_DEVICE.id, "url" to "https://example.com/app.apk")
        val result = run(StackOverflowError(), "adb_install", args)
        assertEquals(false, result["success"], result.toString())
        assertTrue((result["error"] as String).contains("StackOverflowError"), result.toString())
    }

    @Test
    fun `adb_uninstall reports a StackOverflowError from the backend as a tool error`() = runTest {
        val args = mapOf("device_id" to TEST_DEVICE.id, "package" to "com.example.app")
        val result = run(StackOverflowError(), "adb_uninstall", args)
        assertEquals(false, result["success"], result.toString())
        assertTrue((result["error"] as String).contains("StackOverflowError"), result.toString())
    }

    @Test
    fun `adb_disconnect reports a StackOverflowError from the backend as a tool error`() = runTest {
        val result = run(StackOverflowError(), "adb_disconnect", mapOf("device_id" to TEST_DEVICE.id))
        assertEquals(false, result["success"], result.toString())
        assertTrue((result["error"] as String).contains("StackOverflowError"), result.toString())
    }

    @Test
    fun `an OutOfMemoryError is contained the same way`() = runTest {
        val result = run(OutOfMemoryError("Java heap space"), "adb_shell", mapOf("device_id" to TEST_DEVICE.id, "command" to "id"))
        assertEquals(false, result["success"], result.toString())
        assertTrue((result["error"] as String).contains("OutOfMemoryError"), result.toString())
    }

    @Test
    fun `the owner's cancellation is still rethrown, not reported as a tool failure`() = runTest {
        val args = mapOf("device_id" to TEST_DEVICE.id, "command" to "id")
        assertFailsWith<CancellationException> { run(CancellationException("stop"), "adb_shell", args) }
    }
}
