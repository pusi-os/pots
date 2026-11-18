package com.pusios.pots.update

/** The web build has no filesystem and is reloaded, not installed — nothing to download. */
actual fun openUpdateFileSink(fileName: String): UpdateFileSink? = null

actual val hostArchitecture: UpdateArch = UpdateArch.X64

/** Nothing to launch: the web build has no filesystem. */
actual fun openDownloadedUpdate(fileName: String): Boolean = false
