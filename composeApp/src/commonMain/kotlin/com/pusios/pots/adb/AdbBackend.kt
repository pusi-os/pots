package com.pusios.pots.adb

/** The port a legacy `adb tcpip` daemon listens on; TVs, Fire TV and Android 10 phones. */
const val ADB_LEGACY_PORT = 5555

/** What a [AdbBackend.connect] attempt is doing right now, for the screen's progress text. */
enum class AdbConnectPhase {
    /** A probe of the port is running: TCP, then one `CNXN`. */
    PROBING,

    /** The daemon asked for a key and the owner has to tap «Allow» on its screen. */
    WAITING_FOR_CONFIRMATION,

    /** The connection is authorized and open. */
    CONNECTED,
}

/** The per-device state the settings screen shows next to an endpoint. */
enum class AdbDeviceState {
    /** Nothing was attempted in this session. */
    IDLE,

    /** A probe or a connection attempt is in flight. */
    PROBING,

    /** The device shows its authorization dialog and the app is waiting for the owner. */
    WAITING_FOR_CONFIRMATION,

    /** The connection is open. */
    CONNECTED,

    /** The last attempt failed; the reason is in the status detail. */
    ERROR,
}

/**
 * Everything the app can do to an ADB daemon, behind one interface.
 *
 * The interface exists so the transport can be swapped: phase 1 is [LibAdbBackend] (a pure Kotlin
 * client over `libadb-android`), and a future phase can bundle the `adb` binary and implement the
 * same calls on top of it. Nothing above this interface knows which one is in use.
 *
 * Every method is suspending and may run on a device that is slow, asleep or gone: failures surface
 * as [AdbException] carrying an [AdbErrorKind] the caller can act on, except [pair], which returns a
 * [AdbPairResult] because pairing failures are a normal part of the flow rather than an exception.
 */
interface AdbBackend {
    /**
     * Pairs with a daemon that is showing its six-digit code (wireless debugging).
     *
     * Pairing does not connect; call [connect] with the same host and the *connect* port afterwards.
     * The pairing port is a separate, short-lived one from the connect port.
     */
    suspend fun pair(host: String, port: Int, code: String): AdbPairResult

    /**
     * Connects to the daemon at [host]:[port], keeping the connection for later [shell] calls.
     *
     * The attempt starts with [probe], so a closed port, a foreign service and a device that is
     * waiting for a tap are reported as themselves instead of as one silent timeout. The deadline
     * depends on what the probe found: an endpoint that answered `A_AUTH(TOKEN)` is waiting for a
     * human, so it gets [AdbTimeouts.CONNECT_FIRST_SEC] (two minutes); an endpoint whose key is
     * already known gets [AdbTimeouts.CONNECT_SEC].
     *
     * [firstContact] says the device is not known to hold our key (a TV on 5555, a phone added by
     * address). It widens the window for the endpoints whose authorization path the wire does not
     * reveal, so the owner is never cut off mid-reach for the remote.
     *
     * [tls] is the endpoint's label, not a wire switch: `libadb` starts plaintext and upgrades to
     * TLS only when the daemon answers `A_STLS`, which is exactly the wireless-debugging case. The
     * library's own `connectTcp`/`connectTls` take a `Context` because they discover a daemon over
     * mDNS and ignore `host`/`port`, so they cannot serve a manually entered endpoint. What the
     * parameter does do is make a mismatch visible: the probe result is compared against it and
     * logged, so a "TLS" endpoint that speaks plaintext is a line in the log instead of a mystery.
     *
     * Returns true when the connection is established. A timeout is an [AdbException] with
     * [AdbErrorKind.TIMEOUT] and a text that says the device did not confirm the connection — the
     * old `false` return made every failure look like "no connection".
     *
     * Never retries by itself: a second attempt while the first dialog is still on the device's
     * screen only queues another prompt (AOSP keeps one prompt at a time), so retrying is the
     * owner's decision.
     */
    suspend fun connect(
        host: String,
        port: Int,
        tls: Boolean,
        firstContact: Boolean = false,
        onPhase: (AdbConnectPhase) -> Unit = {},
    ): Boolean

    /**
     * Sends one `CNXN` on a throwaway socket and reports what came back.
     *
     * It never presents a key, so it cannot make the device show an authorization dialog: a bare
     * `CNXN` is answered with `A_AUTH(TOKEN)` before any prompt exists. This is the primitive behind
     * the screen's «Диагностика» button and the agent's `adb_diagnose` tool.
     */
    suspend fun probe(host: String, port: Int): AdbProbeResult

