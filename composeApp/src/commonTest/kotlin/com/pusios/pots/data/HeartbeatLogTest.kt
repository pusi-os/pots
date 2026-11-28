package com.pusios.pots.data

// The pulse journal used to keep five runs, which was too short to answer "did the pulse check
// the rate an hour ago?". These tests pin the longer window and the reset action.

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartbeatLogTest {

    private fun freshManager(): HeartbeatManager {
        val settings = AppSettings(MapSettings())
        return HeartbeatManager(settings, MemoryStore(settings), TaskStore(settings))
    }

    @Test
    fun journalKeepsTheLastFiftyRunsNewestFirst() = runTest {
        val manager = freshManager()

        repeat(55) { index -> manager.recordHeartbeat(success = true, note = "run $index") }

        val log = manager.getHeartbeatLog()
        assertEquals(50, log.size)
        assertEquals("run 54", log.first().note)
        // Runs 0..4 are evicted; run 5 is the oldest kept entry.
        assertEquals("run 5", log.last().note)
    }

    @Test
    fun clearLogEmptiesTheJournalButKeepsTheManagerUsable() = runTest {
        val manager = freshManager()
        manager.recordHeartbeat(success = false, error = "boom")

        manager.clearLog()

        assertTrue(manager.getHeartbeatLog().isEmpty())
        manager.recordHeartbeat(success = true)
        assertEquals(1, manager.getHeartbeatLog().size)
    }
}
