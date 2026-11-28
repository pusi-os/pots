package com.pusios.pots.handsfree

import android.app.ActivityManager
import android.content.Context
import com.pusios.pots.speech.sherpa.SherpaSttModels
import org.koin.java.KoinJavaComponent.inject

/**
 * The device's side of the engine decision: whether the selected neural model
 * is on disk, and how much RAM is free to run it in.
 *
 * Both answers are cheap — one marker file plus two `length()` calls, and one
 * `ActivityManager` query — so the settings card may ask on every recomposition.
 */
actual fun sttRuntimeFacts(neuralModelId: String): SttRuntimeFacts {
    val context: Context = try {
        val c: Context by inject(Context::class.java)
        c
    } catch (_: Exception) {
        return SttRuntimeFacts(neuralModelInstalled = false, availableMemoryMb = null)
    }
    return SttRuntimeFacts(
        neuralModelInstalled = SherpaSttModels.isInstalled(context, neuralModelId),
        availableMemoryMb = availableMemoryMb(context),
    )
}

/** Free RAM right now, or null when the system service is unavailable (previews, tests). */
internal fun availableMemoryMb(context: Context): Long? {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return info.availMem / (1024L * 1024L)
}
