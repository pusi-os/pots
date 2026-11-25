package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The read-only classifier is the gate that decides what the agent may run on a device it was not
 * paired with, so the tests are written from the attacker's side: every command that could change
 * state, chain another command or hide one must come back false.
 */
class AdbShellCommandClassifierTest {

    @Test
    fun `known read-only commands are allowed`() {
        val safe = listOf(
            "getprop",
            "getprop ro.product.model",
            "dumpsys battery",
            "dumpsys package com.android.settings",
            "df -h",
            "du -sh /sdcard",
            "ps -A",
            "id",
            "whoami",
            "uname -a",
            "uptime",
            "cat /proc/cpuinfo",
            "ls -la /sdcard",
            "pm list packages",
            "pm path com.android.settings",
            "settings get global airplane_mode_on",
            "logcat -d",
            "logcat -d -t 200",
            "date",
            "date +%Y-%m-%d",
            "  getprop ro.build.version.sdk  ",
        )
        safe.forEach { command ->
            assertTrue(isAdbShellCommandReadOnly(command), "expected read-only: $command")
        }
    }

    @Test
    fun `commands that write are refused`() {
        val writing = listOf(
            "rm -rf /sdcard/pots",
            "pm install -r /data/local/tmp/app.apk",
            "pm uninstall com.example.app",
            "pm clear com.example.app",
            "settings put global airplane_mode_on 1",
            "am start -n com.example.app/.Main",
            "reboot",
            "svc wifi disable",
            "input tap 100 200",
            "mkdir /sdcard/pots",
            "chmod 777 /data/local/tmp/x",
            // `logcat` without -d never returns, and it is not a dump.
            "logcat",
            // `date` with an argument sets the clock.
            "date 010100002026",
        )
        writing.forEach { command ->
            assertFalse(isAdbShellCommandReadOnly(command), "expected refusal: $command")
        }
    }

    @Test
    fun `compound commands and injections are refused even when the first word is safe`() {
        val injections = listOf(
            "id; rm -rf /sdcard",
            "ls && reboot",
            "cat /proc/cpuinfo || reboot",
            "cat /proc/cpuinfo | sh",
            "getprop ro.product.model & reboot",
            "cat /sdcard/x > /sdcard/y",
            "cat < /etc/hosts",
            "echo \$(reboot)",
            "echo `reboot`",
            "getprop\nreboot",
            "getprop\r\nreboot",
            "cat /proc/cpuinfo \\; reboot",
        )
        injections.forEach { command ->
            assertFalse(isAdbShellCommandReadOnly(command), "expected refusal: $command")
        }
    }

    @Test
    fun `empty and unknown commands are refused`() {
        listOf("", "   ", "\n", "top", "sh", "toybox", "unknown-thing --flag").forEach { command ->
            assertFalse(isAdbShellCommandReadOnly(command), "expected refusal: [$command]")
        }
    }
}
