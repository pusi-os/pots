package com.pusios.pots.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.pusios.pots.readAppStorageBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coil model for a media source. Remote and inline sources pass through as is.
 * An app-storage path (gallery imports, cached results) is read to bytes first,
 * because Coil cannot resolve a relative path on its own and would show an empty box.
 */
@Composable
internal fun rememberImageModel(source: String?): Any? {
    val isRemoteOrInline = source == null || source.contains("://") || source.startsWith("data:")
    val bytes by produceState<ByteArray?>(null, source) {
        value = if (!isRemoteOrInline && source != null) {
            withContext(Dispatchers.Default) { readAppStorageBytes(source) }
        } else {
            null
        }
    }
    return if (isRemoteOrInline) source else bytes
}
