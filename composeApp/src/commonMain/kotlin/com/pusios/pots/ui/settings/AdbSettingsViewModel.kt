// State for the «ADB devices» settings tab: the known devices, the mDNS search, pairing and
// connecting. Everything that talks to a device goes through AdbBackend, so this model never
// touches a socket itself; the backend is Android-only and the tab is hidden elsewhere.
//
// Pairing happens here and only here: the six-digit code is typed by the owner, and the agent has
// no tool that can pair a device.

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.ui.settings

import com.pusios.pots.adb.AdbBackend
import com.pusios.pots.adb.AdbDevice
import com.pusios.pots.adb.AdbDeviceStore
import com.pusios.pots.adb.AdbDiscovery
import com.pusios.pots.adb.AdbDiscoveryServiceType
import com.pusios.pots.adb.AdbErrorKind
import com.pusios.pots.adb.AdbException
import com.pusios.pots.adb.AdbPairResult
import com.pusios.pots.adb.AdbTimeouts
import com.pusios.pots.adb.adbDeviceId
import com.pusios.pots.adb.isPrivateAdbHost
import com.pusios.pots.adb.parseAdbEndpoint
import com.pusios.pots.tools.PermissionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** What the screen says after an action. The text is resolved in the UI, so both locales work. */
sealed interface AdbNotice {
    /** Pairing succeeded; a connection attempt follows. */
    data object Paired : AdbNotice

    data object InvalidCode : AdbNotice

    data object PairingWindowClosed : AdbNotice

    data object PairingTimeout : AdbNotice

    data class PairingFailed(val reason: String) : AdbNotice

    /** The device answered and the connection is open. */
    data class Connected(val address: String) : AdbNotice

    /** Nothing answered on the connect port. */
    data class NoAnswer(val address: String) : AdbNotice

    data class ConnectFailed(val reason: String) : AdbNotice

    /** The daemon rejected our key: it was revoked or never authorized. */
    data object PairingRequiredAgain : AdbNotice

    data object EndpointInvalid : AdbNotice

    data object EndpointNotPrivate : AdbNotice

    data object PairingPortInvalid : AdbNotice

    data object CodeInvalid : AdbNotice

    /** A pairing service was picked but no connect service is advertised for that host yet. */
    data object EnterConnectPort : AdbNotice
}

data class AdbSettingsUiState(
    val devices: List<AdbDevice> = emptyList(),
    val discovered: List<AdbDiscovery> = emptyList(),
    val isScanning: Boolean = false,
    /** A scan has finished at least once — the screen only reports "nothing found" after one. */
    val hasScanned: Boolean = false,
    /** The local-network permission was refused; the screen explains what to do instead. */
    val scanPermissionDenied: Boolean = false,
    val endpoint: String = "",
    val pairingPort: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val notice: AdbNotice? = null,
)

