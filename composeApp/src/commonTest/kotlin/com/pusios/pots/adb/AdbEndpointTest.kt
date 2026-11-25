package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Endpoints are typed by a human or produced by a model, so the parser has to be unforgiving. */
class AdbEndpointTest {

    @Test
    fun `plain ipv4 endpoints parse`() {
        assertEquals(AdbEndpoint("192.168.1.42", 5555), parseAdbEndpoint("192.168.1.42:5555"))
        assertEquals(AdbEndpoint("10.0.0.2", 5037), parseAdbEndpoint("  10.0.0.2:5037  "))
        assertEquals(AdbEndpoint("localhost", 5555), parseAdbEndpoint("localhost:5555"))
    }

    @Test
    fun `bracketed ipv6 endpoints parse`() {
        assertEquals(AdbEndpoint("fd00::1", 5555), parseAdbEndpoint("[fd00::1]:5555"))
    }

    @Test
    fun `malformed endpoints are rejected`() {
        listOf(
            "",
            "   ",
            "192.168.1.42",
            ":5555",
            "192.168.1.42:",
            "192.168.1.42:0",
            "192.168.1.42:70000",
            "192.168.1.42:http",
            "fd00::1:5555",
            "[fd00::1]5555",
            "[fd00::1]:",
            "host name:5555",
        ).forEach { raw ->
            assertNull(parseAdbEndpoint(raw), "expected rejection: [$raw]")
        }
    }
}
