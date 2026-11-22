package com.pusios.pots

import android.content.Context
import com.pusios.pots.build.PotsBuildState
import com.pusios.pots.build.activeSandboxCredentials
import com.pusios.pots.build.runtime.BuildEnvironmentManager
import com.pusios.pots.build.runtime.BuildFileBrowser
import com.pusios.pots.data.DataRepository
import com.pusios.pots.linux.LinuxDistro
import com.pusios.pots.linux.LinuxInstalls
import com.pusios.pots.linux.LinuxPaths
import com.pusios.pots.sandbox.LinuxSandboxManager
import kotlinx.coroutines.flow.StateFlow
import org.koin.java.KoinJavaComponent.inject

actual fun createPotsBuildController(): PotsBuildController = AndroidPotsBuildController()

class AndroidPotsBuildController : PotsBuildController {

    private val context: Context by inject(Context::class.java)
    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
    private val dataRepository: DataRepository by inject(DataRepository::class.java)

    private val installs by lazy { LinuxInstalls(context) }

    /**
     * Pots Build is always Debian: its coding agents are vendor scripts that
     * expect glibc and apt. Debian has one directory on the device, so this is
     * the same install the chat sandbox uses whenever the shell integration is
     * pointed at Debian — a Linux set up from either surface is immediately
     * there for the other. Pointing the shell integration at Alpine instead
     * leaves this install alone; the two simply coexist.
     */
    private val paths: LinuxPaths by lazy { installs.pathsFor(LinuxDistro.DEBIAN) }

    private val manager: BuildEnvironmentManager by lazy {
        BuildEnvironmentManager(paths, context.assets).also { built ->
            // The agents in here are meant to be the agent the chat already is, so
            // every session start reads the live provider from Settings instead of
            // asking the user to configure each CLI separately.
            built.activeCredentials = { dataRepository.activeSandboxCredentials() }
            // Panel passwords and autostart toggles are Pots settings, like the
            // provider itself — nothing about them lives in the sandbox.
            built.webPanelPreferences = { panelId -> dataRepository.getWebPanelPreferences(panelId) }
            built.saveWebPanelPreferences = { panelId, prefs -> dataRepository.setWebPanelPreferences(panelId, prefs) }
            // Both directions are checked at fire time, not wired once: the chat
            // sandbox can be pointed at this install and away from it while the
            // app runs.
            sandboxManager.onBeforeReset = {
                // A sandbox reset deletes the rootfs; sessions holding file
                // descriptors into it have to go first.
                if (sandboxManager.rootDir == paths.root) built.onEnvironmentRemoved()
            }
            // And the other direction: installing Debian from Pots Build's setup
            // screen is what gives a Debian-pointed chat sandbox its Linux.
            built.onEnvironmentChanged = { sandboxManager.refreshInstallState() }
        }
    }

    override val state: StateFlow<PotsBuildState> get() = manager.state
    override val files: FileBrowserSource by lazy { BuildFileBrowser(context, paths) }

    override fun install(agentIds: Set<String>) = manager.install(agentIds)
    override fun cancel() = manager.cancel()
    override fun uninstall() = manager.uninstall()
    override fun checkAgents(agentIds: Set<String>) = manager.checkAgents(agentIds)
    override fun updateAgent(agentId: String) = manager.updateAgent(agentId)
    override fun removeAgent(agentId: String, includeBulk: Boolean) = manager.removeAgent(agentId, includeBulk)
    override fun refresh() = manager.refresh()
    override fun createProject(name: String): String? = manager.createProject(name)
    override fun deleteProject(name: String) = manager.deleteProject(name)
    override fun renameProject(name: String, newName: String): String? = manager.renameProject(name, newName)
    override fun startSession(project: String, agentId: String?) = manager.startSession(project, agentId)
    override fun selectSession(id: String) = manager.selectSession(id)
    override fun closeSession(id: String) = manager.closeSession(id)
    override fun resumeProject(project: String): Boolean = manager.resumeProject(project)
    override fun leaveProject(project: String) = manager.leaveProject(project)
    override fun writeToTerminal(text: String) = manager.writeToTerminal(text)
    override fun resizeTerminal(columns: Int, rows: Int) = manager.resizeTerminal(columns, rows)
    override fun startWebPanel(panelId: String) {
        manager.startWebPanel(panelId)
    }

    override fun stopWebPanel(panelId: String) {
        manager.stopWebPanel(panelId)
    }
    override fun openWebPanel(panelId: String) = manager.openWebPanel(panelId)
    override fun setWebPanelAutostart(panelId: String, enabled: Boolean) = manager.setWebPanelAutostart(panelId, enabled)

    override suspend fun webPanelPassword(panelId: String): String? = manager.webPanelPassword(panelId)

    override fun webPanelLog(panelId: String): String = manager.webPanelLog(panelId)
}
