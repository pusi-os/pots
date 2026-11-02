package com.pusios.pots.speech.piper

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The one owner of Piper voices on this device.
 *
 * Voices ship inside the APK (`assets/piper/<voice id>/`); on first use they
 * are copied once into the app's files dir, where the sherpa-onnx runtime can
 * load them by path. A voice whose id has no bundled assets cannot be created.
 */
internal object PiperVoices {
    private const val TAG = "PiperVoices"
    private const val ASSET_ROOT = "piper"

    /** Voices that ship inside the app. */
    val VOICE_IDS: List<String> = listOf("ru_RU-irina-medium")

    /** True when this voice is bundled in the APK. */
    fun isBundled(ctx: Context, id: String): Boolean = !ctx.assets.list("$ASSET_ROOT/$id").isNullOrEmpty()

    /** The directory a voice lives in once installed. */
    fun dir(ctx: Context, id: String): File = File(ctx.filesDir, "$ASSET_ROOT/$id")

    /**
     * Copies the voice from the APK assets into the files dir (skipping files
     * that are already there) and returns its directory, or null when the
     * voice is not bundled or a file could not be installed.
     *
     * Blocking — run it off the main thread.
     */
    fun install(ctx: Context, id: String): File? {
        val names = ctx.assets.list("$ASSET_ROOT/$id")
        if (names.isNullOrEmpty()) return null
        val dest = dir(ctx, id)
        if (!dest.isDirectory && !dest.mkdirs()) return null
        for (name in names) {
            val file = File(dest, name)
            if (file.isFile && file.length() > 0) continue
            val part = File(dest, "$name.part")
            try {
                ctx.assets.open("$ASSET_ROOT/$id/$name").use { input ->
                    part.outputStream().use { out -> input.copyTo(out) }
                }
                if (!part.renameTo(file)) {
                    part.delete()
                    return null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "failed to install piper voice file $id/$name", t)
                part.delete()
                return null
            }
        }
        return dest
    }
}
