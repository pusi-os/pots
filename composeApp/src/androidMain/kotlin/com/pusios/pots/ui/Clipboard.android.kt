package com.pusios.pots.ui

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry

/**
 * A sensitive clip is one the system must not show back: `EXTRA_IS_SENSITIVE`
 * keeps it out of the clipboard history and replaces the contents in the paste
 * preview (API 33+; older releases ignore the flag, which is why the password is
 * also selectable on screen). The constant is a compile-time string, so naming
 * it costs nothing on the versions that predate it.
 */
internal actual fun clipEntryOfText(text: String, sensitive: Boolean): ClipEntry {
    val description = ClipDescription("plain text", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN))
    if (sensitive) {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    return ClipEntry(ClipData(description, ClipData.Item(text)))
}
