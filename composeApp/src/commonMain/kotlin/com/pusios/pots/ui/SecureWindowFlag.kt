// Vault credential sheets must not leak into screenshots, screen recordings or
// the app switcher preview: while a sheet is composed, the window gets the
// platform's "secure" flag (Android FLAG_SECURE). Platforms without such a
// notion do nothing.

package com.pusios.pots.ui

import androidx.compose.runtime.Composable

/** While composed with [enabled], the window content is protected from capture. */
@Composable
expect fun SecureWindowFlag(enabled: Boolean)
