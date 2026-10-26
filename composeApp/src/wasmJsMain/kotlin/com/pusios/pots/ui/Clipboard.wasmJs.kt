package com.pusios.pots.ui

import androidx.compose.ui.platform.ClipEntry

internal actual fun clipEntryOfPlainText(text: String): ClipEntry = ClipEntry.withPlainText(text)
