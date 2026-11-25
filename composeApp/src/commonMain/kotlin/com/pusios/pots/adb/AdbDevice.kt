package com.pusios.pots.adb

import kotlinx.serialization.Serializable

/**
 * One ADB endpoint the user has added, as persisted in `filesDir/adb/devices.json`.
 *
 * [trusted] records how the device got here, and it is a security boundary, not a hint: a device
 * that was paired with its six-digit code is trusted and may be driven with any command, while a
 * device that was merely connected by `host:port` (a TV or an old phone with `adb tcpip`) is not,
 * and the agent may only run read-only commands on it.
 */
@Serializable
data class AdbDevice(
    /** Stable identity of the endpoint, see [adbDeviceId]. */
    val id: String,
    /** What the user calls it; the model name once we have talked to the device. */
    val label: String,
    val host: String,
    val port: Int,
    /** True when the endpoint speaks the wireless-debugging TLS protocol rather than legacy TCP. */
    val tls: Boolean,
    /** True only when the key was exchanged through pairing (see the class doc). */
    val trusted: Boolean,
    /** `ro.product.model` of the device, filled in after the first successful connection. */
    val lastModel: String? = null,
    /** Wall-clock time of the last successful connection, in epoch milliseconds. */
    val lastSeenEpochMs: Long = 0L,
) {
    /** `host:port`, the form the agent tools accept. */
    val endpoint: String get() = "$host:$port"
}

/** The registry id for an endpoint. One device can be reachable on several ports, so both matter. */
fun adbDeviceId(host: String, port: Int): String = "$host:$port"
