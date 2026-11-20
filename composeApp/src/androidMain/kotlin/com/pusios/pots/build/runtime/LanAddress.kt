package com.pusios.pots.build.runtime

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "PotsLanAddress"

/**
 * The phone's own address in the local network, or null when it is on none.
 *
 * Read from the network interfaces rather than from `ip route` inside the
 * sandbox: proot creates no network namespace, so the interfaces here are the
 * ones a router sees, and the answer needs no rootfs to be up.
 *
 * Only site-local IPv4 (10/8, 172.16/12, 192.168/16) is reported — those are the
 * addresses other devices on the same network can dial. A CGNAT or VPN address
 * would be a number they cannot reach, and handing one out would be worse than
 * saying the panel is reachable only on this phone.
 */
object LanAddress {

    fun current(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.onFailure { Log.w(TAG, "could not read the LAN address", it) }.getOrNull()
}
