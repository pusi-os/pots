package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `uninstall` is the only call in the core that interpolates caller-supplied text into a shell
 * command, so its validator is tested against the inputs an injected name would look like.
 */
class AdbPackageNameTest {

    @Test
    fun `real package ids pass`() {
        assertTrue(isValidAdbPackageName("com.pusios.pots"))
        assertTrue(isValidAdbPackageName("com.android.settings"))
        assertTrue(isValidAdbPackageName("a"))
        assertTrue(isValidAdbPackageName("A_b.c1"))
        assertTrue(isValidAdbPackageName("a".repeat(255)))
    }

    @Test
    fun `command injection does not pass`() {
        assertFalse(isValidAdbPackageName("com.x; settings put global adb_enabled 0"))
        assertFalse(isValidAdbPackageName("com.x;reboot"))
        assertFalse(isValidAdbPackageName("com.x|id"))
        assertFalse(isValidAdbPackageName("com.x&&reboot"))
        assertFalse(isValidAdbPackageName("com.x> /sdcard/x"))
        assertFalse(isValidAdbPackageName("com.x$(id)"))
        assertFalse(isValidAdbPackageName("com.x`id`"))
        assertFalse(isValidAdbPackageName("com.x\nreboot"))
        assertFalse(isValidAdbPackageName("com.x\rm"))
    }

    @Test
    fun `paths, flags and whitespace do not pass`() {
        assertFalse(isValidAdbPackageName(""))
        assertFalse(isValidAdbPackageName(" "))
        assertFalse(isValidAdbPackageName("com.x "))
        assertFalse(isValidAdbPackageName("com.x y"))
        assertFalse(isValidAdbPackageName("-r"))
        assertFalse(isValidAdbPackageName("/data/local/tmp/x.apk"))
        assertFalse(isValidAdbPackageName("com/x"))
        assertFalse(isValidAdbPackageName("com.x\u0000"))
    }

    @Test
    fun `an over-long name does not pass`() {
        assertFalse(isValidAdbPackageName("a".repeat(256)))
    }
}
