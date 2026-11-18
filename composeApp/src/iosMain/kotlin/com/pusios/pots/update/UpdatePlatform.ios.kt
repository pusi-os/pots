package com.pusios.pots.update

/**
 * iOS has no self-update: builds are installed through the App Store, so there is nothing
 * for the app to download and hand over.
 */
actual fun openUpdateFileSink(fileName: String): UpdateFileSink? = null

actual val hostArchitecture: UpdateArch = UpdateArch.Arm64

/** Nothing to launch: the App Store owns installation. */
actual fun openDownloadedUpdate(fileName: String): Boolean = false
