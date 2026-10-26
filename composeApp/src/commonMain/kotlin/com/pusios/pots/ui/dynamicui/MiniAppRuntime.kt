package com.pusios.pots.ui.dynamicui

import androidx.compose.runtime.staticCompositionLocalOf
import com.pusios.pots.data.miniapp.MiniAppRecord
import kotlinx.coroutines.flow.StateFlow

/**
 * What a pots-ui tree may do when it is running as a saved app rather than as a
 * chat message: read the app's persistent records, write them, and move between
 * saved screens. Chat uses no runtime, so the same tree stays a pure chat UI.
 */
interface MiniAppRuntime {
    /** Bumped whenever the store changes, so record nodes recompose. */
    val revision: StateFlow<Int>

    fun records(collection: String): List<MiniAppRecord>

    fun onStorePut(action: StorePutAction, values: Map<String, String>)

    fun onStoreDelete(action: StoreDeleteAction, values: Map<String, String>)

    fun onNavigate(screenId: String)

    fun onHostCall(action: HostCallAction, values: Map<String, String>)
}

val LocalMiniAppRuntime = staticCompositionLocalOf<MiniAppRuntime?> { null }
