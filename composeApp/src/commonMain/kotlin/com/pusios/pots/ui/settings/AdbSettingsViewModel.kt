// State for the «ADB devices» settings tab: the known devices, the mDNS search, the LAN scan,
// pairing and connecting. Everything that talks to a device goes through AdbBackend, so this model
// never touches a socket itself; the backend is Android-only and the tab is hidden elsewhere.
//
// Pairing happens here and only here: the six-digit code is typed by the owner, and the agent has
// no tool that can pair a device.

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.ui.settings

import com.pusios.pots.adb.ADB_LEGACY_PORT
import com.pusios.pots.adb.AdbBackend
import com.pusios.pots.adb.AdbConnectPhase
import com.pusios.pots.adb.AdbDevice
import com.pusios.pots.adb.AdbDeviceState
import com.pusios.pots.adb.AdbDeviceStore
import com.pusios.pots.adb.AdbDiscovery
import com.pusios.pots.adb.AdbDiscoveryServiceType
import com.pusios.pots.adb.AdbErrorKind
import com.pusios.pots.adb.AdbException
import com.pusios.pots.adb.AdbPairResult
import com.pusios.pots.adb.AdbProbeResult
import com.pusios.pots.adb.AdbTimeouts
import com.pusios.pots.adb.adbDeviceId
import com.pusios.pots.adb.adbProbeSummary
import com.pusios.pots.adb.isPrivateAdbHost
import com.pusios.pots.adb.parseAdbEndpoint
import com.pusios.pots.adb.readProductModelOrNull
import com.pusios.pots.tools.PermissionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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

    /** Nothing answered on the connect port: closed port, no route, or a TCP timeout. */
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

    /** The device is showing its «Allow debugging» dialog; the app is waiting for the owner. */
    data class WaitingForConfirmation(val address: String) : AdbNotice

    /** The wait ran out: the device never confirmed the key. Not a network failure. */
    data class DeviceDidNotConfirm(val address: String) : AdbNotice

    /** The port is open but nothing on it speaks ADB. */
    data class NotAdb(val address: String) : AdbNotice

    /** The «Диагностика» result, so the screen can word each verdict differently. */
    data class Diagnosis(val result: AdbProbeResult) : AdbNotice

    /** The owner stopped waiting for the device dialog before the deadline. */
    data class ConnectCancelled(val address: String) : AdbNotice
}

/** What the screen shows next to one endpoint. */
data class AdbDeviceStatus(val state: AdbDeviceState, val detail: String? = null)

