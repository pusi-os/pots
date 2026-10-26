package com.pusios.pots.data

import com.pusios.pots.mcp.McpServerManager
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentProfileTest {

    @Test
    fun `legacy agent and Potsik settings migrate into default profile and stay isolated`() {
        val raw = MapSettings()
        raw.putString(AppSettings.KEY_SOUL, "legacy prompt")
        raw.putString(AppSettings.KEY_SOUL_PRESET, AgentPersonaPresets.RESEARCHER)
        raw.putBoolean("${AppSettings.KEY_TOOL_PREFIX}web_search", false)
        raw.putBoolean(AppSettings.KEY_POTSIK_GLASSES, false)
        val settings = AppSettings(raw)
        val original = settings.getActiveAgentProfile()

        assertEquals("legacy prompt", settings.getSoulText())
        assertEquals(AgentPersonaPresets.RESEARCHER, settings.getSoulPresetId())
        assertFalse(settings.isToolEnabled("web_search"))
        assertFalse(settings.getPotsikLook().glassesOn)

        val second = assertNotNull(settings.createAgentProfile("Study buddy"))
        assertNotEquals(original.id, second.id)
        assertTrue(settings.activateAgentProfile(second.id))
        settings.setSoulText("study profile prompt")
        settings.setSoulPresetId(AgentPersonaPresets.COACH)
        settings.setToolEnabled("web_search", true)
        settings.setActiveProfileSkillIds(setOf("notes"))
        settings.setPotsikLook(settings.getPotsikLook().copy(glassesOn = true))

        assertTrue(settings.isToolEnabled("web_search"))
        assertEquals("study profile prompt", settings.getSoulText())
        assertEquals(setOf("notes"), settings.getActiveAgentProfile().enabledSkillIds.toSet())
        assertTrue(settings.getPotsikLook().glassesOn)

        assertTrue(settings.activateAgentProfile(original.id))
        assertEquals("legacy prompt", settings.getSoulText())
        assertEquals(AgentPersonaPresets.RESEARCHER, settings.getSoulPresetId())
        assertFalse(settings.isToolEnabled("web_search"))
        assertFalse(settings.getPotsikLook().glassesOn)
    }

    @Test
    fun `profiles keep user data global and persist profile metadata`() {
        val raw = MapSettings()
        val settings = AppSettings(raw)
        settings.setMemoryEnabled(false)
        settings.setAgentTuning(AgentTuning.Default.copy(reasoningEffort = ReasoningEffort.HIGH))
        val profile = assertNotNull(settings.createAgentProfile("Работа"))
        assertTrue(settings.activateAgentProfile(profile.id))
        settings.setSoulText("work-only instructions")

        assertFalse(settings.isMemoryEnabled())
        assertEquals(ReasoningEffort.HIGH, settings.getAgentTuning().reasoningEffort)
        assertTrue(raw.getStringOrNull("agent_profiles_json")?.contains("work-only instructions") == true)

        val reopened = AppSettings(raw)
        assertEquals(profile.id, reopened.getActiveAgentProfileId())
        assertEquals("work-only instructions", reopened.getSoulText())
        assertEquals(2, reopened.getAgentProfiles().size)
        assertFalse(reopened.isMemoryEnabled())
    }

    @Test
    fun `new profile snapshots active legacy MCP selection`() {
        val raw = MapSettings()
        raw.putString(
            AppSettings.KEY_MCP_SERVERS,
            """[{"id":"search","name":"Search","url":"https://example.test/mcp","isEnabled":true},{"id":"draft","name":"Draft","url":"https://example.test/draft","isEnabled":false}]""",
        )
        val settings = AppSettings(raw)
        val profile = assertNotNull(settings.createAgentProfile("Research"))
        assertEquals(setOf("search"), profile.enabledMcpServerIds?.toSet())
        assertNull(settings.getActiveAgentProfile().enabledMcpServerIds)
    }

    @Test
    fun `MCP server enablement is isolated per profile`() {
        val settings = AppSettings(MapSettings())
        val manager = McpServerManager(settings)
        val server = manager.addServer("Search", "https://example.test/mcp", emptyMap())
        val defaultProfileId = settings.getActiveAgentProfileId()
        val research = assertNotNull(settings.createAgentProfile("Research"))
        assertTrue(settings.activateAgentProfile(research.id))

        manager.setServerEnabled(server.id, false)
        assertFalse(manager.getServers().single().isEnabled)

        assertTrue(settings.activateAgentProfile(defaultProfileId))
        assertTrue(manager.getServers().single().isEnabled)
        assertTrue(settings.activateAgentProfile(research.id))
        assertFalse(manager.getServers().single().isEnabled)
    }
}

class AgentPersonaPresetsTest {

    @Test
    fun `all ready personas have compact Russian instructions`() {
        val ids = listOf(
            AgentPersonaPresets.RESEARCHER,
            AgentPersonaPresets.CREATIVE,
            AgentPersonaPresets.PRODUCTIVITY,
            AgentPersonaPresets.COMPANION,
            AgentPersonaPresets.PSYCHOLOGIST,
            AgentPersonaPresets.HOME_THERAPIST,
            AgentPersonaPresets.COACH,
            AgentPersonaPresets.POTSIK,
        )
        for (id in ids) {
            val prompt = AgentPersonaPresets.soulText(id)
            assertTrue(prompt.isNotBlank(), id)
            assertTrue(prompt.length < 900, "$id prompt grew beyond its compact budget")
            assertTrue(AgentPersonaPresets.presetName(id, "ru").any { it in 'А'..'я' }, id)
        }
        assertTrue("не выдумывай" in AgentPersonaPresets.soulText(AgentPersonaPresets.POTSIK).lowercase())
        assertTrue("не ставь диагноз" in AgentPersonaPresets.soulText(AgentPersonaPresets.PSYCHOLOGIST).lowercase())
        assertTrue(AgentPersonaPresets.presetName(AgentPersonaPresets.POTSIK, "en") == "Potsik")
    }
}
