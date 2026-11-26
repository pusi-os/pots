// The one-question probe that runs before any ADB connection: "does this port answer as an ADB
// daemon, and does it already know our key?" Everything here is pure byte handling and error
// classification, so a reply can be tested without a device; the socket that carries it lives in
// androidMain (LibAdbBackend.probe).
//
// Why a probe at all: libadb's connect() only reports true/false after its whole deadline, so a
// closed port, a foreign service and a device that is waiting for its owner to tap "Allow" all look
// identical — a silent timeout. The probe tells them apart in about a second.

package com.pusios.pots.adb

/** What a single TCP probe of an ADB port found. */
sealed interface AdbProbeResult {
    /** The daemon answered `A_AUTH(TOKEN)`: it is alive and our key is not authorized yet. */
    data object AuthRequired : AdbProbeResult

    /** The daemon answered `A_STLS`: a wireless-debugging TLS server, so it must be paired first. */
    data object TlsRequired : AdbProbeResult

    /** The daemon answered `A_CNXN`: the connection is authorized with the key we already have. */
    data object Connected : AdbProbeResult

    /** TCP was accepted but nothing came back within the probe window. */
    data object Silent : AdbProbeResult

    /** Nothing listens on the port (ECONNREFUSED). */
    data object PortClosed : AdbProbeResult

    /** The host does not answer at all: no route, unknown host, network unreachable. */
    data object NoRoute : AdbProbeResult

    /** The TCP connection itself did not complete in time. */
    data object Timeout : AdbProbeResult

    /** Bytes came back, but they are not an ADB packet. */
    data class NotAdb(val detail: String) : AdbProbeResult

    /** The probe could not run to the point of a verdict. */
    data class Failed(val detail: String) : AdbProbeResult
}

/** True when something on the other side is a live ADB daemon, authorized or not. */
fun adbProbeIsDaemon(result: AdbProbeResult): Boolean = when (result) {
    AdbProbeResult.AuthRequired, AdbProbeResult.TlsRequired, AdbProbeResult.Connected -> true
    else -> false
}

/**
 * The wire format of the probe: a bare `CNXN` packet and the parser for whatever comes back.
 *
 * Only `CNXN` is ever sent. `A_AUTH(TOKEN)` is the daemon's answer to it, and the dialog on the
 * device appears only after the client presents its public key — a probe that stops at `CNXN`
 * therefore never creates a prompt the owner would have to dismiss.
 */
object AdbProbeProtocol {
    /** ADB packets start with a 24-byte header; everything after it is payload. */
    const val HEADER_SIZE = 24

    /** The largest payload we ask the daemon to send. */
    const val MAX_DATA = 4096

    /** Protocol version of Android 9+; old daemons accept it and answer in kind. */
    const val VERSION = 0x01000000

    /** `CNXN` — the connect packet. */
    const val A_CNXN = 0x4E584E43

    /** `AUTH` — the daemon asks for a signature or a public key. */
    const val A_AUTH = 0x48545541

    /** `STLS` — the daemon wants the connection upgraded to TLS before anything else. */
    const val A_STLS = 0x534C5453

    /** The `CNXN` payload every `adb` client sends: the host banner, NUL-terminated. */
    const val CONNECT_PAYLOAD = "host::\u0000"

    /** The `CNXN` packet to send: version, max payload, the host banner and its checksum. */
    fun connectPacket(): ByteArray {
        val payload = CONNECT_PAYLOAD.encodeToByteArray()
        val packet = ByteArray(HEADER_SIZE + payload.size)
        putIntLe(packet, 0, A_CNXN)
        putIntLe(packet, 4, VERSION)
        putIntLe(packet, 8, MAX_DATA)
        putIntLe(packet, 12, payload.size)
        putIntLe(packet, 16, checksum(payload))
        putIntLe(packet, 20, A_CNXN xor -1)
        payload.copyInto(packet, HEADER_SIZE)
        return packet
    }

