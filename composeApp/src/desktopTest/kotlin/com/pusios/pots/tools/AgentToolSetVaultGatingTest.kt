// Acceptance test for the vault-tools master switch (3.20.0): the set ships enabled
// by default and the Tools-tab toggle removes/restores it. Builds the real agent tool
// set with the real JVM vault file access and crypto, no Koin.

package com.pusios.pots.tools

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.MemoryStore
import com.pusios.pots.data.TaskStore
import com.pusios.pots.data.vault.VaultFormBridge
import com.pusios.pots.data.vault.VaultGate
import com.pusios.pots.data.vault.VaultRevealBridge
import com.pusios.pots.data.vault.VaultSession
import com.pusios.pots.data.vault.VaultStore
import com.pusios.pots.data.vault.VaultToolDependencies
import com.pusios.pots.data.vault.VaultTools
import com.pusios.pots.mcp.McpServerManager
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.ui.potsik.PotsikStage
import com.russhwolf.settings.MapSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentToolSetVaultGatingTest {

    private fun tempVaultStore(): VaultStore =
        VaultStore(JvmVaultFileAccess(Files.createTempDirectory("vault-tools-test").toFile()), JvmVaultCryptoEngine())

    private fun vaultDeps(store: VaultStore): VaultToolDependencies {
        val session = VaultSession(store = store)
        return VaultToolDependencies(
            session = session,
            store = store,
            formBridge = VaultFormBridge(session),
            revealBridge = VaultRevealBridge(session),
            gate = VaultGate(),
        )
    }

    private fun buildTools(appSettings: AppSettings): List<Tool> {
        val store = tempVaultStore()
        return buildAgentToolSet(
            appSettings = appSettings,
            memoryStore = MemoryStore(appSettings),
            taskStore = TaskStore(appSettings),
            mcpServerManager = McpServerManager(appSettings),
            potsikStage = PotsikStage(),
            vault = vaultDeps(store),
        )
    }

    @Test
    fun `vault tools are registered with default settings`() {
        val tools = buildTools(AppSettings(MapSettings()))
        val names = tools.map { it.schema.name }.toSet()
        assertTrue(
            VaultTools.TOOL_NAMES.all { it in names },
            "default settings must register the vault tools, missing: ${VaultTools.TOOL_NAMES.filterNot { it in names }}",
        )
    }

    @Test
    fun `toggling the master switch off removes the vault tools`() {
        val appSettings = AppSettings(MapSettings())
        appSettings.setToolEnabled(VaultTools.MASTER_ID, false)
        val names = buildTools(appSettings).map { it.schema.name }.toSet()
        assertTrue(VaultTools.TOOL_NAMES.none { it in names })
    }

    @Test
    fun `toggling the master switch back on restores the vault tools`() {
        val appSettings = AppSettings(MapSettings())
        appSettings.setToolEnabled(VaultTools.MASTER_ID, false)
        appSettings.setToolEnabled(VaultTools.MASTER_ID, true)
        val names = buildTools(appSettings).map { it.schema.name }.toSet()
        assertTrue(VaultTools.TOOL_NAMES.all { it in names })
    }

    @Test
    fun `only the master switch is user toggleable`() {
        val master = VaultTools.toolDefinitions.first { it.id == VaultTools.MASTER_ID }
        assertTrue(master.userToggleable, "the Tools tab must show a switch for the vault set")
        assertTrue(master.isEnabled, "the vault set must be enabled by default")
        val others = VaultTools.toolDefinitions.filter { it.id != VaultTools.MASTER_ID }
        assertFalse(others.any { it.userToggleable }, "individual vault tools must not get their own switches")
    }
}
