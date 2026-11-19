package com.pusios.pots.ui

import androidx.compose.ui.platform.ClipEntry

/** The browser clipboard has no notion of a sensitive entry: [sensitive] is ignored. */
internal actual fun clipEntryOfText(text: String, sensitive: Boolean): ClipEntry = ClipEntry.withPlainText(text)
