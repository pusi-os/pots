package com.pusios.pots.adb

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
     * Returns true when the connection is established, false when it timed out — most often because
     * the device is showing its "Allow USB debugging?" dialog and nobody tapped it yet. TLS is
     * negotiated by the transport itself (`A_STLS`), so [tls] only records how the endpoint is
     * expected to behave; it does not change the wire protocol.
     */
    suspend fun connect(host: String, port: Int, tls: Boolean): Boolean

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
     * discovering *itself* over wireless debugging. Other devices on the LAN are added by hand with
     * their `host:port`.
     */
    suspend fun discover(timeoutMs: Long = AdbTimeouts.DISCOVERY_MS): List<AdbDiscovery>
}

/** Deadlines used by every [AdbBackend] implementation, so the UI and the agent agree on them. */
object AdbTimeouts {
    /** Establishing the socket, sending CONNECT and finishing the key exchange. */
    const val CONNECT_SEC = 15

    /** A single `shell:` command, which includes the agent's own tool timeout budget. */
    const val SHELL_SEC = 30

    /** The SPAKE2 pairing handshake, which waits for the user to read the code off the device. */
    const val PAIR_SEC = 60

    /** How long an mDNS scan listens before reporting what it has. */
    const val DISCOVERY_MS = 10_000L
}
