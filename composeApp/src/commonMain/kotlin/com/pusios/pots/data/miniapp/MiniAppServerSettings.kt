package com.pusios.pots.data.miniapp

import com.pusios.pots.data.AppSettings

/**
 * The built-in server's preferences. They live here rather than on AppSettings
 * so the server feature can evolve without turning the settings class into the
 * place every new subsystem's flags accumulate.
 */
fun AppSettings.isMiniAppServerEnabled(): Boolean = settings.getBoolean(KEY_ENABLED, false)

fun AppSettings.setMiniAppServerEnabled(enabled: Boolean) = settings.putBoolean(KEY_ENABLED, enabled)

fun AppSettings.isMiniAppServerBackgroundEnabled(): Boolean = settings.getBoolean(KEY_BACKGROUND, false)

fun AppSettings.setMiniAppServerBackgroundEnabled(enabled: Boolean) = settings.putBoolean(KEY_BACKGROUND, enabled)

fun AppSettings.getMiniAppServerPort(): Int = settings.getInt(KEY_PORT, MiniAppServerState.DEFAULT_PORT)

fun AppSettings.setMiniAppServerPort(port: Int) = settings.putInt(KEY_PORT, port)

private const val KEY_ENABLED = "mini_app_server_enabled"
private const val KEY_BACKGROUND = "mini_app_server_background"
private const val KEY_PORT = "mini_app_server_port"
