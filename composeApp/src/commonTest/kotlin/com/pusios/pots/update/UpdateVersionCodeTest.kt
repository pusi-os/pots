package com.pusios.pots.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The check is numeric, never lexical: `3.9.0` is older than `3.13.0`, which a string
 * comparison gets backwards.
 */
class UpdateVersionCodeTest {

    @Test
    fun encodesAMajorMinorPatchTag() {
        assertEquals(3_013_001L, versionCodeOf("3.13.1"))
        assertEquals(3_013_001L, versionCodeOf("v3.13.1"))
        assertEquals(3_014_000L, versionCodeOf("3.14"))
        assertEquals(4_000_000L, versionCodeOf("4"))
    }

    @Test
    fun ignoresBuildAndPreReleaseSuffixes() {
        assertEquals(3_013_001L, versionCodeOf("3.13.1-rc2"))
        assertEquals(3_013_001L, versionCodeOf("3.13.1+sha.abc123"))
    }

    @Test
    fun ordersVersionsNumericallyNotLexically() {
        val nine = versionCodeOf("3.9.0")!!
        val thirteen = versionCodeOf("3.13.0")!!
        assertTrue(nine < thirteen, "3.9.0 must sort below 3.13.0")
        assertTrue("3.9.0" > "3.13.0", "the string comparison this replaces is wrong")

        assertTrue(versionCodeOf("3.13.1")!! > versionCodeOf("3.13.0")!!)
        assertTrue(versionCodeOf("3.100.0")!! > versionCodeOf("3.99.9")!!)
        assertTrue(versionCodeOf("10.0.0")!! > versionCodeOf("9.99.99")!!)
    }

    @Test
    fun rejectsWhatIsNotAVersion() {
        assertNull(versionCodeOf(""))
        assertNull(versionCodeOf("v"))
        assertNull(versionCodeOf("nightly"))
        assertNull(versionCodeOf("3.x.1"))
        // Out of the encoding's range: better to refuse than to compare nonsense.
        assertNull(versionCodeOf("3.1000.0"))
    }
}
