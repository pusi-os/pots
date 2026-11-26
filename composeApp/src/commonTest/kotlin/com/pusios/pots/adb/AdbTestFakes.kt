// Fakes shared by the ADB tool and settings-model tests: a registry in memory, a backend that
// records what it was asked to do, a gate that answers yes or no, and a cache that pretends to
// have downloaded an APK.

package com.pusios.pots.adb

internal val TEST_DEVICE = AdbDevice(
    id = "192.168.1.50:5555",
    label = "Pixel",
    host = "192.168.1.50",
    port = 5555,
    tls = false,
    trusted = true,
)

internal class FakeAdbStore(initial: List<AdbDevice> = listOf(TEST_DEVICE)) : AdbDeviceStore {
    private val devices = initial.toMutableList()

    override fun all(): List<AdbDevice> = devices.toList()

    override fun upsert(device: AdbDevice): List<AdbDevice> {
        val index = devices.indexOfFirst { it.id == device.id }
        if (index >= 0) devices[index] = device else devices.add(device)
        return devices.toList()
    }

    override fun remove(id: String): List<AdbDevice> {
        devices.removeAll { it.id == id }
        return devices.toList()
    }

    override fun setTrusted(id: String, trusted: Boolean): List<AdbDevice> {
        val index = devices.indexOfFirst { it.id == id }
        if (index >= 0) devices[index] = devices[index].copy(trusted = trusted)
        return devices.toList()
    }

    override fun touch(id: String, lastModel: String?, lastSeenEpochMs: Long): List<AdbDevice> {
        val index = devices.indexOfFirst { it.id == id }
        if (index >= 0) devices[index] = devices[index].copy(lastModel = lastModel, lastSeenEpochMs = lastSeenEpochMs)
        return devices.toList()
    }
}

internal class FakeAdbBackend : AdbBackend {
    val shellCalls = mutableListOf<String>()
    val installs = mutableListOf<String>()
    val uninstalls = mutableListOf<String>()
    val connects = mutableListOf<String>()
    val pairs = mutableListOf<String>()
    val disconnects = mutableListOf<String>()
    val firstContacts = mutableListOf<Boolean>()
    var discoverCalls = 0
    var scanCalls = 0
    var probeCalls = 0
    var connectResult = true
    var connectFailure: AdbException? = null
    var pairResult: AdbPairResult = AdbPairResult.Success
    var shellOutput = ""
    var shellFailure: AdbException? = null
    var discoverFailure: AdbException? = null
    var probeResult: AdbProbeResult = AdbProbeResult.AuthRequired
    var scanResult: List<AdbDiscovery> = emptyList()
    var connected = false

    override suspend fun pair(host: String, port: Int, code: String): AdbPairResult {
        pairs += "$host:$port"
        return pairResult
    }

    override suspend fun connect(
        host: String,
        port: Int,
        tls: Boolean,
        firstContact: Boolean,
        onPhase: (AdbConnectPhase) -> Unit,
    ): Boolean {
        connects += "$host:$port"
        firstContacts += firstContact
        onPhase(AdbConnectPhase.PROBING)
        connectFailure?.let { throw it }
        if (connectResult) {
            connected = true
            onPhase(AdbConnectPhase.CONNECTED)
        }
        return connectResult
    }

    override suspend fun probe(host: String, port: Int): AdbProbeResult {
        probeCalls++
        return probeResult
    }

    override fun isConnected(host: String, port: Int): Boolean = connected

    override suspend fun disconnect(host: String, port: Int) {
        disconnects += "$host:$port"
        connected = false
    }

    override suspend fun disconnectAll() {
        connected = false
    }

    override suspend fun shell(host: String, port: Int, command: String, timeoutSec: Int): String {
        shellCalls += command
        shellFailure?.let { throw it }
        return shellOutput
    }

    override suspend fun installApk(host: String, port: Int, apkPath: String): String {
        installs += apkPath
        return "Success"
    }

    override suspend fun uninstall(host: String, port: Int, packageName: String): String {
        uninstalls += packageName
        return "Success"
    }

    override suspend fun discover(timeoutMs: Long): List<AdbDiscovery> {
        discoverCalls++
        discoverFailure?.let { throw it }
        return emptyList()
    }

    override suspend fun scanLan(port: Int): List<AdbDiscovery> {
        scanCalls++
        return scanResult
    }
}

internal class RecordingAdbGate(private val allowed: Boolean = true) : AdbConfirmation {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun confirm(tool: String, summary: String): Boolean {
        calls += tool to summary
        return allowed
    }
}

internal class FakeAdbApkCache : AdbApkCache {
    val urls = mutableListOf<String>()

    override suspend fun download(url: String): String {
        urls += url
        return "/data/cache/install.apk"
    }
}
