// The product model a device reports, kept out of AdbTools because the settings screen needs the
// same call: the registry's `lastModel` is filled by both the agent's connect tool and the screen's
// «Подключить», and neither should have to depend on the other's file.

package com.pusios.pots.adb

/**
 * Best-effort product model for the registry's `lastModel`. A device that answers `connect` but not
 * `getprop` still connects, so a failed read is null, not an error.
 */
internal suspend fun AdbBackend.readProductModelOrNull(host: String, port: Int): String? = try {
    shell(host, port, "getprop ro.product.model", AdbTimeouts.SHELL_SEC).trim().ifEmpty { null }
} catch (failure: AdbException) {
    null
}
