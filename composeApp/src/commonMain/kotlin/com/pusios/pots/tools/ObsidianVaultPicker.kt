package com.pusios.pots.tools

import androidx.compose.runtime.Composable

@Composable
expect fun ObsidianVaultPicker(
    currentUri: String,
    onPicked: (String?) -> Unit,
)
