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
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
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
 * the sync framing, the probe packet — lives in commonMain with tests; this class only moves bytes.
 *
 * Every step writes one line to [PotsAdbLog] under the `PotsAdb` tag. Before 3.21.1 an ADB attempt
 * left no trace anywhere, and a silent failure on a TV took an hour of packet-level probing to
 * explain; the log is the difference between that and `adb logcat -s PotsAdb`.
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

    /** Guards the "one LAN scan at a time" rule; two scans would double the probes for no gain. */
    private val scanInProgress = AtomicBoolean(false)

    override suspend fun pair(host: String, port: Int, code: String): AdbPairResult = withContext(Dispatchers.IO) {
        PotsAdbLog.step("pair", "pairing with $host:$port")
        try {
            // Pairing opens a plain socket with no connect or handshake timeout, so the deadline has
            // to come from outside: the coroutine machinery cannot interrupt a blocking call.
            val paired = callWithDeadline(AdbTimeouts.PAIR_SEC * 1000L) { managerFor(host, port).pair(host, port, code) }
            val result = when (paired) {
                null -> AdbPairResult.Timeout
                true -> AdbPairResult.Success
                false -> AdbPairResult.IoError("the device refused the pairing")
            }
            PotsAdbLog.step("pair", "$host:$port -> $result")
            result
        } catch (error: Exception) {
            val result = adbPairResultOf(error)
            PotsAdbLog.step("pair", "$host:$port failed -> $result (${error.message})")
            result
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

    override suspend fun connect(
        host: String,
        port: Int,
        tls: Boolean,
        firstContact: Boolean,
        onPhase: (AdbConnectPhase) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val id = adbDeviceId(host, port)
        val manager = managerFor(host, port)
        if (manager.isConnected) {
            PotsAdbLog.step("connect", "$id is already connected")
            onPhase(AdbConnectPhase.CONNECTED)
            return@withContext true
        }

        PotsAdbLog.step("connect", "$id tls=$tls firstContact=$firstContact")
        onPhase(AdbConnectPhase.PROBING)
        val probe = probeInternal(host, port, log = true)
        if (!adbProbeIsDaemon(probe)) {
            PotsAdbLog.step("connect", "$id refused: ${adbProbeSummary(probe)}")
            throw adbProbeException(host, port, probe)
        }
        if (tls != (probe is AdbProbeResult.TlsRequired)) {
            // The label is a hint, the wire is the truth: `connect` follows A_STLS and nothing else,
            // so a mismatch is worth a line but never a reason to refuse a working endpoint.
            PotsAdbLog.step("connect", "$id is labelled tls=$tls but answers $probe — following the wire")
        }

        val timeoutSec = adbConnectTimeoutSec(probe, firstContact)
        if (probe is AdbProbeResult.AuthRequired) {
            PotsAdbLog.step(
                "waiting",
                "$id asked for a key; waiting up to ${timeoutSec}s for the owner to tap «Allow» on the device",
            )
            onPhase(AdbConnectPhase.WAITING_FOR_CONFIRMATION)
        }
        manager.setTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        val connected = try {
            manager.connect(host, port)
        } catch (error: Exception) {
            PotsAdbLog.step("connect", "$id failed: ${error::class.simpleName}: ${error.message}")
            throw adbExceptionOf(error)
        }
        if (!connected) {
            // Close explicitly: the transport is abandoned by the library either way, but the device
            // keeps its prompt alive until the socket is gone, and a half-dead prompt blocks the next
            // attempt (AOSP dispatches one prompt at a time).
            PotsAdbLog.step("timeout", "$id did not confirm within ${timeoutSec}s; closing the transport")
            runCatching { manager.disconnect() }
            throw AdbException(
                AdbErrorKind.TIMEOUT,
                "the device did not confirm the connection within ${timeoutSec}s — if its «Allow debugging» " +
                    "dialog is on screen, tap «Allow» (and tick «Always allow from this computer»); if a dialog " +
                    "was left over from an earlier attempt, press «Cancel» on the device first, then connect once more",
            )
        }
        PotsAdbLog.step("connected", "$id authorized")
        onPhase(AdbConnectPhase.CONNECTED)
        true
    }

    override suspend fun probe(host: String, port: Int): AdbProbeResult = probeInternal(host, port, log = true)

    /**
     * One `CNXN` on a throwaway socket.
     *
     * `log = false` is the LAN scan: 254 lines about closed ports would push the one interesting
     * attempt out of the ring buffer. The scan logs its own summary instead.
     *
     * [timeoutMs] bounds both the TCP connect and the wait for the reply header. A manual probe can
     * afford [AdbTimeouts.PROBE_SEC]; the scan passes [AdbTimeouts.SCAN_HOST_TIMEOUT_MS], because a
     * silent host must not hold a scan wave for seconds.
     */
    private suspend fun probeInternal(
        host: String,
        port: Int,
        log: Boolean,
        timeoutMs: Int = AdbTimeouts.PROBE_SEC * 1000,
    ): AdbProbeResult = withContext(Dispatchers.IO) {
        var tcpConnected = false
        var packetSize = 0
        var replySize = 0
        val result = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                tcpConnected = true
                val packet = AdbProbeProtocol.connectPacket()
                packetSize = packet.size
                socket.soTimeout = timeoutMs
                socket.getOutputStream().apply {
                    write(packet)
                    flush()
                }
                val reply = readUpTo(socket.getInputStream(), AdbProbeProtocol.HEADER_SIZE)
                replySize = reply.size
                if (reply.isEmpty()) AdbProbeResult.Silent else AdbProbeProtocol.parseReply(reply)
            }
        } catch (_: SocketTimeoutException) {
            // A read timeout after a successful connect is the daemon going quiet, not a dead host.
            if (tcpConnected) AdbProbeResult.Silent else AdbProbeResult.Timeout
        } catch (error: Exception) {
            adbProbeFailureOf(error)
        }
        if (log) {
            PotsAdbLog.step(
                "probe",
                "$host:$port tcp=${if (tcpConnected) "ok" else "failed"} cnxn=$packetSize B reply=$replySize B -> $result",
            )
        }
        result
    }

    override fun isConnected(host: String, port: Int): Boolean = synchronized(managers) {
        managers[adbDeviceId(host, port)]?.isConnected == true
    }

    override suspend fun disconnect(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            val manager = synchronized(managers) { managers.remove(adbDeviceId(host, port)) } ?: return@withContext
            PotsAdbLog.step("disconnect", adbDeviceId(host, port))
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
            if (open.isNotEmpty()) PotsAdbLog.step("disconnect", "closing ${open.size} session(s)")
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
        PotsAdbLog.step("mdns", "listening for ${listeners.size} service type(s) for ${timeoutMs}ms")
        try {
            // Cancelling the coroutine (the UI's "stop") lands here too and still stops the scan.
            delay(timeoutMs)
        } finally {
            listeners.forEach { mdns -> runCatching { mdns.stop() } }
            runCatching { multicastLock?.release() }
        }
        val result = synchronized(found) { found.toList() }
        PotsAdbLog.step("mdns", "found ${result.size} daemon(s)")
        result
    }

    override suspend fun scanLan(port: Int): List<AdbDiscovery> = withContext(Dispatchers.IO) {
        if (!localNetworkPermission.hasPermission()) {
            throw AdbException(
                AdbErrorKind.PERMISSION_DENIED,
                "local network permission is required for a LAN scan",
            )
        }
        if (!scanInProgress.compareAndSet(false, true)) {
            throw AdbException(AdbErrorKind.IO, "a LAN scan is already running")
        }
        try {
            val hosts = localSubnetHosts()
            if (hosts.isEmpty()) {
                PotsAdbLog.step("scan", "no private IPv4 interface to scan")
                return@withContext emptyList()
            }
            PotsAdbLog.step("scan", "probing ${hosts.size} hosts on port $port")
            val perHostMs = AdbTimeouts.SCAN_HOST_TIMEOUT_MS.toInt()
            val found = scanAdbHosts(hosts) { host -> probeInternal(host, port, log = false, timeoutMs = perHostMs) }
            PotsAdbLog.step("scan", "found ${found.size} on port $port: ${found.joinToString().ifEmpty { "none" }}")
            found.map { host -> AdbDiscovery(AdbDiscoveryServiceType.LEGACY_TCP, host, port) }
        } finally {
            scanInProgress.set(false)
        }
    }

    /**
     * The /24 this phone sits in, one entry per usable private IPv4 interface.
     *
     * A /24 rather than the interface's real prefix: home LANs are /24 and probing a /16 would be
     * 65 534 sockets for the one TV on the shelf. The phone's own address is skipped, and the list
     * is capped at [AdbTimeouts.SCAN_MAX_HOSTS] so a multi-interface device cannot blow it up.
     */
    private fun localSubnetHosts(): List<String> {
        val hosts = LinkedHashSet<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (networkInterface in Collections.list(interfaces)) {
            val usable = runCatching {
                networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isPointToPoint
            }.getOrDefault(false)
            if (!usable) continue
            for (address in Collections.list(networkInterface.inetAddresses)) {
                val ipv4 = address as? Inet4Address ?: continue
                val host = ipv4.hostAddress ?: continue
                if (!isPrivateAdbHost(host)) continue
                val prefix = host.substringBeforeLast('.', missingDelimiterValue = "")
                if (prefix.isEmpty()) continue
                for (last in 1..254) {
                    val candidate = "$prefix.$last"
                    if (candidate != host) hosts += candidate
                    if (hosts.size >= AdbTimeouts.SCAN_MAX_HOSTS) return hosts.toList()
                }
            }
        }
        return hosts.toList()
    }

    private suspend fun connectedManager(host: String, port: Int): AbsAdbConnectionManager {
        val manager = managerFor(host, port)
        if (manager.isConnected) return manager
        // Reuse the full attempt, so a shell on a sleeping device reports the same probe verdict and
        // the same long authorization window as a manual connect instead of a bare timeout.
        connect(host, port, tls = port != ADB_LEGACY_PORT)
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

    /** Reads up to [size] bytes, returning what arrived before EOF — a daemon may close early. */
    private fun readUpTo(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) break
            offset += read
        }
        return buffer.copyOf(offset)
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
        // The API level only selects the protocol version and the feature set we advertise; an old
        // daemon (Android 11 TV, protocol 0x01000000) ignores the newer features and answers in the
        // dialect it knows, so a high value here does not break legacy devices.
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
