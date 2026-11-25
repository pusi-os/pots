// Where a downloaded APK lands before it is pushed: commonMain cannot touch the filesystem, so
// Android supplies this and the tools only ever see the path it returns.

package com.pusios.pots.adb

/**
 * Fetches an APK to a file this app can push to a device.
 *
 * The tools live in commonMain and must not touch the filesystem, so the download-and-store step
 * is injected: Android downloads into the app cache (`cacheDir`), a test hands back a fixture path.
 * Throwing is part of the contract — a broken URL, a 404 or a full disk is reported to the model as
 * a failed install, not as an empty file.
 */
interface AdbApkCache {
    /** Downloads [url] and returns the absolute path of the local APK. */
    suspend fun download(url: String): String
}
