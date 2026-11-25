package com.pusios.pots.adb

/** A daemon found by an mDNS scan. */
data class AdbDiscovery(val serviceType: AdbDiscoveryServiceType, val host: String, val port: Int)

/**
 * The three service types adbd advertises, as the short names the mDNS resolver formats into
 * `_<name>._tcp`. [mdnsServiceType] is that short name, not the full domain.
 */
enum class AdbDiscoveryServiceType(val mdnsServiceType: String) {
    /** `_adb-tls-connect._tcp` — an already paired daemon accepting TLS connections. */
    TLS_CONNECT("adb-tls-connect"),

    /** `_adb-tls-pairing._tcp` — the pairing window, open only while the device shows its code. */
    TLS_PAIRING("adb-tls-pairing"),

    /** `_adb._tcp` — legacy adbd over plain TCP, usually port 5555. */
    LEGACY_TCP("adb"),
}
