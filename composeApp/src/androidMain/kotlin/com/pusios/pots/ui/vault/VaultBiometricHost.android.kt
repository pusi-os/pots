// Vault quick unlock UI anchor on Android (card t_6b300431): the host is the
// FragmentActivity in composition — androidx.biometric needs one to show the
// system prompt. Inspection mode (paparazzi, previews) returns null.

package com.pusios.pots.ui.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.fragment.app.FragmentActivity

actual typealias VaultBiometricHost = FragmentActivity

@Composable
actual fun rememberVaultBiometricHost(): VaultBiometricHost? {
    if (LocalInspectionMode.current) return null
    val context = LocalContext.current
    return remember(context) { context.findFragmentActivity() }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
