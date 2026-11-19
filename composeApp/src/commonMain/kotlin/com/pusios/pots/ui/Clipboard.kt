package com.pusios.pots.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/**
 * [sensitive] marks the clip as one the system should not show back to the user
 * — no preview of the contents, no entry in the clipboard history. Platforms
 * that have no such notion ignore it.
 */
internal expect fun clipEntryOfText(text: String, sensitive: Boolean): ClipEntry

@Composable
internal fun rememberCopyToClipboard(sensitive: Boolean = false): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope, sensitive) {
        { text ->
            scope.launch {
                clipboard.setClipEntry(clipEntryOfText(text, sensitive))
            }
        }
    }
}
