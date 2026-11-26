// The 3.21.0 crash cannot be reproduced on the JVM: `AbsAdbConnectionManager` is a class from an
// Android AAR, so a desktop test never loads `LibAdbBackend`. What it CAN check is the name
// resolution that caused the crash — a Java getter `getDeviceName()` is also a Kotlin synthetic
// property on the anonymous object, so a bare `deviceName` inside the override resolved to the
// override itself and recursed until the stack overflowed. This guard reads the androidMain source
// and fails if the override stops pointing at a property of a different name.

package com.pusios.pots.adb

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibAdbBackendDeviceNameTest {

    private val source: String = libAdbBackendSource()

    @Test
    fun `the device name comes from a property that is not named after the Java getter`() {
        val body = Regex("""override fun getDeviceName\(\)\s*:\s*String\s*=\s*(\S+)""")
            .find(source)?.groupValues?.get(1)
        assertNotNull(body, "LibAdbBackend no longer overrides getDeviceName()")
        assertFalse(
            body.contains("deviceName"),
            "`$body` resolves to the override itself: a Java getter is also a Kotlin synthetic property",
        )
        assertTrue(body.contains("hostName"), "expected the hostName property, got `$body`")
    }

    @Test
    fun `no constructor property is named after a getter the manager overrides`() {
        assertFalse(
            Regex("""\bval\s+deviceName\b""").containsMatchIn(source),
            "a `deviceName` property invites the recursive `= deviceName` resolution",
        )
    }
}

/**
 * Reads the androidMain file from the source tree. Gradle runs a desktop test with the module
 * directory as the working directory, but the path is looked up from the module and from the repo
 * root alike, so the guard does not depend on that detail.
 */
private fun libAdbBackendSource(): String {
    val modulePath = "androidMain/kotlin/com/pusios/pots/adb/LibAdbBackend.kt"
    val candidates = listOf(
        File("composeApp/src/$modulePath"),
        File("../composeApp/src/$modulePath"),
        File("../../composeApp/src/$modulePath"),
        File("src/$modulePath"),
    )
    val file = candidates.firstOrNull { it.isFile }
        ?: error("LibAdbBackend.kt not found; looked in ${candidates.map { it.absolutePath }}")
    return file.readText()
}
