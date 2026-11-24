package com.pusios.pots.ui.chat

import com.pusios.pots.data.Service
import com.pusios.pots.ui.settings.SettingsModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The onboarding default model (report t_b1dce70b §6.0): the DeepSeek V4.1 Flash snapshot
 * spelled the way each provider spells it, falling back to the curated everyday pick when
 * the snapshot is absent from the provider's live catalog.
 */
class OnboardingDefaultModelTest {

    @Test
    fun `plusvibe and aitunnel use the bare snapshot id`() {
        val catalog = listOf(SettingsModel(id = "deepseek-v4.1-flash-1017", subtitle = ""))
        assertEquals("deepseek-v4.1-flash-1017", OnboardingDefaultModel.pick(catalog, Service.PlusVibe))
        assertEquals("deepseek-v4.1-flash-1017", OnboardingDefaultModel.pick(catalog, Service.AiTunnel))
        assertEquals("deepseek-v4.1-flash-1017", OnboardingDefaultModel.pick(catalog, Service.PlusVibeReserve))
    }

    @Test
    fun `polza uses its prefixed snapshot id`() {
        val catalog = listOf(SettingsModel(id = "deepseek/deepseek-v4.1-flash-1017", subtitle = ""))
        assertEquals("deepseek/deepseek-v4.1-flash-1017", OnboardingDefaultModel.pick(catalog, Service.Polza))
    }

    @Test
    fun `gptunnel has no snapshot and lands on the base v4.1 flash`() {
        val catalog = listOf(SettingsModel(id = "deepseek-v4.1-flash", subtitle = ""))
        assertEquals("deepseek-v4.1-flash", OnboardingDefaultModel.pick(catalog, Service.Gptunnel))
    }

    @Test
    fun `llm router only carries the previous generation v4 flash`() {
        val catalog = listOf(SettingsModel(id = "deepseek-v4-flash", subtitle = ""))
        assertEquals("deepseek-v4-flash", OnboardingDefaultModel.pick(catalog, Service.LlmRouter))
    }

    @Test
    fun `when the snapshot is absent the nearest curated everyday pick wins`() {
        // PlusVibe switched its /v1/models to unprefixed ids, so the curated pick
        // "z-ai/glm-5.3-flash" would never match — the fallback itself must respect
        // the live catalog and only return picks the provider actually returned.
        val catalog = listOf(SettingsModel(id = "glm-5.3-flash", subtitle = ""))
        assertNull(OnboardingDefaultModel.pick(catalog, Service.PlusVibe))
        assertEquals("glm-5.3-flash", OnboardingDefaultModel.pick(catalog, Service.AiTunnel))
    }

    @Test
    fun `an unknown model never gets selected`() {
        val catalog = listOf(SettingsModel(id = "totally-unrelated-model", subtitle = ""))
        assertNull(OnboardingDefaultModel.pick(catalog, Service.PlusVibe))
        assertNull(OnboardingDefaultModel.pick(emptyList(), Service.PlusVibe))
    }
}
