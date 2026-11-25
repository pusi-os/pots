package com.pusios.pots.adb

/** A parsed `host:port` pair, the way the user and the agent spell an ADB endpoint. */
data class AdbEndpoint(val host: String, val port: Int)

/**
 * Parses `host:port` into an [AdbEndpoint], or returns null when the text is not one.
 *
 * Both the settings screen and the agent tools take endpoints as text, and a malformed one must be
 * a clean rejection rather than a socket attempt to a nonsense host. IPv6 literals have to be
 * bracketed (`[fd00::1]:5555`) so the port separator stays unambiguous; a bare `fd00::1:5555` is
 * rejected because there is no way to tell the address from the port.
 */
fun parseAdbEndpoint(raw: String): AdbEndpoint? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val host: String
    val portText: String
    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close <= 1 || close + 1 >= text.length || text[close + 1] != ':') return null
        host = text.substring(1, close)
        if (!host.contains(':')) return null
        portText = text.substring(close + 2)
    } else {
        val separator = text.lastIndexOf(':')
        if (separator <= 0) return null
        host = text.substring(0, separator)
        if (host.contains(':')) return null
        portText = text.substring(separator + 1)
    }
    if (host.any { it.isWhitespace() }) return null
    val port = portText.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return AdbEndpoint(host, port)
}
