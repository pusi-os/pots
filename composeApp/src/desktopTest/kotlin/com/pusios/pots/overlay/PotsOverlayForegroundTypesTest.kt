package com.pusios.pots.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The foreground-service types the overlay declares.
 *
 * This branch has no compiler to catch it — `compileAndroidMain` only proves
 * it parses — and it already cost a review round once: the retry after a
 * refused microphone re-declared MICROPHONE and threw a second, uncaught
 * `SecurityException`. So the decision is a pure function and every branch is
 * pinned here.
 */
class PotsOverlayForegroundTypesTest {

    @Test
    fun `api 34 declares special use, and the microphone only while it is asked for`() {
        assertEquals(
            FOREGROUND_TYPE_SPECIAL_USE or FOREGROUND_TYPE_MICROPHONE,
            foregroundTypes(34, withMicrophone = true),
        )
        assertEquals(
            FOREGROUND_TYPE_SPECIAL_USE,
            foregroundTypes(34, withMicrophone = false),
            "the retry after a refused microphone must not declare it again",
        )
    }

    @Test
    fun `api 30 to 33 declare the microphone alone, and nothing without it`() {
        assertEquals(FOREGROUND_TYPE_MICROPHONE, foregroundTypes(30, withMicrophone = true))
        assertEquals(0, foregroundTypes(30, withMicrophone = false))
        assertEquals(FOREGROUND_TYPE_MICROPHONE, foregroundTypes(33, withMicrophone = true))
        assertEquals(0, foregroundTypes(33, withMicrophone = false))
    }

    @Test
    fun `below api 29 there is no three-argument call at all`() {
        assertEquals(0, foregroundTypes(29, withMicrophone = false))
        assertEquals(0, foregroundTypes(28, withMicrophone = true), "no SPECIAL_USE and no microphone type")
    }

    @Test
    fun `the numbers are the platform's own`() {
        // Verified against android.jar (API 37):
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1073741824,
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE = 128.
        assertEquals(0x40000000, FOREGROUND_TYPE_SPECIAL_USE)
        assertEquals(0x80, FOREGROUND_TYPE_MICROPHONE)
    }
}
