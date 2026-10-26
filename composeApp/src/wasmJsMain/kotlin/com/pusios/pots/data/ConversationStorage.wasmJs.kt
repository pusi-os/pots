package com.pusios.pots.data

import kotlinx.browser.localStorage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val STORAGE_KEY = "potsmutableConversations"

@OptIn(ExperimentalEncodingApi::class)
actual fun readLegacyConversationFile(): ByteArray? {
    val stored = localStorage.getItem(STORAGE_KEY) ?: return null
    if (stored.startsWith("{")) return null // Already plain JSON, not legacy
    return try {
        Base64.decode(stored)
    } catch (_: Exception) {
        null
    }
}

actual fun deleteLegacyConversationFile() {
    localStorage.removeItem(STORAGE_KEY)
}
