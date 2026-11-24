// Vault export/import file pickers on Android: SAF CreateDocument /
// OpenDocument. Bytes are produced and written on IO after the system sheet
// closes; cancelled picks report false / null like the desktop flow.

package com.pusios.pots.ui.vault

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val EXPORT_MIME = "application/octet-stream"

@Composable
actual fun VaultExportPickButton(
    label: String,
    enabled: Boolean,
    fileName: String,
    produceBytes: suspend () -> ByteArray,
    onDone: (Boolean) -> Unit,
) {
    if (LocalInspectionMode.current) {
        TextButton(onClick = {}, enabled = false) { Text(label) }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(EXPORT_MIME),
    ) { uri ->
        if (uri == null) {
            onDone(false)
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            val bytes = runCatching { produceBytes() }.getOrNull()
            val written = if (bytes == null) {
                false
            } else {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                }.getOrDefault(false)
            }
            onDone(written)
        }
    }
    TextButton(onClick = { launcher.launch(fileName) }, enabled = enabled) {
        Text(label)
    }
}

@Composable
actual fun VaultImportPickButton(
    label: String,
    enabled: Boolean,
    onLoaded: (VaultImportSource?) -> Unit,
) {
    if (LocalInspectionMode.current) {
        TextButton(onClick = {}, enabled = false) { Text(label) }
        return
    }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        onLoaded(
            uri?.let { picked ->
                object : VaultImportSource {
                    override fun read(): ByteArray? = readContent(context, picked)
                }
            },
        )
    }
    TextButton(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = enabled) {
        Text(label)
    }
}

private fun readContent(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}.getOrNull()
