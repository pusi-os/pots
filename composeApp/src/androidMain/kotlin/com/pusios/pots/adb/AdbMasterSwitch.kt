// Watches the `adb_tools` master toggle and drops every open ADB session when it goes off.

package com.pusios.pots.adb

import com.pusios.pots.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Closes every open ADB session the moment the `adb_tools` master toggle goes off.
 *
 * The tools disappear from the agent's set by themselves — `buildAgentToolSet` re-reads the toggle
 * on every request — but an already-open socket would outlive the switch. Dropping the connections
 * is what makes "off" mean off.
 *
 * The first emission after start also disconnects when the toggle is already off, which is exactly
 * the state after a process restart: no session survives, nothing to do, no harm.
 */
class AdbMasterSwitch(
    private val appSettings: AppSettings,
    private val backend: AdbBackend,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            appSettings.agentProfilesFlow
                .map { appSettings.isToolEnabled(AdbTools.MASTER_ID, defaultEnabled = false) }
                .distinctUntilChanged()
                .collect { enabled -> if (!enabled) backend.disconnectAll() }
        }
    }
}
