package com.pusios.pots.update

import android.content.Context
import com.pusios.pots.getAppFilesDirectory
import com.pusios.pots.sandbox.openFileWithIntent
import org.koin.java.KoinJavaComponent.inject
import java.io.File

/**
 * Opens the downloaded APK through the system package installer. [openFileWithIntent] already
 * refuses when the build does not declare `REQUEST_INSTALL_PACKAGES`, so the Play Store flavor
 * reports the file instead of failing silently.
 */
actual fun openDownloadedUpdate(fileName: String): Boolean = try {
    val context: Context by inject(Context::class.java)
    val file = File(File(getAppFilesDirectory(), "updates"), fileName.substringAfterLast('/'))
    file.isFile && openFileWithIntent(context, file).success
} catch (_: Exception) {
    false
}
