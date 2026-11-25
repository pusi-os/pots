// The step log is what made the 3.21.0 TV failure diagnosable; these tests pin the ring buffer's
// contract: it keeps the newest lines, in order, and never grows past its capacity.

package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbDiagnosticsLogTest {

    @Test
    fun `the buffer keeps the newest lines in order`() {
        PotsAdbLog.clear()

        repeat(PotsAdbLog.CAPACITY + 5) { index -> PotsAdbLog.step("probe", "line $index") }

        val lines = PotsAdbLog.snapshot()
        assertEquals(PotsAdbLog.CAPACITY, lines.size)
        assertEquals("line 5", lines.first().message)
        assertEquals("line ${PotsAdbLog.CAPACITY + 4}", lines.last().message)
        assertTrue(lines.all { line -> line.step == "probe" })
        PotsAdbLog.clear()
    }

    @Test
    fun `tail returns the last lines for a tool result`() {
        PotsAdbLog.clear()
        repeat(30) { index -> PotsAdbLog.step("connect", "step $index") }

        val tail = PotsAdbLog.tail(20)

        assertEquals(20, tail.size)
        assertEquals("step 10", tail.first().message)
        assertEquals("step 29", tail.last().message)
        assertEquals("connect: step 29", tail.last().toString())
        PotsAdbLog.clear()
    }

    @Test
    fun `clear empties the buffer`() {
        PotsAdbLog.step("connect", "something")
        PotsAdbLog.clear()

        assertEquals(emptyList(), PotsAdbLog.snapshot())
    }
}
