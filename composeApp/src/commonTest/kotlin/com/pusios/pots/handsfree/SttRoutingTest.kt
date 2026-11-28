package com.pusios.pots.handsfree

import com.pusios.pots.data.SttEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one decision every speech consumer obeys: which engine runs, and why it
 * is not the requested one.
 */
class SttRoutingTest {

    private val ready = SttRuntimeFacts(neuralModelInstalled = true, availableMemoryMb = 2_000)
    private val modelMissing = SttRuntimeFacts(neuralModelInstalled = false, availableMemoryMb = 2_000)
    private val shortOfMemory = SttRuntimeFacts(neuralModelInstalled = true, availableMemoryMb = 120)
    private val unknownMemory = SttRuntimeFacts(neuralModelInstalled = true, availableMemoryMb = null)

    @Test
    fun `neural runs as requested when the model is there and memory allows it`() {
        val route = routeSttEngine(SttEngine.Neural, ready)
        assertEquals(SttEngine.Neural, route.engine)
        assertEquals(SttEngine.Neural, route.requested)
        assertNull(route.fallback)
        assertFalse(route.isFallback)
    }

    @Test
    fun `a missing model falls back to the bundled Vosk and says so`() {
        val route = routeSttEngine(SttEngine.Neural, modelMissing)
        assertEquals(SttEngine.Vosk, route.engine)
        assertEquals(SttFallback.ModelMissing, route.fallback)
        assertTrue(route.isFallback)
    }

    @Test
    fun `a memory shortfall falls back to Vosk rather than risking an out-of-memory kill`() {
        val route = routeSttEngine(SttEngine.Neural, shortOfMemory)
        assertEquals(SttEngine.Vosk, route.engine)
        assertEquals(SttFallback.LowMemory, route.fallback)
    }

    @Test
    fun `an unknown memory figure is not a reason to refuse`() {
        assertEquals(SttEngine.Neural, routeSttEngine(SttEngine.Neural, unknownMemory).engine)
    }

    @Test
    fun `exactly the required memory is enough`() {
        val facts = SttRuntimeFacts(
            neuralModelInstalled = true,
            availableMemoryMb = NEURAL_STT_REQUIRED_MEMORY_MB,
        )
        assertEquals(SttEngine.Neural, routeSttEngine(SttEngine.Neural, facts).engine)
        val oneMegabyteShort = facts.copy(availableMemoryMb = NEURAL_STT_REQUIRED_MEMORY_MB - 1)
        assertEquals(SttEngine.Vosk, routeSttEngine(SttEngine.Neural, oneMegabyteShort).engine)
    }

    @Test
    fun `a missing model is reported before a memory shortfall`() {
        val facts = SttRuntimeFacts(neuralModelInstalled = false, availableMemoryMb = 10)
        assertEquals(SttFallback.ModelMissing, routeSttEngine(SttEngine.Neural, facts).fallback)
    }

    @Test
    fun `vosk and system are honoured as picked`() {
        assertEquals(SttEngine.Vosk, routeSttEngine(SttEngine.Vosk, ready).engine)
        assertEquals(SttEngine.System, routeSttEngine(SttEngine.System, ready).engine)
        assertEquals(SttEngine.Vosk, routeSttEngine(SttEngine.Vosk, modelMissing).engine)
        assertNull(routeSttEngine(SttEngine.Vosk, modelMissing).fallback)
    }
}
