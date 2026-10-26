package com.pusios.pots.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.miniapp.MiniApp
import com.pusios.pots.data.miniapp.MiniAppRecord
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.data.miniapp.MiniAppServer
import com.pusios.pots.data.miniapp.MiniAppServerController
import com.pusios.pots.data.miniapp.MiniAppServerState
import com.pusios.pots.data.miniapp.getMiniAppServerPort
import com.pusios.pots.data.miniapp.isMiniAppServerBackgroundEnabled
import com.pusios.pots.data.miniapp.isMiniAppServerEnabled
import com.pusios.pots.data.miniapp.setMiniAppServerBackgroundEnabled
import com.pusios.pots.data.miniapp.setMiniAppServerEnabled
import com.pusios.pots.data.miniapp.setMiniAppServerPort
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class MiniAppCollectionRow(
    val name: String,
    val description: String,
    val fields: List<String>,
    val count: Int,
)

data class MiniAppServerUiState(
    val server: MiniAppServerState = MiniAppServerState(),
    val enabled: Boolean = false,
    val background: Boolean = false,
    val port: String = MiniAppServerState.DEFAULT_PORT.toString(),
    val apps: ImmutableList<MiniApp> = emptyList<MiniApp>().toImmutableList(),
    val selectedAppId: String? = null,
    val collections: ImmutableList<MiniAppCollectionRow> = emptyList<MiniAppCollectionRow>().toImmutableList(),
    val selectedCollection: String? = null,
    val records: ImmutableList<MiniAppRecord> = emptyList<MiniAppRecord>().toImmutableList(),
    val message: String? = null,
)

class MiniAppServerViewModel(
    private val repository: MiniAppRepository,
    private val server: MiniAppServer,
    private val controller: MiniAppServerController,
    private val appSettings: AppSettings,
) : ViewModel() {

    private val ui = MutableStateFlow(
        MiniAppServerUiState(
            enabled = appSettings.isMiniAppServerEnabled(),
            background = appSettings.isMiniAppServerBackgroundEnabled(),
            port = appSettings.getMiniAppServerPort().toString(),
        ),
    )

    val state = combine(ui, server.state, repository.apps) { state, serverState, apps ->
        val selectedApp = apps.firstOrNull { it.id == state.selectedAppId }
        val collections = selectedApp?.collections.orEmpty().map { collection ->
            MiniAppCollectionRow(
                name = collection.name,
                description = collection.description,
                fields = collection.fields.map { it.name },
                count = repository.records(selectedApp!!.id, collection.name).size,
            )
        }
        state.copy(
            server = serverState,
            port = serverState.port.toString(),
            apps = apps.toImmutableList(),
            collections = collections.toImmutableList(),
            records = if (selectedApp != null && state.selectedCollection != null) {
                repository.records(selectedApp.id, state.selectedCollection!!).toImmutableList()
            } else {
                emptyList<MiniAppRecord>().toImmutableList()
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ui.value.copy(server = server.state.value),
    )

    fun onScreenVisible() {
        ui.update {
            it.copy(
                enabled = appSettings.isMiniAppServerEnabled(),
                background = appSettings.isMiniAppServerBackgroundEnabled(),
            )
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        appSettings.setMiniAppServerEnabled(enabled)
        if (enabled) {
            server.start()
            if (appSettings.isMiniAppServerBackgroundEnabled()) controller.startBackground()
        } else {
            controller.stopBackground()
            server.stop()
        }
        ui.update { it.copy(enabled = enabled, message = null) }
    }

    fun toggleBackground(enabled: Boolean) {
        appSettings.setMiniAppServerBackgroundEnabled(enabled)
        if (enabled && appSettings.isMiniAppServerEnabled()) {
            server.start()
            controller.startBackground()
        } else {
            controller.stopBackground()
            // The service stops the server with it; keep the in-app server alive
            // while the user is still in Pots.
            if (appSettings.isMiniAppServerEnabled()) server.start()
        }
        ui.update { it.copy(background = enabled) }
    }

    fun setPort(value: String) {
        if (value.length <= 5 && value.all { it.isDigit() }) {
            ui.update { it.copy(port = value) }
        }
    }

    fun applyPort() {
        val port = ui.value.port.toIntOrNull() ?: return
        if (port !in MiniAppServerState.PortRange) {
            ui.update { it.copy(message = "Port must be between 1024 and 65535") }
            return
        }
        server.setPort(port)
        ui.update { it.copy(message = null) }
    }

    fun restart() {
        server.restart()
    }

    fun selectApp(id: String?) {
        ui.update { it.copy(selectedAppId = id, selectedCollection = null, records = emptyList<MiniAppRecord>().toImmutableList()) }
    }

    fun selectCollection(name: String?) {
        ui.update { it.copy(selectedCollection = name) }
    }

    fun addRecord(json: String) {
        val appId = ui.value.selectedAppId ?: return
        val collection = ui.value.selectedCollection ?: return
        val parsed = runCatching { com.pusios.pots.data.SharedJson.parseToJsonElement(json) as? JsonObject }
            .getOrNull()
        if (parsed == null) {
            ui.update { it.copy(message = "Record must be a JSON object") }
            return
        }
        repository.putRecord(appId, collection, null, parsed)
        ui.update { it.copy(message = "Record added") }
    }

    fun deleteRecord(recordId: String) {
        val appId = ui.value.selectedAppId ?: return
        val collection = ui.value.selectedCollection ?: return
        repository.deleteRecord(appId, collection, recordId)
        ui.update { it.copy(message = "Record deleted") }
    }

    fun addCollection(name: String, fields: String) {
        val appId = ui.value.selectedAppId ?: return
        if (name.isBlank()) {
            ui.update { it.copy(message = "Collection name is required") }
            return
        }
        val parsed = fields.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
            com.pusios.pots.data.miniapp.MiniAppField(
                name = entry.substringBefore(':').trim(),
                type = entry.substringAfter(':', "string").trim(),
            )
        }
        repository.declareCollection(
            appId,
            com.pusios.pots.data.miniapp.MiniAppCollection(name = name.trim(), fields = parsed),
        )
        ui.update { it.copy(selectedCollection = name.trim(), message = "Collection saved") }
    }

    fun deleteCollection() {
        val appId = ui.value.selectedAppId ?: return
        val collection = ui.value.selectedCollection ?: return
        repository.deleteCollection(appId, collection)
        ui.update { it.copy(selectedCollection = null, message = "Collection deleted") }
    }

    fun clearCollection() {
        val appId = ui.value.selectedAppId ?: return
        val collection = ui.value.selectedCollection ?: return
        repository.clearCollection(appId, collection)
        ui.update { it.copy(message = "Collection cleared") }
    }

    fun clearMessage() {
        ui.update { it.copy(message = null) }
    }
}
