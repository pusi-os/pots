package com.pusios.pots.media

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Detection cache semantics for the desktop ffmpeg/ffprobe lookup: only a
 * positive result is cached. A negative result leaves the cache empty so the
 * next request re-probes — the §8 card's «Проверить снова» must pick up a
 * binary installed while the app is already running.
 */
class VideoEditBinaryCacheTest {

    @Test
    fun negativeResultIsNotCachedSoReprobeFindsNewlyInstalledBinary() {
        val cache = AtomicReference<String?>(null)
        assertNull(probeBinaryCached(cache) { null })
        assertEquals("/usr/bin/ffmpeg", probeBinaryCached(cache) { "/usr/bin/ffmpeg" })
    }

    @Test
    fun positiveResultIsCachedAndSkipsLaterProbes() {
        val cache = AtomicReference<String?>(null)
        var probes = 0
        val probe: () -> String? = {
            probes += 1
            "/usr/sbin/ffmpeg"
        }
        repeat(3) { assertEquals("/usr/sbin/ffmpeg", probeBinaryCached(cache, probe)) }
        assertEquals(1, probes)
    }

    @Test
    fun repeatedNegativeResultsReprobeEveryTime() {
        val cache = AtomicReference<String?>(null)
        var probes = 0
        val probe: () -> String? = {
            probes += 1
            null
        }
        repeat(3) { assertNull(probeBinaryCached(cache, probe)) }
        assertEquals(3, probes)
    }
}
