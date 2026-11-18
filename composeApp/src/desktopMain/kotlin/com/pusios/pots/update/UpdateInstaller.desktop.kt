package com.pusios.pots.update

import com.pusios.pots.getAppFilesDirectory
import java.awt.Desktop
import java.io.File

actual fun openDownloadedUpdate(fileName: String): Boolean = try {
    val file = File(File(getAppFilesDirectory(), "updates"), fileName.substringAfterLast('/'))
    if (!file.isFile) {
        false
    } else {
        Desktop.getDesktop().open(file)
        true
    }
} catch (_: Exception) {
    // No handler registered for the container — the UI shows the path instead.
    false
}
