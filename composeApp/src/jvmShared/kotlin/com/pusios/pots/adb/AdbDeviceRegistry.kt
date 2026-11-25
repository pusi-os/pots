package com.pusios.pots.adb

import com.pusios.pots.data.SharedJson
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Directory under `filesDir` that holds the host key and the device registry.
 *
 * It is excluded from both backup mechanisms in `backup_rules.xml` and `data_extraction_rules.xml`:
 * a host key that leaves the device in a cloud backup is a key an attacker can inherit.
 */
const val ADB_DIRECTORY = "adb"

@Serializable
private data class AdbDeviceFile(val devices: List<AdbDevice> = emptyList())

/**
 * The known devices, persisted as `filesDir/adb/devices.json`.
 *
 * A plain JSON file rather than a settings slot or a database: the list is short, it is written only
 * when the user adds, removes or connects to a device, and a human-readable file is what makes a
 * support report possible. A corrupt or unreadable file degrades to an empty list — losing the list
 * costs a re-add, while throwing on read would make the settings screen unusable.
 *
 * Reads and writes go through one lock and a temp-file rename, so a process death mid-write cannot
 * leave half a JSON document behind.
 */
class AdbDeviceRegistry(private val directory: File) : AdbDeviceStore {
    private val file = File(directory, "devices.json")
    private val lock = Any()

    /** Every known device, in the order it was added. */
    override fun all(): List<AdbDevice> = synchronized(lock) { read() }

    /** Adds [device], or replaces the entry with the same [AdbDevice.id]. Returns the new list. */
    override fun upsert(device: AdbDevice): List<AdbDevice> = synchronized(lock) {
        val devices = read().toMutableList()
        val index = devices.indexOfFirst { it.id == device.id }
        if (index >= 0) devices[index] = device else devices.add(device)
        write(devices)
        devices.toList()
    }

    /** Forgets the device with [id]. Returns the new list. */
    override fun remove(id: String): List<AdbDevice> = synchronized(lock) {
        val devices = read().filterNot { it.id == id }
        write(devices)
        devices
    }

    /** Flips the trust flag of [id] — the settings screen's "Trust" toggle. Returns the new list. */
    override fun setTrusted(id: String, trusted: Boolean): List<AdbDevice> = synchronized(lock) {
        val devices = read().map { if (it.id == id) it.copy(trusted = trusted) else it }
        write(devices)
        devices
    }

    /** Records a successful connection: the model it reported and when it answered. */
    override fun touch(id: String, lastModel: String?, lastSeenEpochMs: Long): List<AdbDevice> = synchronized(lock) {
        val devices = read().map { device ->
            if (device.id == id) {
                device.copy(lastModel = lastModel ?: device.lastModel, lastSeenEpochMs = lastSeenEpochMs)
            } else {
                device
            }
        }
        write(devices)
        devices
    }

    private fun read(): List<AdbDevice> {
        if (!file.isFile) return emptyList()
        return runCatching { SharedJson.decodeFromString<AdbDeviceFile>(file.readText()).devices }
            .getOrDefault(emptyList())
    }

    private fun write(devices: List<AdbDevice>) {
        directory.mkdirs()
        val payload = SharedJson.encodeToString(AdbDeviceFile(devices))
        val temp = File(directory, "devices.json.tmp")
        temp.writeText(payload)
        runCatching {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            file.writeText(payload)
            temp.delete()
        }
    }
}
