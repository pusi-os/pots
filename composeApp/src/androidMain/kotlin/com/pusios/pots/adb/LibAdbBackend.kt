package com.pusios.pots.adb

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.pusios.pots.tools.PermissionController
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [AdbBackend] on top of `libadb-android` — the only JVM client with a real pairing handshake
 * (SPAKE2 + TLS), which is what makes ADB work without root and without a bundled `adb` binary.
 *
 * The library is used the way its README suggests: one [AbsAdbConnectionManager] per endpoint, key
 * and certificate from [AdbKeyStore], `shell:` and `sync:` streams for the actual work. Everything
 * that is a decision rather than a socket call — timeouts, the read-only classifier, error mapping,
 * the sync framing — lives in commonMain with tests; this class only moves bytes.
 *
 * `close()` is not called on a manager: it does the same teardown as `disconnect()` and then
 * destroys the key instance it was handed. Managers stay in the map for the next [connect], so the
 * minimal call — [disconnect] — is the right one; the destroyed key would be harmless anyway, since
 * [AdbKeyStore] hands out a fresh instance per call.
 */
class LibAdbBackend(
    private val context: Context,
    private val keys: AdbKeyStore,
    private val localNetworkPermission: PermissionController,
    private val hostName: String = DEVICE_NAME,
) : AdbBackend {
    private val managers = mutableMapOf<String, AbsAdbConnectionManager>()

    override suspend fun pair(host: String, port: Int, code: String): AdbPairResult = withContext(Dispatchers.IO) {
        try {
            // Pairing opens a plain socket with no connect or handshake timeout, so the deadline has
            // to come from outside: the coroutine machinery cannot interrupt a blocking call.
            val paired = callWithDeadline(AdbTimeouts.PAIR_SEC * 1000L) { managerFor(host, port).pair(host, port, code) }
            when (paired) {
                null -> AdbPairResult.Timeout
                true -> AdbPairResult.Success
                false -> AdbPairResult.IoError("the device refused the pairing")
            }
        } catch (error: Exception) {
            adbPairResultOf(error)
        }
    }

    /**
     * Runs the blocking [block] on its own thread and gives up after [timeoutMs], returning null.
     *
     * The abandoned thread dies when its socket gives up; what matters is that the caller — the
     * pairing screen — is never stuck on a peer that accepted the connection and went silent.
     */
    private suspend fun <T> callWithDeadline(timeoutMs: Long, block: () -> T): T? = withContext(Dispatchers.IO) {
        val future = CompletableFuture<T>()
        val worker = Thread(
            {
                runCatching { future.complete(block()) }.onFailure { future.completeExceptionally(it) }
            },
            PAIRING_THREAD_NAME,
        )
        worker.isDaemon = true
        worker.start()
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    override suspend fun connect(host: String, port: Int, tls: Boolean): Boolean = withContext(Dispatchers.IO) {
        val manager = managerFor(host, port)
        try {
            manager.isConnected || manager.connect(host, port)
        } catch (error: Exception) {
            throw adbExceptionOf(error)
        }
    }

    override suspend fun disconnect(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            val manager = synchronized(managers) { managers.remove(adbDeviceId(host, port)) } ?: return@withContext
            runCatching { manager.disconnect() }
        }
    }

    override suspend fun disconnectAll() {
        withContext(Dispatchers.IO) {
            val open = synchronized(managers) {
                val snapshot = managers.values.toList()
                managers.clear()
                snapshot
            }
            open.forEach { manager -> runCatching { manager.disconnect() } }
        }
    }

    override suspend fun shell(host: String, port: Int, command: String, timeoutSec: Int): String = withContext(Dispatchers.IO) {
        runShell(connectedManager(host, port), command, timeoutSec)
    }

    override suspend fun installApk(host: String, port: Int, apkPath: String): String = withContext(Dispatchers.IO) {
        val apk = File(apkPath)
        if (!apk.isFile) throw AdbException(AdbErrorKind.IO, "no such APK: $apkPath")
        val manager = connectedManager(host, port)
        try {
            push(manager, apk, REMOTE_APK_PATH)
            runShell(manager, "pm install -r $REMOTE_APK_PATH", AdbTimeouts.SHELL_SEC)
        } finally {
            // The pushed copy is a temporary file on a shared volume; it never outlives the install.
            runCatching { runShell(manager, "rm -f $REMOTE_APK_PATH", AdbTimeouts.SHELL_SEC) }
        }
    }

    override suspend fun uninstall(host: String, port: Int, packageName: String): String = withContext(Dispatchers.IO) {
        // The name is interpolated into a shell command, so it is validated before anything is
        // opened: `com.x; reboot` must never reach the device shell.
        if (!isValidAdbPackageName(packageName)) {
            throw AdbException(AdbErrorKind.IO, "not an Android package name: $packageName")
        }
        runShell(connectedManager(host, port), "pm uninstall $packageName", AdbTimeouts.SHELL_SEC)
    }

    override suspend fun discover(timeoutMs: Long): List<AdbDiscovery> = withContext(Dispatchers.IO) {
        if (!localNetworkPermission.hasPermission()) {
            throw AdbException(
                AdbErrorKind.PERMISSION_DENIED,
                "local network permission is required for mDNS discovery",
            )
        }
        // The mDNS callbacks arrive on a resolver thread, so the accumulator has to be thread-safe.
        val found = Collections.synchronizedSet(LinkedHashSet<AdbDiscovery>())
        val multicastLock = acquireMulticastLock()
        val listeners = AdbDiscoveryServiceType.entries.mapNotNull { type ->
            runCatching {
                AdbMdns(
                    context,
                    type.mdnsServiceType,
                    AdbMdns.OnAdbDaemonDiscoveredListener { address, port ->
                        if (address != null && port > 0) {
                            found.add(AdbDiscovery(type, address.hostAddress ?: address.toString(), port))
                        }
                    },
                ).also { it.start() }
            }.getOrNull()
        }
        try {
            // Cancelling the coroutine (the UI's "stop") lands here too and still stops the scan.
            delay(timeoutMs)
        } finally {
            listeners.forEach { mdns -> runCatching { mdns.stop() } }
            runCatching { multicastLock?.release() }
        }
        synchronized(found) { found.toList() }
    }

    private suspend fun connectedManager(host: String, port: Int): AbsAdbConnectionManager {
        val manager = managerFor(host, port)
        if (manager.isConnected) return manager
        val connected = try {
            manager.connect(host, port)
        } catch (error: Exception) {
            throw adbExceptionOf(error)
        }
        if (!connected) {
            throw AdbException(
                AdbErrorKind.TIMEOUT,
                "no connection to $host:$port within ${AdbTimeouts.CONNECT_SEC}s " +
                    "(a legacy device may still be showing its authorization dialog)",
            )
        }
        return manager
    }

    private suspend fun runShell(manager: AbsAdbConnectionManager, command: String, timeoutSec: Int): String {
        val stream = try {
            manager.openStream("shell:$command")
        } catch (error: Exception) {
            throw adbExceptionOf(error)
        }
        return readStream(stream, timeoutSec)
    }

    /**
     * Reads a stream to its end, closing it when [timeoutSec] passes.
     *
     * A blocking `read` cannot be interrupted by a coroutine timeout, so the deadline is enforced by
     * a watchdog that closes the stream: the read then fails with [IOException], and [timedOut] says
     * whether that was the deadline or a real socket error.
     */
    private suspend fun readStream(stream: AdbStream, timeoutSec: Int): String = coroutineScope {
        val timedOut = AtomicBoolean(false)
        val watchdog = launch(Dispatchers.IO) {
            delay(timeoutSec * 1000L)
            timedOut.set(true)
            runCatching { stream.close() }
        }
        try {
            val input = stream.openInputStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        } catch (error: IOException) {
            if (timedOut.get()) throw AdbException(AdbErrorKind.TIMEOUT, "command exceeded ${timeoutSec}s", error)
            throw adbExceptionOf(error)
        } finally {
            watchdog.cancel()
            runCatching { stream.close() }
        }
    }

    /** `adb push` over the `sync:` service: SEND, DATA chunks, DONE, then one response frame. */
    private fun push(manager: AbsAdbConnectionManager, apk: File, remotePath: String) {
        val stream = try {
            manager.openStream("sync:")
        } catch (error: Exception) {
            throw adbExceptionOf(error)
        }
        try {
            val output = stream.openOutputStream()
            output.write(AdbSyncProtocol.sendRequest(remotePath))
            apk.inputStream().use { input ->
                val buffer = ByteArray(AdbSyncProtocol.MAX_DATA_CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(AdbSyncProtocol.dataChunk(buffer, 0, read))
                }
            }
            output.write(AdbSyncProtocol.doneChunk((apk.lastModified() / 1000L).toInt()))
            output.flush()

            val header = ByteArray(AdbSyncProtocol.HEADER_SIZE)
            readFully(stream, header)
            when (val response = AdbSyncProtocol.parseResponse(header)) {
                is AdbSyncResponse.Okay -> Unit

                is AdbSyncResponse.Fail -> {
                    val message = ByteArray(response.messageLength.coerceAtLeast(0))
                    readFully(stream, message)
                    throw AdbException(AdbErrorKind.IO, "push to $remotePath failed: ${message.decodeToString()}")
                }
            }
        } catch (error: IOException) {
            throw adbExceptionOf(error)
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun readFully(stream: AdbStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = stream.read(target, offset, target.size - offset)
            if (read < 0) throw IOException("sync stream closed after $offset of ${target.size} bytes")
            offset += read
        }
    }

    private fun managerFor(host: String, port: Int): AbsAdbConnectionManager = synchronized(managers) {
        managers.getOrPut(adbDeviceId(host, port)) { createManager() }
    }

    private fun createManager(): AbsAdbConnectionManager = object : AbsAdbConnectionManager() {
        // Every outer property is read through `this@LibAdbBackend`: a Java getter of this object is
        // also a Kotlin synthetic property on `this`, so a bare name can silently resolve to a
        // method of the very object being built. `getDeviceName()` below is exactly that case.
        override fun getPrivateKey(): PrivateKey = this@LibAdbBackend.keys.privateKey()

        override fun getCertificate(): Certificate = this@LibAdbBackend.keys.certificate()

        // A bare `deviceName` here resolved to this override (the Java getter is the synthetic
        // property `deviceName`) and recursed until the stack overflowed — the 3.21.0 crash on the
        // first `connect()`. The property is `hostName` and it is qualified, so it cannot happen.
        override fun getDeviceName(): String = this@LibAdbBackend.hostName
    }.apply {
        // The daemon runs on the same OS family as this app, so telling it our API version lets it
        // pick the fastest protocol variant (shell_v2, features) instead of the oldest one.
        setApi(Build.VERSION.SDK_INT)
        setTimeout(AdbTimeouts.CONNECT_SEC.toLong(), TimeUnit.SECONDS)
        // A legacy 5555 device shows "Allow USB debugging?" and needs a human tap; failing fast here
        // would turn that wait into an error. Unpaired TLS daemons fail on the handshake instead.
        setThrowOnUnauthorised(false)
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching {
            wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private companion object {
        const val DEVICE_NAME = "pots"
        const val REMOTE_APK_PATH = "/data/local/tmp/pots-adb-install.apk"
        const val READ_BUFFER_SIZE = 8192
        const val MULTICAST_LOCK_TAG = "pots-adb-discovery"
        const val PAIRING_THREAD_NAME = "pots-adb-pairing"
    }
}
