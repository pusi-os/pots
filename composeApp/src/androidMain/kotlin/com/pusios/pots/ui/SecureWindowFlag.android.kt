// FLAG_SECURE on the activity window while a vault sheet is composed. The
// context walks ContextWrapper chains up to the Activity — a Compose view
// context is usually wrapped in a theme or a wrapper context.

package com.pusios.pots.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

@Composable
actual fun SecureWindowFlag(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        val window = view.context.findActivity()?.window
        val hadFlag = window != null &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (window != null && enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            // Restore exactly what was there: the app may hold the flag for
            // its own reasons (a future privacy mode) — do not clear those.
            if (window != null && enabled && !hadFlag) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