    /** True when a session to [host]:[port] is open right now. */
    fun isConnected(host: String, port: Int): Boolean

    /** Drops the connection to [host]:[port], if any. Never throws. */
    suspend fun disconnect(host: String, port: Int)

    /** Drops every open connection. Never throws. */
    suspend fun disconnectAll()

    /**
     * Runs [command] in the device shell (`shell:` service) and returns its combined stdout/stderr.
     *
     * The shell runs as uid 2000 (`shell`), not root. [timeoutSec] is a wall-clock deadline: when it
     * passes the stream is closed and [AdbException] with [AdbErrorKind.TIMEOUT] is thrown.
     */
    suspend fun shell(
        host: String,
        port: Int,
        command: String,
        timeoutSec: Int = AdbTimeouts.SHELL_SEC,
    ): String

    /**
     * Pushes the APK at [apkPath] (a path on *this* device) to `/data/local/tmp` and runs
     * `pm install -r` on it. Returns the `pm` output; the temporary remote file is always removed.
     */
    suspend fun installApk(host: String, port: Int, apkPath: String): String

    /**
     * Runs `pm uninstall` for [packageName] and returns the `pm` output.
     *
     * [packageName] is validated as a plain package id first: it is the only argument in this
     * interface that goes into a shell command verbatim, and `pm uninstall com.x; reboot` would run
     * two commands.
     */
    suspend fun uninstall(host: String, port: Int, packageName: String): String

    /**
     * Scans mDNS for ADB daemons for [timeoutMs] and returns everything found.
     *
     * Only daemons reachable on this device's own addresses are reported — that is a limitation of
     * the mDNS resolver in the transport, which matches `adb connect` semantics for a device
     * discovering *itself* over wireless debugging. Other devices on the LAN are found by [scanLan]
     * or added by hand with their `host:port`.
     */
    suspend fun discover(timeoutMs: Long = AdbTimeouts.DISCOVERY_MS): List<AdbDiscovery>

    /**
     * Probes the /24 this phone sits in on [port] and returns every host that answers as an ADB
     * daemon — the discovery that actually finds a TV, because mDNS does not.
     *
     * Bounded by [AdbTimeouts.SCAN_CONCURRENCY] probes in flight, [AdbTimeouts.SCAN_HOST_TIMEOUT_MS]
     * per host and [AdbTimeouts.SCAN_MAX_HOSTS] hosts; cancellable from the caller's coroutine; at
     * most one scan runs at a time. Only private/LAN addresses are scanned, never the WAN.
     */
    suspend fun scanLan(port: Int = ADB_LEGACY_PORT): List<AdbDiscovery>
}

/** Deadlines used by every [AdbBackend] implementation, so the UI and the agent agree on them. */
object AdbTimeouts {
    /** Establishing the socket to a device that already holds our key, and finishing the handshake. */
    const val CONNECT_SEC = 15

    /**
     * The first connection to a device that does not hold our key: the daemon shows its
     * «Allow debugging?» dialog and a human has to reach the remote, read it and tap. Measured on a
     * real TV: 2–5 s once the owner is at the screen, but the walk to the remote took longer than
     * the old 15 s, which is why the socket used to close before the answer arrived.
     */
    const val CONNECT_FIRST_SEC = 120

    /** One TCP probe: connect, send `CNXN`, wait for the header of the reply. */
    const val PROBE_SEC = 3

    /** A single `shell:` command, which includes the agent's own tool timeout budget. */
    const val SHELL_SEC = 30

    /** The SPAKE2 pairing handshake, which waits for the user to read the code off the device. */
    const val PAIR_SEC = 60

    /** How long an mDNS scan listens before reporting what it has. */
    const val DISCOVERY_MS = 10_000L

    /** How many hosts a LAN scan probes at once: 254 hosts finish in a couple of seconds. */
    const val SCAN_CONCURRENCY = 32

    /**
     * Per-host deadline during a scan: both the TCP connect and the wait for the reply to `CNXN`.
     * A host that does not answer this fast is not ADB, and a silent one must not hold a wave for
     * the [PROBE_SEC] a manual probe allows.
     */
    const val SCAN_HOST_TIMEOUT_MS = 300L

    /** Upper bound on the addresses one scan touches, so a /16 network cannot turn into a flood. */
    const val SCAN_MAX_HOSTS = 254
}
