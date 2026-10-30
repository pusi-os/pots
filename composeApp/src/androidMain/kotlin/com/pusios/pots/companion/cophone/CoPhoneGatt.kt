package com.pusios.pots.companion.cophone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone side of the CoPhone link: a GATT server the cube (GATT client)
 * finds via the mesh PM2 (FLAG_PHONE, tapped as a candidate by detection),
 * connects to and subscribes on. One job: bytes across the air. Dialog
 * semantics live in [CoPhoneBridge]; this class never parses JSON or audio.
 *
 * No second advertiser on purpose: a separate service-UUID set gets evicted
 * by single-set controllers and silently kills the mesh presence. The GATT
 * server only listens.
 *
 * Threading: all Bluetooth callbacks arrive on binder threads — copy into
 * the listener immediately, never block. Notify chunking honors the
 * negotiated MTU (default 23 → 20-byte payloads).
 */
class CoPhoneGatt(
    private val context: Context,
    var listener: Listener,
) {
    interface Listener {
        fun onConnected(mac: String)
        fun onDisconnected(mac: String)
        fun onAudioUp(bytes: ByteArray)
        fun onTextUp(text: String)
        fun onCtrl(op: Int, rest: ByteArray)
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null

    /** True while the GATT server runs (discovery rides the mesh PM2, not a second advertiser). */
    private var advertising = false
    private val notifyOn = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val mtuOf = ConcurrentHashMap<String, Int>()

    // v2 framing (§7.4): ctrl/text arrive as [len LE16][payload] split across
    // notifies at the MTU; reassemble per connection before delivering.
    private val ctrlRx = ConcurrentHashMap<String, java.io.ByteArrayOutputStream>()
    private val textRx = ConcurrentHashMap<String, java.io.ByteArrayOutputStream>()

    // No second advertiser: the phone is found by the cube through its mesh
    // PM2 (FLAG_PHONE carries the address; detection taps it as a candidate).
    // A second legacy set gets evicted by single-set controllers, which
    // silently killed the MESH presence while the UUID slot looked alive.
    // The GATT server below only listens.

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val mac = device.address ?: return
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                mtuOf[mac] = 23
                Log.i(TAG, "cophone: connected $mac")
                listener.onConnected(mac)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                notifyOn.remove(mac)
                mtuOf.remove(mac)
                ctrlRx.remove(mac)
                textRx.remove(mac)
                Log.i(TAG, "cophone: disconnected $mac")
                listener.onDisconnected(mac)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            (device.address ?: return).let { mtuOf[it] = mtu }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD) {
                val on = value.isNotEmpty() && value[0].toInt() and 0x01 != 0
                val set = notifyOn.getOrPut(device.address ?: return) { mutableSetOf() }
                val charUuid = descriptor.characteristic.uuid
                if (on) set.add(charUuid) else set.remove(charUuid)
                Log.i(TAG, "cophone: cccd ${if (on) "on" else "off"} $charUuid ${device.address}")
            }
            try {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            } catch (t: Throwable) {
                Log.w(TAG, "cophone: descriptor rsp failed", t)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            try {
                if (responseNeeded) {
                    server?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null,
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cophone: write rsp failed", t)
            }
            val bytes = value ?: return
            when (characteristic.uuid) {
                CoPhoneUuids.AUDIO_UP -> if (bytes.isNotEmpty()) listener.onAudioUp(bytes)

                CoPhoneUuids.TEXT -> {
                    val mac = device.address ?: return
                    feed(textRx, mac, bytes) { listener.onTextUp(String(it, Charsets.UTF_8)) }
                }

                CoPhoneUuids.CTRL -> {
                    val mac = device.address ?: return
                    feed(ctrlRx, mac, bytes) {
                        if (it.isNotEmpty()) {
                            listener.onCtrl(it[0].toInt() and 0xFF, it.copyOfRange(1, it.size))
                        }
                    }
                }
            }
        }
    }

    /** Append [data] and deliver every complete [len LE16][payload] frame. */
    private fun feed(
        streams: ConcurrentHashMap<String, java.io.ByteArrayOutputStream>,
        mac: String,
        data: ByteArray,
        deliver: (ByteArray) -> Unit,
    ) {
        if (data.isEmpty()) return
        val s = streams.getOrPut(mac) { java.io.ByteArrayOutputStream() }
        synchronized(s) {
            s.write(data)
            var buf = s.toByteArray()
            var off = 0
            while (buf.size - off >= 2) {
                val pl = (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
                if (off + 2 + pl > buf.size) break
                deliver(buf.copyOfRange(off + 2, off + 2 + pl))
                off += 2 + pl
            }
            if (off > 0) {
                s.reset()
                if (off < buf.size) s.write(buf, off, buf.size - off)
            }
        }
    }

    fun isAdvertising(): Boolean = advertising

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (server != null) return true
        val mgr = manager
        val srv = try {
            mgr.openGattServer(context, gattCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "cophone: openGattServer failed", t)
            null
        } ?: return false
        try {
            srv.addService(buildService())
        } catch (t: Throwable) {
            Log.w(TAG, "cophone: addService failed", t)
            try {
                srv.close()
            } catch (_: Throwable) {
            }
            return false
        }
        server = srv
        advertising = true
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        advertising = false
        try {
            server?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "cophone: close failed", t)
        }
        server = null
        notifyOn.clear()
    }

    private fun chr(
        uuid: UUID,
        props: Int,
        perms: Int,
    ) = BluetoothGattCharacteristic(uuid, props, perms)

    private fun buildService(): BluetoothGattService {
        val svc = BluetoothGattService(CoPhoneUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val write = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        val wPerm = BluetoothGattCharacteristic.PERMISSION_WRITE
        val rPerm = BluetoothGattCharacteristic.PERMISSION_READ
        // TEXT/CTRL must accept BOTH write types: the cube writes them as
        // WRITE_CMD (no response) -- the mesh path has always done that, and
        // a characteristic without WRITE_NO_RESPONSE silently drops a command
        // at the ATT layer. That is exactly why the very first AUTH_CHAL
        // vanished: the link was up, the write "succeeded", nothing arrived.
        val both = write or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val up = chr(CoPhoneUuids.AUDIO_UP, write, wPerm)
        val down = chr(CoPhoneUuids.AUDIO_DOWN, notify, rPerm)
        down.addDescriptor(BluetoothGattDescriptor(CCCD, rPerm or wPerm))
        val text = chr(CoPhoneUuids.TEXT, both or notify, wPerm or rPerm)
        text.addDescriptor(BluetoothGattDescriptor(CCCD, rPerm or wPerm))
        val ctrl = chr(CoPhoneUuids.CTRL, both or notify, wPerm or rPerm)
        ctrl.addDescriptor(BluetoothGattDescriptor(CCCD, rPerm or wPerm))
        svc.addCharacteristic(up)
        svc.addCharacteristic(down)
        svc.addCharacteristic(text)
        svc.addCharacteristic(ctrl)
        return svc
    }

    /** Notify one connected cube; chunks to its MTU. Best-effort, never throws. */
    @SuppressLint("MissingPermission")
    fun notify(mac: String, uuid: UUID, payload: ByteArray): Boolean {
        val srv = server ?: return false
        val dev = try {
            manager.adapter?.getRemoteDevice(mac)
        } catch (t: Throwable) {
            null
        } ?: return false
        if (notifyOn[mac]?.contains(uuid) != true) {
            Log.w(TAG, "cophone: notify $uuid skipped, no subscription from $mac")
            return false
        }
        val char = srv.getService(CoPhoneUuids.SERVICE)?.getCharacteristic(uuid) ?: return false
        // Audio is one self-contained frame per notify; ctrl/text carry a
        // 2-byte LE length prefix so the cube can reassemble across the MTU.
        val wire = if (uuid == CoPhoneUuids.AUDIO_DOWN) payload else frame(payload)
        val chunk = (mtuOf[mac] ?: 23) - 3
        var off = 0
        var ok = true
        while (off < wire.size) {
            val n = minOf(chunk, wire.size - off)
            try {
                char.value = wire.copyOfRange(off, off + n)
                if (!srv.notifyCharacteristicChanged(dev, char, false)) ok = false
            } catch (t: Throwable) {
                Log.w(TAG, "cophone: notify failed", t)
                return false
            }
            off += n
        }
        return ok
    }

    private fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = (payload.size and 0xFF).toByte()
        out[1] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, 2)
        return out
    }

    companion object {
        private const val TAG = "CoPhoneGatt"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
