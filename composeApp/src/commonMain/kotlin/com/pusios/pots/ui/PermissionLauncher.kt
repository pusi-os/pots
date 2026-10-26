package com.pusios.pots.ui

import androidx.compose.runtime.Composable

@Composable
expect fun rememberPermissionLauncher(onResult: (Boolean) -> Unit): (String) -> Unit
