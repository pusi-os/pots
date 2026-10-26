package com.pusios.pots.tools

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_obsidian_pick_vault

@Composable
actual fun ObsidianVaultPicker(currentUri: String, onPicked: (String?) -> Unit) {
    Button(onClick = {}, enabled = false) {
        Text(stringResource(Res.string.settings_obsidian_pick_vault))
    }
}
