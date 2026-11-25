// Which addresses the agent may reach: private IPv4 (10/8, 172.16/12, 192.168/16), IPv6 unique
// local (fc00::/7) and loopback. Hostnames are refused because their answer depends on DNS, not on
// the text the model sent; there is no internet relay, by design.

package com.pusios.pots.adb

/**
 * True when [host] is a LAN or loopback address — the only kind of target the ADB tools may
 * reach.
 *
 * ADB over a WAN address would turn the phone into a relay into somebody else's network, so the
 * check is an allowlist of the private ranges the owner's own devices live in: 10/8, 172.16/12,
 * 192.168/16, the IPv6 unique-local block fc00::/7, and loopback (`127.0.0.0/8`, `::1`,
 * `localhost`) for a device that runs its own daemon. Hostnames are rejected: resolving one
 * would make the answer depend on DNS rather than on the text the model sent.
 */
fun isPrivateAdbHost(host: String): Boolean {
    val text = host.trim().removeSurrounding("[", "]").lowercase()
    if (text.isEmpty()) return false
    if (text == "localhost") return true
    return if (text.contains(':')) isPrivateAdbIpv6(text) else isPrivateAdbIpv4(text)
}

private fun isPrivateAdbIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false }
    return when {
        octets[0] == 10 -> true
        octets[0] == 127 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        else -> false
    }
}

private fun isPrivateAdbIpv6(host: String): Boolean {
    // A bare `::1` has an empty first group; every other accepted literal starts with fc00–fdff.
    if (host == "::1") return true
    val firstGroup = host.substringBefore(':')
    if (firstGroup.isEmpty()) return false
    val value = firstGroup.toIntOrNull(16) ?: return false
    return value in 0xfc00..0xfdff
}
