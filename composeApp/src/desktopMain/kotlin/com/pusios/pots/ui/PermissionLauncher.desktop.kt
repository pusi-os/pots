package com.pusios.pots.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPermissionLauncher(onResult: (Boolean) -> Unit): (String) -> Unit = { _ -> onResult(true) }
