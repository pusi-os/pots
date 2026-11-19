package com.pusios.pots.data

import com.pusios.pots.network.ConfiguredInstanceNotFoundException
import com.pusios.pots.network.NoConfiguredServiceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The instance a background run is allowed to use. The defect this guards: a saved Pulse model
 * that no longer existed was silently replaced by "the first remote", so the journal described
 * a provider the owner had not chosen.
 */
class AskInstanceResolutionTest {

    private fun instance(id: String, service: Service) = ServiceInstance(instanceId = id, serviceId = service.id)

    private val onDevice = instance("local", Service.LiteRT)
    private val remote = instance("remote", Service.Polza)

    @Test
    fun `an explicit pin wins over configuration order`() {
        val resolved = resolveAskInstance(listOf(remote, onDevice), "local")

        assertEquals("local", resolved.instanceId)
    }

    @Test
    fun `a pin that no longer exists is an error, not a substitution`() {
        val failure = assertFailsWith<ConfiguredInstanceNotFoundException> {
            resolveAskInstance(listOf(remote, onDevice), "deleted-model")
        }

        assertEquals("deleted-model", failure.instanceId)
    }

    @Test
    fun `no preference prefers a remote instance`() {
        assertEquals("remote", resolveAskInstance(listOf(onDevice, remote), null).instanceId)
    }

    @Test
    fun `no preference falls back to on-device when it is all there is`() {
        assertEquals("local", resolveAskInstance(listOf(onDevice), null).instanceId)
    }

    @Test
    fun `an empty catalogue is an error, not an empty run`() {
        assertFailsWith<NoConfiguredServiceException> { resolveAskInstance(emptyList(), null) }
        assertFailsWith<NoConfiguredServiceException> { resolveAskInstance(emptyList(), "remote") }
    }
}