    /**
     * Classifies a reply header.
     *
     * The magic field is checked first: the daemon drops a transport whose magic is not
     * `command xor 0xFFFFFFFF` without a word, so a header that arrived with a wrong magic is not
     * an ADB daemon talking — it is a foreign service or a corrupt stream, and calling it
     * "authorization pending" would send the owner hunting for a dialog that will never appear.
     */
    fun parseReply(bytes: ByteArray): AdbProbeResult {
        if (bytes.size < HEADER_SIZE) {
            return AdbProbeResult.NotAdb("a reply shorter than an ADB header (${bytes.size} B)")
        }
        val command = intLe(bytes, 0)
        val magic = intLe(bytes, 20)
        if (magic != command xor -1) {
            return AdbProbeResult.NotAdb(
                "bad packet magic 0x${magic.toUInt().toString(16)} for command " +
                    "0x${command.toUInt().toString(16)}",
            )
        }
        return when (command) {
            A_AUTH -> AdbProbeResult.AuthRequired
            A_STLS -> AdbProbeResult.TlsRequired
            A_CNXN -> AdbProbeResult.Connected
            else -> AdbProbeResult.NotAdb("unexpected ADB packet 0x${command.toUInt().toString(16)}")
        }
    }

    private fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (byte in payload) sum += byte.toInt() and 0xFF
        return sum
    }

    private fun putIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun intLe(source: ByteArray, offset: Int): Int = (source[offset].toInt() and 0xFF) or
        ((source[offset + 1].toInt() and 0xFF) shl 8) or
        ((source[offset + 2].toInt() and 0xFF) shl 16) or
        ((source[offset + 3].toInt() and 0xFF) shl 24)
}

/**
 * Turns a socket failure into a probe verdict by the names of the exception types in its chain.
 *
 * Name-based for the same reason as [adbErrorKindOf]: the mapping has to be testable in commonMain
 * while the exception types it recognises only exist on the JVM. `ConnectException` covers both
 * "refused" and "network unreachable" on Android, so its message is the tie-breaker.
 */
fun adbProbeFailureOf(error: Throwable): AdbProbeResult {
    var current: Throwable? = error
    while (current != null) {
        when (current::class.simpleName) {
            "ConnectException" -> {
                val message = current.message.orEmpty().lowercase()
                val noRoute = "unreachable" in message || "no route" in message
                return if (noRoute) AdbProbeResult.NoRoute else AdbProbeResult.PortClosed
            }

            "NoRouteToHostException", "UnknownHostException", "UnresolvedAddressException" ->
                return AdbProbeResult.NoRoute

            "SocketTimeoutException", "InterruptedIOException" -> return AdbProbeResult.Timeout
        }
        current = current.cause
    }
    return AdbProbeResult.Failed(error.message ?: error::class.simpleName.orEmpty())
}

/**
 * The English sentence the agent and the log use for [result]. The UI has its own localised text;
 * this one exists so a tool result never says "failed" without saying what happened.
 */
fun adbProbeSummary(result: AdbProbeResult): String = when (result) {
    AdbProbeResult.AuthRequired ->
        "the port answers as ADB and waits for the device owner to confirm this phone's key"

    AdbProbeResult.TlsRequired ->
        "the port answers as a wireless-debugging ADB daemon over TLS; the device must be paired first"

    AdbProbeResult.Connected -> "the port answers as ADB and already trusts this phone's key"

    AdbProbeResult.Silent ->
        "the port accepts TCP but never answers an ADB CNXN packet — it is not an ADB daemon, " +
            "or a stuck authorization prompt on the device is holding the answer"

    AdbProbeResult.PortClosed -> "nothing listens on that port"

    AdbProbeResult.NoRoute -> "the host does not answer at all (no route)"

    AdbProbeResult.Timeout -> "the TCP connection was not established in time"

    is AdbProbeResult.NotAdb -> "the port answered, but not with an ADB packet: ${result.detail}"

    is AdbProbeResult.Failed -> "the probe failed: ${result.detail}"
}

