package com.pusios.pots.companion

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.pusios.pots.companion.cophone.CoPhoneCrypto
import com.pusios.pots.companion.cophone.CoPhoneGatt
import com.pusios.pots.companion.cophone.CoPhonePrefs
import com.pusios.pots.companion.cophone.CoPhoneUuids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CompanionControllerImpl(
    private val context: Context,
) : CompanionController {
    private var currentMac: String = ""
    private val prefs = CoPhonePrefs(context)
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _status = MutableStateFlow(
        if (prefs.paired) CompanionStatus.Available else CompanionStatus.Disconnected,
    )
    private val _devices = MutableStateFlow(devicesFromPrefs())

    override val status: StateFlow<CompanionStatus> = _status.asStateFlow()
    override val devices: StateFlow<List<CompanionDevice>> = _devices.asStateFlow()
    override val paired: Boolean get() = prefs.paired
    private val _enabled = MutableStateFlow(prefs.enabled)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        // Resume the link on process start so paired cubes can find the
        // phone again without reopening Settings.
        if (prefs.enabled) setEnabled(true)
    }

    private var gatt: CoPhoneGatt? = null

    private fun devicesFromPrefs(): List<CompanionDevice> {
        if (!prefs.paired) return emptyList()
        return listOf(
            CompanionDevice(
                id = prefs.deviceNodeHex.ifBlank { prefs.deviceMac },
                name = prefs.deviceName.ifBlank { "PotsCharm" },
                mac = prefs.deviceMac,
                nodeId = prefs.deviceNodeHex,
                connected = prefs.connected,
                status = if (prefs.connected) CompanionStatus.Available else CompanionStatus.Disconnected,
            ),
        )
    }

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun advertisePairing(callback: AdvertiseCallback) {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(CoPhoneUuids.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        advertiser.startAdvertising(settings, data, callback)
    }

    private val gattListener = object : CoPhoneGatt.Listener {
        override fun onConnected(mac: String) {
            currentMac = mac
            if (prefs.paired) {
                prefs.connected = true
                prefs.connectedAtMs = System.currentTimeMillis()
            }
            _status.value = CompanionStatus.Pairing
            _devices.value = devicesFromPrefs().map { it.copy(status = _status.value) }
        }

        override fun onDisconnected(mac: String) {
            prefs.connected = false
            prefs.connectedAtMs = 0L
            _status.value = if (prefs.paired) CompanionStatus.Disconnected else CompanionStatus.Disconnected
            _devices.value = devicesFromPrefs()
        }

        override fun onAudioUp(bytes: ByteArray) = Unit
        override fun onTextUp(text: String) = Unit
        override fun onCtrl(op: Int, rest: ByteArray) {
            val mac = currentMac
            if (mac.isBlank()) return
            when (op) {
                0x13 -> { // OP_PAIR_HELLO from the cube
                    val cubeEph = rest.copyOfRange(0, 32.coerceAtMost(rest.size))
                    val cubeNode = rest.copyOfRange(32, 40.coerceAtMost(rest.size))
                    if (cubeEph.size < 32 || cubeNode.size < 8) return
                    if (prefs.phoneIdHex.length != 12) {
                        val id = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
                        prefs.phoneIdHex = bytesToHex(id)
                    }
                    val phoneId = bytesFromHex(prefs.phoneIdHex) ?: return
                    val priv = CoPhoneCrypto.randomScalar()
                    val pub = CoPhoneCrypto.publicKey(priv)
                    val shared = CoPhoneCrypto.x25519(priv, cubeEph)
                    val pairKey = CoPhoneCrypto.pairKey(shared, cubeNode, phoneId)
                    prefs.pairKeyHex = bytesToHex(pairKey)
                    prefs.deviceNodeHex = bytesToHex(cubeNode)
                    prefs.sas = CoPhoneCrypto.sas(pairKey)
                    prefs.awaitCode = true
                    prefs.pairing = true
                    val reply = pub + phoneId
                    gatt?.notify(mac, CoPhoneUuids.CTRL, byteArrayOf(0x14.toByte()) + reply)
                    _status.value = CompanionStatus.Pairing
                    _devices.value = devicesFromPrefs()
                }

                0x15 -> { // OP_PAIR_ACCEPT from cube (ack) or confirmation
                    prefs.pairing = false
                    prefs.awaitCode = false
                    prefs.connected = true
                    prefs.connectedAtMs = System.currentTimeMillis()
                    _status.value = CompanionStatus.Available
                    _devices.value = devicesFromPrefs().map { it.copy(connected = true, status = CompanionStatus.Available) }
                }

                0x17 -> { // auth challenge
                    val pairKey = bytesFromHex(prefs.pairKeyHex)
                    if (pairKey == null) return
                    val phoneNonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    val respKey = CoPhoneCrypto.respKey(pairKey)
                    val msg = rest.copyOfRange(0, 16) + phoneNonce
                    val hmac = CoPhoneCrypto.hmacSha256(respKey, msg).copyOfRange(0, 16)
                    val reply = hmac + phoneNonce
                    gatt?.notify(mac, CoPhoneUuids.CTRL, byteArrayOf(0x18.toByte()) + reply)
                }

                0x19 -> { // auth OK
                    prefs.connected = true
                    prefs.connectedAtMs = System.currentTimeMillis()
                    _status.value = CompanionStatus.Available
                }
            }
        }
    }

    override fun refresh() {
        _status.value = when {
            !prefs.enabled -> CompanionStatus.Disconnected
            prefs.paired -> if (prefs.connected) CompanionStatus.Available else CompanionStatus.Disconnected
            else -> CompanionStatus.Disconnected
        }
        _devices.value = devicesFromPrefs()
    }

    override fun setEnabled(enabled: Boolean) {
        prefs.enabled = enabled
        _enabled.value = enabled
        if (!enabled) {
            cancelPairing()
            _status.value = CompanionStatus.Disconnected
            stopGatt()
        } else {
            if (gatt == null) {
                try {
                    val g = CoPhoneGatt(context, gattListener)
                    if (g.start()) gatt = g
                } catch (t: Throwable) {
                    _status.value = CompanionStatus.Error
                }
            }
        }
        refresh()
    }

    override fun startPairing() {
        if (prefs.phoneIdHex.length != 12) {
            val id = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
            prefs.phoneIdHex = bytesToHex(id)
        }
        prefs.pairing = true
        prefs.awaitCode = false
        if (!prefs.enabled) prefs.enabled = true
        _status.value = CompanionStatus.Pairing
        try {
            val advertiseCallback = object : AdvertiseCallback() {}
            advertisePairing(advertiseCallback)
        } catch (t: Throwable) {
            _status.value = CompanionStatus.Error
            return
        }
        if (gatt == null) {
            try {
                val g = CoPhoneGatt(context, gattListener)
                if (g.start()) gatt = g
            } catch (t: Throwable) {
                _status.value = CompanionStatus.Error
            }
        }
    }

    override fun cancelPairing() {
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(object : AdvertiseCallback() {})
        stopGatt()
        prefs.pairing = false
        refresh()
    }

    override fun confirmCode(code: String): Boolean {
        val pairKey = bytesFromHex(prefs.pairKeyHex) ?: return false
        val cubeNode = bytesFromHex(prefs.deviceNodeHex) ?: return false
        val phoneId = bytesFromHex(prefs.phoneIdHex) ?: return false
        if (code.length != 6) return false
        val hmac = CoPhoneCrypto.confirmHmac(pairKey, cubeNode, phoneId, code)
        val payload = phoneId + code.toByteArray(Charsets.UTF_8) + hmac
        val mac = currentMac
        if (mac.isBlank()) return false
        gatt?.notify(mac, CoPhoneUuids.CTRL, byteArrayOf(0x15.toByte()) + payload)
        prefs.awaitCode = false
        return true
    }

    override fun forget(deviceId: String) {
        prefs.forget()
        stopGatt()
        _status.value = CompanionStatus.Disconnected
        _devices.value = emptyList()
    }

    override fun updateStatus(status: CompanionStatus) {
        _status.value = status
        _devices.value = devicesFromPrefs().map { it.copy(status = status, connected = status != CompanionStatus.Disconnected) }
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun bytesFromHex(hex: String): ByteArray? {
        val t = hex.trim().lowercase()
        if (t.length % 2 != 0) return null
        return try {
            ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun stopGatt() {
        gatt?.stop()
        gatt = null
    }
}

actual fun createCompanionController(): CompanionController {
    val app = com.pusios.pots.PotsAppContext.application
        ?: throw IllegalStateException("PotsApplication not initialized")
    return CompanionControllerImpl(app)
}
