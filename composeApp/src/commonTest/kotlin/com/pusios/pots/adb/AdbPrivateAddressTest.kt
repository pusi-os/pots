// Tests for the LAN address allowlist the ADB tools apply before a connection is attempted.

package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbPrivateAddressTest {

    @Test
    fun acceptsThePrivateIpv4Ranges() {
        assertTrue(isPrivateAdbHost("10.0.0.1"))
        assertTrue(isPrivateAdbHost("10.255.255.254"))
        assertTrue(isPrivateAdbHost("172.16.0.1"))
        assertTrue(isPrivateAdbHost("172.31.255.255"))
        assertTrue(isPrivateAdbHost("192.168.1.50"))
        assertTrue(isPrivateAdbHost("192.168.0.0"))
    }

    @Test
    fun acceptsLoopback() {
        assertTrue(isPrivateAdbHost("127.0.0.1"))
        assertTrue(isPrivateAdbHost("127.9.9.9"))
        assertTrue(isPrivateAdbHost("localhost"))
        assertTrue(isPrivateAdbHost("::1"))
        assertTrue(isPrivateAdbHost("[::1]"))
    }

    @Test
    fun acceptsTheIpv6UniqueLocalBlock() {
        assertTrue(isPrivateAdbHost("fc00::1"))
        assertTrue(isPrivateAdbHost("fd12:3456:789a::1"))
        assertTrue(isPrivateAdbHost("[fd00::1]"))
    }

    @Test
    fun rejectsPublicIpv4() {
        assertFalse(isPrivateAdbHost("8.8.8.8"))
        assertFalse(isPrivateAdbHost("203.0.113.7"))
        assertFalse(isPrivateAdbHost("172.15.0.1"))
        assertFalse(isPrivateAdbHost("172.32.0.1"))
        assertFalse(isPrivateAdbHost("192.169.1.1"))
        assertFalse(isPrivateAdbHost("11.0.0.1"))
    }

    @Test
    fun rejectsMalformedAddresses() {
        assertFalse(isPrivateAdbHost(""))
        assertFalse(isPrivateAdbHost("192.168.1"))
        assertFalse(isPrivateAdbHost("192.168.1.256"))
        assertFalse(isPrivateAdbHost("192.168.1.1.1"))
        assertFalse(isPrivateAdbHost("192.168.1.-1"))
    }

    @Test
    fun rejectsHostnamesAndPublicIpv6() {
        // A hostname would make the answer depend on DNS, not on the text the model sent.
        assertFalse(isPrivateAdbHost("my-tv.local"))
        assertFalse(isPrivateAdbHost("example.com"))
        assertFalse(isPrivateAdbHost("2001:db8::1"))
        assertFalse(isPrivateAdbHost("fe80::1"))
    }
}