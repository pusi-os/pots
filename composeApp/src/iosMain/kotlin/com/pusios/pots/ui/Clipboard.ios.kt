package com.pusios.pots.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

/** UIPasteboard has no notion of a sensitive entry: [sensitive] is ignored. */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun clipEntryOfText(text: String, sensitive: Boolean): ClipEntry = ClipEntry.withPlainText(text)