class AdbSettingsViewModel(
    private val backend: AdbBackend,
    private val store: AdbDeviceStore,
    private val localNetworkPermission: PermissionController,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AdbSettingsUiState(devices = store.all()))
    val state: StateFlow<AdbSettingsUiState> = _state.asStateFlow()

    fun onEndpointChange(text: String) = _state.update { it.copy(endpoint = text, notice = null) }

    fun onPairingPortChange(text: String) = _state.update { it.copy(pairingPort = text, notice = null) }

    fun onCodeChange(text: String) = _state.update { it.copy(code = text.filter { ch -> ch.isDigit() }.take(6), notice = null) }

    fun onTrustChange(device: AdbDevice, trusted: Boolean) {
        _state.update { it.copy(devices = store.setTrusted(device.id, trusted)) }
    }

    fun onRemove(device: AdbDevice) {
        scope.launch {
            // Closing the session first: a removed device must not keep a live connection the
            // agent could still reach through a stale tool argument.
            backend.disconnect(device.host, device.port)
            _state.update { it.copy(devices = store.remove(device.id)) }
        }
    }

    /** mDNS search, behind the local-network permission it needs on Android 17+. */
    fun onScan() {
        if (_state.value.isScanning) return
        scope.launch {
            if (!localNetworkPermission.hasPermission() && !localNetworkPermission.requestPermission()) {
                _state.update { it.copy(isScanning = false, scanPermissionDenied = true, hasScanned = true) }
                return@launch
            }
            _state.update { it.copy(isScanning = true, scanPermissionDenied = false, discovered = emptyList(), notice = null) }
            val found = try {
                backend.discover(AdbTimeouts.DISCOVERY_MS)
            } catch (failure: AdbException) {
                if (failure.kind == AdbErrorKind.PERMISSION_DENIED) {
                    _state.update { it.copy(isScanning = false, scanPermissionDenied = true, hasScanned = true) }
                    return@launch
                }
                emptyList()
            }
            _state.update { it.copy(isScanning = false, discovered = found, hasScanned = true) }
        }
    }

    /** Tapping a discovered service fills the form; a pairing entry brings its own port. */
    fun onDiscoveredSelected(discovery: AdbDiscovery) {
        val connectEntry = _state.value.discovered.firstOrNull { entry ->
            entry.host == discovery.host && entry.serviceType == AdbDiscoveryServiceType.TLS_CONNECT
        }
        _state.update { current ->
            when (discovery.serviceType) {
                AdbDiscoveryServiceType.TLS_PAIRING -> current.copy(
                    pairingPort = discovery.port.toString(),
                    endpoint = connectEntry?.let { "${it.host}:${it.port}" } ?: current.endpoint,
                    notice = if (connectEntry == null) AdbNotice.EnterConnectPort else null,
                )

                AdbDiscoveryServiceType.TLS_CONNECT, AdbDiscoveryServiceType.LEGACY_TCP ->
                    current.copy(endpoint = "${discovery.host}:${discovery.port}", notice = null)
            }
        }
    }

    fun onOpenAppSettings() = localNetworkPermission.openAppSettings()

    /**
     * Pairs with the code the owner typed, then connects to the endpoint.
     *
     * The pairing port is a separate, short-lived one that the device shows next to the code; the
     * connect port is the one from the wireless-debugging screen. Pairing success is what makes the
     * device trusted — nothing else in the app sets that flag.
     */
    fun onPairAndConnect() {
        val current = _state.value
        if (current.busy) return
        val endpoint = parseAdbEndpoint(current.endpoint)
        if (endpoint == null) return notice(AdbNotice.EndpointInvalid)
        if (!isPrivateAdbHost(endpoint.host)) return notice(AdbNotice.EndpointNotPrivate)
        val pairingPort = current.pairingPort.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return notice(AdbNotice.PairingPortInvalid)
        val code = current.code.trim()
        if (code.length != 6 || code.any { !it.isDigit() }) return notice(AdbNotice.CodeInvalid)

        scope.launch {
            _state.update { it.copy(busy = true, notice = null) }
            val result = try {
                backend.pair(endpoint.host, pairingPort, code)
            } catch (failure: Exception) {
                AdbPairResult.IoError(failure.message ?: "pairing failed")
            }
            when (result) {
                AdbPairResult.Success -> {
                    val device = AdbDevice(
                        id = adbDeviceId(endpoint.host, endpoint.port),
                        label = "${endpoint.host}:${endpoint.port}",
                        host = endpoint.host,
                        port = endpoint.port,
                        tls = true,
                        trusted = true,
                    )
                    _state.update { it.copy(devices = store.upsert(device), notice = AdbNotice.Paired) }
                    val connected = connect(device)
                    _state.update { it.copy(notice = connected, devices = store.all()) }
                }

                AdbPairResult.InvalidCode -> _state.update { it.copy(notice = AdbNotice.InvalidCode) }
                AdbPairResult.TargetNotFound -> _state.update { it.copy(notice = AdbNotice.PairingWindowClosed) }
                AdbPairResult.Timeout -> _state.update { it.copy(notice = AdbNotice.PairingTimeout) }
                is AdbPairResult.IoError -> _state.update { it.copy(notice = AdbNotice.PairingFailed(result.message)) }
            }
            _state.update { it.copy(busy = false, code = "") }
        }
    }

    /** Connects to an endpoint that is already paired or speaks legacy TCP (a TV on port 5555). */
    fun onConnect() {
        val current = _state.value
        if (current.busy) return
        val endpoint = parseAdbEndpoint(current.endpoint)
        if (endpoint == null) return notice(AdbNotice.EndpointInvalid)
        if (!isPrivateAdbHost(endpoint.host)) return notice(AdbNotice.EndpointNotPrivate)
        val id = adbDeviceId(endpoint.host, endpoint.port)
        val device = store.byId(id) ?: AdbDevice(
            id = id,
            label = "${endpoint.host}:${endpoint.port}",
            host = endpoint.host,
            port = endpoint.port,
            // A plain-TCP daemon lives on 5555; anything else here is wireless debugging.
            tls = endpoint.port != 5555,
            trusted = false,
        )
        scope.launch {
            _state.update { it.copy(busy = true, notice = null) }
            if (store.byId(device.id) == null) _state.update { it.copy(devices = store.upsert(device)) }
            val connected = connect(device)
            _state.update { it.copy(notice = connected, busy = false, devices = store.all()) }
        }
    }

    private suspend fun connect(device: AdbDevice): AdbNotice = try {
        if (backend.connect(device.host, device.port, device.tls)) {
            store.touch(device.id, lastModel = null, lastSeenEpochMs = Clock.System.now().toEpochMilliseconds())
            AdbNotice.Connected(device.endpoint)
        } else {
            AdbNotice.NoAnswer(device.endpoint)
        }
    } catch (failure: AdbException) {
        when (failure.kind) {
            AdbErrorKind.AUTHENTICATION_FAILED, AdbErrorKind.PAIRING_REQUIRED -> AdbNotice.PairingRequiredAgain
            else -> AdbNotice.ConnectFailed(failure.message ?: failure.kind.name)
        }
    }

    private fun notice(value: AdbNotice) {
        _state.update { it.copy(notice = value) }
    }
}