data class AdbSettingsUiState(
    val devices: List<AdbDevice> = emptyList(),
    val discovered: List<AdbDiscovery> = emptyList(),
    val isScanning: Boolean = false,
    /** A LAN scan of port 5555 is running; mDNS and the subnet scan are mutually exclusive. */
    val isScanningLan: Boolean = false,
    /** A scan has finished at least once — the screen only reports "nothing found" after one. */
    val hasScanned: Boolean = false,
    /** The local-network permission was refused; the screen explains what to do instead. */
    val scanPermissionDenied: Boolean = false,
    val endpoint: String = "",
    val pairingPort: String = "",
    val code: String = "",
    val busy: Boolean = false,
    /**
     * A connection attempt is in flight and «Отмена» can stop it. Set only by [AdbSettingsViewModel.onConnect]
     * and [AdbSettingsViewModel.onPairAndConnect]: a «Диагностика» leaves a device in the waiting
     * state too, but nothing is waiting there and there is nothing to cancel.
     */
    val connectInFlight: Boolean = false,
    /**
     * «Отмена» has something to stop: the live attempt's own device is showing its «Allow debugging»
     * dialog. Derived from [connectInFlight] and that device's status — a device left waiting by
     * «Диагностика» must not offer a button that would do nothing.
     */
    val canCancelConnect: Boolean = false,
    /** Per-endpoint state, keyed by [adbDeviceId]; an entry appears once an attempt starts. */
    val statuses: Map<String, AdbDeviceStatus> = emptyMap(),
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

    /** The connection attempt in flight, so «Отмена» can stop waiting for the device dialog. */
    private var connectJob: Job? = null

    /**
     * Id of the newest connection attempt.
     *
     * The transport blocks on a socket read that no cancellation can interrupt, so a cancelled
     * attempt still returns a verdict — often seconds later. That late verdict belongs to an attempt
     * nobody is waiting for: it is compared against this id and dropped instead of overwriting the
     * «Отмена» the owner just pressed. A [Job] identity would not do: on an immediate dispatcher the
     * attempt body runs before `connectJob` is assigned.
     */
    private var attemptId = 0L

    /** The endpoint [connectJob] is working on. */
    private var connectTarget: AdbDevice? = null

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
            _state.update {
                it.copy(devices = store.remove(device.id), statuses = it.statuses - device.id).withCancelFlag()
            }
        }
    }

    /** mDNS search, behind the local-network permission it needs on Android 17+. */
    fun onScan() {
        if (_state.value.isScanning || _state.value.isScanningLan) return
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

    /**
     * Probes the /24 this phone sits in on port 5555.
     *
     * This is the search that finds a TV: mDNS only reports daemons on the phone's own addresses, so
     * a device that answers `CNXN` on 5555 is found here and nowhere else. Results are merged into
     * the list instead of replacing it, so an mDNS find does not disappear after a subnet scan.
     */
    fun onScanLan() {
        val current = _state.value
        if (current.isScanning || current.isScanningLan) return
        scope.launch {
            if (!localNetworkPermission.hasPermission() && !localNetworkPermission.requestPermission()) {
                _state.update { it.copy(isScanningLan = false, scanPermissionDenied = true, hasScanned = true) }
                return@launch
            }
            _state.update { it.copy(isScanningLan = true, scanPermissionDenied = false, notice = null) }
            val found = try {
                backend.scanLan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure is AdbException && failure.kind == AdbErrorKind.PERMISSION_DENIED) {
                    _state.update { it.copy(isScanningLan = false, scanPermissionDenied = true, hasScanned = true) }
                    return@launch
                }
                emptyList()
            }
            _state.update { state ->
                state.copy(
                    isScanningLan = false,
                    discovered = (state.discovered + found).distinct(),
                    hasScanned = true,
                )
            }
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
     * One probe of the typed endpoint, with the verdict as a notice.
     *
     * It sends a bare `CNXN`, which cannot put a dialog on the device's screen, so the owner can run
     * it before connecting and after a failed attempt without leaving a prompt behind.
     */
    fun onDiagnose() {
        val current = _state.value
        if (current.busy) return
        val endpoint = parseAdbEndpoint(current.endpoint) ?: return notice(AdbNotice.EndpointInvalid)
        if (!isPrivateAdbHost(endpoint.host)) return notice(AdbNotice.EndpointNotPrivate)
        val id = adbDeviceId(endpoint.host, endpoint.port)
        scope.launch {
            _state.update { it.copy(busy = true, notice = null) }
            setStatus(id, AdbDeviceStatus(AdbDeviceState.PROBING))
            val result = try {
                backend.probe(endpoint.host, endpoint.port)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                AdbProbeResult.Failed(failure.message ?: failure::class.simpleName.orEmpty())
            }
            setStatus(id, AdbDeviceStatus(stateForProbe(result), adbProbeSummary(result)))
            _state.update { it.copy(busy = false, notice = AdbNotice.Diagnosis(result)) }
        }
    }

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
            try {
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
                        // The connect half can also wait for a dialog, so it is cancellable too.
                        val token = ++attemptId
                        connectTarget = device
                        connectJob = currentCoroutineContext()[Job]
                        connectInFlight(true)
                        val connected = connect(device, token)
                        if (connected != null) _state.update { it.copy(notice = connected, devices = store.all()) }
                    }

                    AdbPairResult.InvalidCode -> _state.update { it.copy(notice = AdbNotice.InvalidCode) }

                    AdbPairResult.TargetNotFound -> _state.update { it.copy(notice = AdbNotice.PairingWindowClosed) }

                    AdbPairResult.Timeout -> _state.update { it.copy(notice = AdbNotice.PairingTimeout) }

                    is AdbPairResult.IoError -> _state.update { it.copy(notice = AdbNotice.PairingFailed(result.message)) }
                }
            } finally {
                connectJob = null
                connectTarget = null
                connectInFlight(false)
                _state.update { it.copy(busy = false, code = "") }
            }
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
            tls = endpoint.port != ADB_LEGACY_PORT,
            trusted = false,
        )
        connectTarget = device
        // Set before the launch, so a body that finishes on an immediate dispatcher cannot leave the
        // flag on: its `finally` clears it.
        connectInFlight(true)
        // The token, not the job, is the attempt's identity: on an immediate dispatcher the body
        // runs to its first suspension before `connectJob` is assigned below.
        val token = ++attemptId
        lateinit var attempt: Job
        attempt = scope.launch {
            _state.update { it.copy(busy = true, notice = null) }
            if (store.byId(device.id) == null) _state.update { it.copy(devices = store.upsert(device)) }
            try {
                val connected = connect(device, token)
                if (connected != null) _state.update { it.copy(notice = connected, busy = false, devices = store.all()) }
            } finally {
                if (isLiveAttempt(token)) {
                    connectJob = null
                    connectTarget = null
                    connectInFlight(false)
                }
            }
        }
        connectJob = attempt
    }

    /**
     * Stops waiting for the device's «Allow debugging» dialog.
     *
     * Cancelling the coroutine alone is not enough: the transport blocks on a socket read that no
     * cancellation can interrupt, so the connection is closed explicitly. That is also what takes
     * the prompt off the device's screen — an abandoned prompt stays there and blocks the next
     * attempt (AOSP dispatches one prompt at a time), which is how 3.21.0 ended up with a queue of
     * dialogs nobody could answer.
     *
     * Only offered while a device is actually waiting for its owner: cancelling a finished attempt
     * would report a failure for a connection that is already open.
     */
    fun onCancelConnect() {
        val target = connectTarget ?: return
        val job = connectJob ?: return
        if (_state.value.statuses[target.id]?.state != AdbDeviceState.WAITING_FOR_CONFIRMATION) return
        // Invalidate the attempt before cancelling: the blocking transport will still return a
        // verdict after this, and it must not overwrite the cancellation below.
        attemptId++
        connectJob = null
        connectTarget = null
        job.cancel()
        scope.launch {
            backend.disconnect(target.host, target.port)
            _state.update { current ->
                current.copy(
                    busy = false,
                    connectInFlight = false,
                    statuses = current.statuses + (target.id to AdbDeviceStatus(AdbDeviceState.IDLE)),
                    notice = AdbNotice.ConnectCancelled(target.endpoint),
                ).withCancelFlag()
            }
        }
    }

    /**
     * One connection attempt, with its phases mirrored into the per-device status.
     *
     * The attempt is never retried here: a second connect while the first dialog is still on the
     * device's screen only queues another prompt, and the owner would see two dialogs for one tap.
     *
     * Returns null when the attempt was superseded (the owner pressed «Отмена», or a newer attempt
     * started) while the transport was still blocking: its verdict is stale, so neither the notice
     * nor the per-device status is written from it.
     */
    private suspend fun connect(device: AdbDevice, token: Long): AdbNotice? {
        setStatus(device.id, AdbDeviceStatus(AdbDeviceState.PROBING))
        return try {
            val connected = backend.connect(
                device.host,
                device.port,
                device.tls,
                firstContact = !device.trusted,
            ) { phase ->
                if (isLiveAttempt(token)) {
                    when (phase) {
                        AdbConnectPhase.PROBING -> setStatus(device.id, AdbDeviceStatus(AdbDeviceState.PROBING))

                        AdbConnectPhase.WAITING_FOR_CONFIRMATION -> {
                            setStatus(device.id, AdbDeviceStatus(AdbDeviceState.WAITING_FOR_CONFIRMATION))
                            _state.update { it.copy(notice = AdbNotice.WaitingForConfirmation(device.endpoint)) }
                        }

                        AdbConnectPhase.CONNECTED -> setStatus(device.id, AdbDeviceStatus(AdbDeviceState.CONNECTED))
                    }
                }
            }
            if (!isLiveAttempt(token)) return null
            if (!connected) {
                setStatus(device.id, AdbDeviceStatus(AdbDeviceState.ERROR, "no answer"))
                AdbNotice.NoAnswer(device.endpoint)
            } else {
                store.touch(
                    device.id,
                    lastModel = backend.readProductModelOrNull(device.host, device.port),
                    lastSeenEpochMs = Clock.System.now().toEpochMilliseconds(),
                )
                AdbNotice.Connected(device.endpoint)
            }
        } catch (cancelled: CancellationException) {
            setStatus(device.id, AdbDeviceStatus(AdbDeviceState.IDLE))
            throw cancelled
        } catch (failure: Throwable) {
            if (!isLiveAttempt(token)) return null
            // Throwable, not Exception: an Error from the transport must become a notice on the
            // screen instead of taking the process down mid-connection.
            val kind = (failure as? AdbException)?.kind
            // A transport failure can arrive without a message (a socket error, a TLS throwable);
            // the screen must say what failed instead of falling back to «не подключено».
            val detail = failure.message?.ifBlank { null } ?: failure::class.simpleName.orEmpty()
            setStatus(device.id, AdbDeviceStatus(AdbDeviceState.ERROR, detail))
            when (kind) {
                AdbErrorKind.AUTHENTICATION_FAILED, AdbErrorKind.PAIRING_REQUIRED -> AdbNotice.PairingRequiredAgain
                AdbErrorKind.TIMEOUT -> AdbNotice.DeviceDidNotConfirm(device.endpoint)
                AdbErrorKind.NOT_ADB -> AdbNotice.NotAdb(device.endpoint)
                AdbErrorKind.UNREACHABLE -> AdbNotice.NoAnswer(device.endpoint)
                else -> AdbNotice.ConnectFailed(failure.message ?: failure::class.simpleName.orEmpty())
            }
        }
    }

    /** True while [token] is still the attempt the screen is waiting for. */
    private fun isLiveAttempt(token: Long): Boolean = token == attemptId

    private fun connectInFlight(value: Boolean) {
        _state.update { it.copy(connectInFlight = value).withCancelFlag() }
    }

    /**
     * Recomputes [AdbSettingsUiState.canCancelConnect] from the live attempt and the status of the
     * device it is working on. [onDiagnose] can leave a device in the waiting state too, so the
     * status alone is not enough — the attempt's own target is what counts.
     */
    private fun AdbSettingsUiState.withCancelFlag(): AdbSettingsUiState {
        val target = connectTarget
        val waiting = target != null && statuses[target.id]?.state == AdbDeviceState.WAITING_FOR_CONFIRMATION
        return copy(canCancelConnect = connectInFlight && waiting)
    }

    private fun setStatus(id: String, status: AdbDeviceStatus) {
        _state.update { it.copy(statuses = it.statuses + (id to status)).withCancelFlag() }
    }

    private fun stateForProbe(result: AdbProbeResult): AdbDeviceState = when (result) {
        AdbProbeResult.Connected -> AdbDeviceState.CONNECTED
        AdbProbeResult.AuthRequired -> AdbDeviceState.WAITING_FOR_CONFIRMATION
        else -> AdbDeviceState.ERROR
    }

    private fun notice(value: AdbNotice) {
        _state.update { it.copy(notice = value) }
    }
}
