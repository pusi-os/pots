package com.pusios.pots.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the companion (CoPhone cube) dialog link is currently doing. */
enum class CompanionStatus {
    /** No cube available. */
    Unavailable,

    /** Master switch off or nothing paired. */
    Disconnected,

    /** Pairing in progress (advertise + SAS handshake). */
    Pairing,

    /** Paired and reachable. */
    Available,

    /** Listening to the cube's microphone. */
    Listening,

    /** The agent is working on the cube's turn. */
    Thinking,

    /** Reply is being spoken on the cube. */
    Speaking,

    /** Last attempt failed. */
    Error,
}

/** One bonded companion device (PotsCharm is single-bond for now). */
data class CompanionDevice(
    val id: String,
    val name: String,
    val mac: String,
    val nodeId: String,
    val transport: String = "BLE",
    val paired: Boolean = true,
    val connected: Boolean = false,
    val status: CompanionStatus = CompanionStatus.Disconnected,
    val lastSeenMs: Long = 0L,
    val battery: Int? = null,
)

interface CompanionController {
    val status: StateFlow<CompanionStatus>
    val devices: StateFlow<List<CompanionDevice>>

    val paired: Boolean

    fun refresh()
    fun setEnabled(enabled: Boolean)
    fun startPairing()
    fun cancelPairing()
    fun confirmCode(code: String): Boolean
    fun forget(deviceId: String)
    fun updateStatus(status: CompanionStatus)
}

class NoOpCompanionController : CompanionController {
    private val _status = MutableStateFlow(CompanionStatus.Unavailable)
    private val _devices = MutableStateFlow(emptyList<CompanionDevice>())
    override val status: StateFlow<CompanionStatus> = _status.asStateFlow()
    override val devices: StateFlow<List<CompanionDevice>> = _devices.asStateFlow()
    override val paired: Boolean = false

    override fun refresh() = Unit
    override fun setEnabled(enabled: Boolean) = Unit
    override fun startPairing() = Unit
    override fun cancelPairing() = Unit
    override fun confirmCode(code: String): Boolean = false
    override fun forget(deviceId: String) = Unit
    override fun updateStatus(status: CompanionStatus) = Unit
}

expect fun createCompanionController(): CompanionController
