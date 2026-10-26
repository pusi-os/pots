package com.pusios.pots.tools

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_obsidian_pick_vault

@Composable
actual fun ObsidianVaultPicker(
    currentUri: String,
    onPicked: (String?) -> Unit,
) {
    if (LocalInspectionMode.current) {
        Button(onClick = {}) {
            Text(stringResource(Res.string.settings_obsidian_pick_vault))
        }
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        onPicked(uri.toString())
    }
    Button(onClick = { launcher.launch(null) }) {
        Text(stringResource(Res.string.settings_obsidian_pick_vault))
    }
}
