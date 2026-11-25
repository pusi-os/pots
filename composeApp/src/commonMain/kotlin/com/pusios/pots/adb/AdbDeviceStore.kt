// The known-devices list as commonMain sees it: the agent tools and the settings screen work
// with endpoints, while the JSON file behind it lives in jvmShared (AdbDeviceRegistry), where
// java.io.File is available. Keeping the shape here is what lets AdbTools live in commonMain.

package com.pusios.pots.adb

interface AdbDeviceStore {
    /** Every known device, in the order it was added. */
    fun all(): List<AdbDevice>

    /** Adds [device], or replaces the entry with the same [AdbDevice.id]. Returns the new list. */
    fun upsert(device: AdbDevice): List<AdbDevice>

    /** Forgets the device with [id]. Returns the new list. */
    fun remove(id: String): List<AdbDevice>

    /** Flips the trust flag of [id]. Returns the new list. */
    fun setTrusted(id: String, trusted: Boolean): List<AdbDevice>

    /** Records a successful connection: the model it reported and when it answered. */
    fun touch(id: String, lastModel: String?, lastSeenEpochMs: Long): List<AdbDevice>

    /** The device with [id], or null when the owner never added it. */
    fun byId(id: String): AdbDevice? = all().firstOrNull { it.id == id }
}