/** The hint that goes next to [adbProbeSummary] in a tool result, or null when none is needed. */
fun adbProbeHint(result: AdbProbeResult): String? = when (result) {
    AdbProbeResult.AuthRequired ->
        "ask the owner to tap «Allow» on the device screen (and tick «Always allow from this " +
            "computer»), then call adb_connect once — a second attempt before that only queues " +
            "another dialog"

    AdbProbeResult.TlsRequired -> "the owner has to pair this device in Settings → ADB devices"

    AdbProbeResult.Connected -> null

    AdbProbeResult.Silent, is AdbProbeResult.NotAdb ->
        "check that «Network debugging»/«ADB debugging» is enabled on the device and that the " +
            "port is the ADB one (5555 for legacy devices)"

    AdbProbeResult.PortClosed, AdbProbeResult.NoRoute, AdbProbeResult.Timeout ->
        "check the address, that the device is on the same LAN, and that its ADB port is open"

    is AdbProbeResult.Failed -> "check the local network permission and try again"
}

/**
 * True when the host answered on the port at all, whether as ADB or as something else. A silent or
 * foreign service is reachable but unusable; a closed port or a missing route is not reachable.
 */
fun adbProbeTcpReachable(result: AdbProbeResult): Boolean = when (result) {
    AdbProbeResult.PortClosed, AdbProbeResult.NoRoute, AdbProbeResult.Timeout -> false
    is AdbProbeResult.Failed -> false
    else -> true
}

/**
 * How long the transport may wait for the handshake after [result].
 *
 * The only case that needs minutes is a daemon that asked for a key: the answer is a human walking
 * to the device and tapping «Allow», not a network round trip. A device that already trusts us (or
 * a TLS daemon that must be paired) either answers in seconds or never will.
 */
fun adbConnectTimeoutSec(result: AdbProbeResult, firstContact: Boolean): Int = when (result) {
    AdbProbeResult.AuthRequired -> AdbTimeouts.CONNECT_FIRST_SEC
    AdbProbeResult.TlsRequired -> if (firstContact) AdbTimeouts.CONNECT_FIRST_SEC else AdbTimeouts.CONNECT_SEC
    else -> AdbTimeouts.CONNECT_SEC
}

/**
 * The exception a caller should see when the probe says the endpoint cannot be used at all.
 *
 * It exists so every transport failure carries the reason the probe measured instead of the
 * generic "no connection" that made the 3.21.0 TV report impossible to diagnose.
 */
fun adbProbeException(host: String, port: Int, result: AdbProbeResult): AdbException = when (result) {
    AdbProbeResult.PortClosed ->
        AdbException(AdbErrorKind.UNREACHABLE, "nothing listens on $host:$port — the port is closed")

    AdbProbeResult.NoRoute ->
        AdbException(AdbErrorKind.UNREACHABLE, "no route to $host — the device is not reachable on this network")

    AdbProbeResult.Timeout ->
        AdbException(AdbErrorKind.UNREACHABLE, "no TCP answer from $host:$port within ${AdbTimeouts.PROBE_SEC}s")

    AdbProbeResult.Silent, is AdbProbeResult.NotAdb ->
        AdbException(AdbErrorKind.NOT_ADB, "$host:$port accepts TCP but does not answer as ADB: ${adbProbeSummary(result)}")

    is AdbProbeResult.Failed ->
        AdbException(AdbErrorKind.IO, "the ADB probe of $host:$port failed: ${result.detail}")

    AdbProbeResult.AuthRequired, AdbProbeResult.TlsRequired, AdbProbeResult.Connected ->
        AdbException(AdbErrorKind.IO, "the ADB probe of $host:$port is not a failure: ${adbProbeSummary(result)}")
}
