package com.pusios.pots.adb

/** Why an ADB operation failed, reduced to the cases the UI and the agent can act on. */
enum class AdbErrorKind {
    /** The daemon rejected our key: wrong pairing code, or the key was revoked on the device. */
    AUTHENTICATION_FAILED,

    /** The daemon wants the pairing handshake first — wireless debugging has not been paired yet. */
    PAIRING_REQUIRED,

    /** The operation ran past its deadline (see [AdbTimeouts]). */
    TIMEOUT,

    /** Nothing listens on that host and port, or the host does not resolve. */
    UNREACHABLE,

    /** Android blocked the call: the local-network permission was never granted. */
    PERMISSION_DENIED,

    /** Anything else that came out of the socket or stream layer. */
    IO,
}

/** An ADB failure carrying the [kind] the caller switches on instead of parsing messages. */
class AdbException(
    val kind: AdbErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Result of a pairing attempt.
 *
 * Pairing is the one flow where failure is expected and needs different words per case: a wrong code
 * is the user's typo, [PairingRequiredAgain] means the key was revoked on the device (pair again,
 * do not report "no connection"), and [TargetNotFound] means the pairing window closed.
 */
sealed class AdbPairResult {
    data object Success : AdbPairResult()

    data object InvalidCode : AdbPairResult()

    data object TargetNotFound : AdbPairResult()

    data object Timeout : AdbPairResult()

    data object PairingRequiredAgain : AdbPairResult()

    data class IoError(val message: String) : AdbPairResult()
}

/**
 * Classifies [error] by the *names* of the exception types in its cause chain.
 *
 * This is deliberately name-based rather than `is`-based: the mapping has to live in commonMain to
 * be testable, while the exception types it recognises (`AdbAuthenticationFailedException`,
 * `AdbPairingRequiredException`, `java.net.ConnectException`, …) only exist in androidMain. The
 * names are the library's public class names and the JDK's, so they survive the transport swap —
 * a future bundled-binary backend maps its own errors to [AdbErrorKind] and never reaches here.
 */
fun adbErrorKindOf(error: Throwable): AdbErrorKind {
    var current: Throwable? = error
    while (current != null) {
        when (current::class.simpleName) {
            "AdbAuthenticationFailedException" -> return AdbErrorKind.AUTHENTICATION_FAILED
            "AdbPairingRequiredException" -> return AdbErrorKind.PAIRING_REQUIRED
            "InterruptedException", "TimeoutException", "SocketTimeoutException" -> return AdbErrorKind.TIMEOUT
            "ConnectException", "UnknownHostException", "NoRouteToHostException" -> return AdbErrorKind.UNREACHABLE
            "SecurityException" -> return AdbErrorKind.PERMISSION_DENIED
        }
        current = current.cause
    }
    return AdbErrorKind.IO
}

/** [AdbPairResult] for a failed pairing, so the UI never sees a raw exception from this path. */
fun adbPairResultOf(error: Throwable): AdbPairResult = when (val kind = adbErrorKindOf(error)) {
    AdbErrorKind.AUTHENTICATION_FAILED -> AdbPairResult.InvalidCode
    AdbErrorKind.PAIRING_REQUIRED -> AdbPairResult.PairingRequiredAgain
    AdbErrorKind.TIMEOUT -> AdbPairResult.Timeout
    AdbErrorKind.UNREACHABLE -> AdbPairResult.TargetNotFound
    AdbErrorKind.PERMISSION_DENIED -> AdbPairResult.IoError("local network permission denied")
    AdbErrorKind.IO -> AdbPairResult.IoError(error.message ?: "pairing failed")
}

/** Wraps [error] into an [AdbException] of the classified kind, keeping the original as the cause. */
fun adbExceptionOf(error: Throwable): AdbException = if (error is AdbException) {
    error
} else {
    AdbException(adbErrorKindOf(error), error.message ?: error::class.simpleName.orEmpty(), error)
}
