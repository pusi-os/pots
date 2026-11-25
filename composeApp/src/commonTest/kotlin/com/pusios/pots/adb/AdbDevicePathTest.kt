// Tests for the device-path allowlist: an install path is spliced into a shell command, so it must
// not be able to start a second one.

package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbDevicePathTest {

    @Test
    fun acceptsAbsoluteApkPaths() {
        assertTrue(isValidAdbDevicePath("/sdcard/Download/app.apk"))
        assertTrue(isValidAdbDevicePath("/data/local/tmp/app-release_1.2.3.apk"))
        assertTrue(isValidAdbDevicePath("/sdcard/Android/data/com.example/files/a+b=c.apk"))
    }

    @Test
    fun rejectsAnythingThatCouldStartASecondCommand() {
        assertFalse(isValidAdbDevicePath("/sdcard/app.apk; reboot"))
        assertFalse(isValidAdbDevicePath("/sdcard/app.apk && reboot"))
        assertFalse(isValidAdbDevicePath("/sdcard/$(reboot).apk"))
        assertFalse(isValidAdbDevicePath("/sdcard/app.apk`reboot`"))
        assertFalse(isValidAdbDevicePath("/sdcard/app.apk > /sdcard/x"))
        assertFalse(isValidAdbDevicePath("/sdcard/app apk"))
        assertFalse(isValidAdbDevicePath("/sdcard/app.apk\nreboot"))
        assertFalse(isValidAdbDevicePath("/sdcard/app\"apk"))
        assertFalse(isValidAdbDevicePath("/sdcard/app'apk"))
    }

    @Test
    fun rejectsRelativeAndTraversalPaths() {
        assertFalse(isValidAdbDevicePath("sdcard/app.apk"))
        assertFalse(isValidAdbDevicePath("/sdcard/../data/app.apk"))
        assertFalse(isValidAdbDevicePath(""))
        assertFalse(isValidAdbDevicePath("/"))
    }
}