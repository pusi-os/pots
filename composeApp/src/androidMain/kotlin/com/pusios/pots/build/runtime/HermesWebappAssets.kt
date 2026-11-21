package com.pusios.pots.build.runtime

import android.content.res.AssetManager
import android.util.Log
import com.pusios.pots.build.HermesWebapp
import java.io.File

private const val TAG = "PotsWebappBundle"

/** Directory in this app's assets that CI fills with the Hermes panel bundle. */
private const val ASSET_DIR = "hermes-webapp"

/** The hermes-agent commit the bundle was built from, written by the bundle action. */
private const val ASSET_PIN = "$ASSET_DIR/pin"

/** `apps/desktop/dist-webapp`, packed as one `tar.xz`. */
private const val ASSET_ARCHIVE = "$ASSET_DIR/dist.tar.xz"

/**
 * The Hermes panel bundle this APK carries: the hermes-agent commit CI built it
 * from, and the archive itself.
 *
 * A developer build has neither — only CI fills that asset directory — and then
 * every method here answers null or does nothing, which leaves the sandbox on its
 * own checkout's renderer. See [HermesWebapp] for why the bundle is shipped at all.
 *
 * Both files land under `/root` in the guest, so they sit beside the checkout
 * rather than inside it: a bundle in `apps/desktop/dist-webapp` would read as
 * Hermes' own build and be rebuilt (and marked stale) by the next `hermes update`.
 */
object HermesWebappAssets {

    /** The commit this app's panel was built from, or null when this build ships none. */
    fun pin(assets: AssetManager): String? = runCatching {
        assets.open(ASSET_PIN).bufferedReader().use { it.readText() }.trim().ifEmpty { null }
    }.getOrElse { null }

    /**
     * Copies the shipped archive into the guest, once per bundle.
     *
     * The pin file doubles as the stamp: when it already names [pin] and the
     * archive is there, the copy is skipped — otherwise every panel start would
     * write 14 MB again. A new APK's bundle carries a new pin, so its archive
     * replaces the old one instead of the old one being served against it.
     */
    fun stageArchive(assets: AssetManager, pin: String, homeDir: File): Boolean = runCatching {
        val dir = File(homeDir, ".hermes")
        dir.mkdirs()
        val stamp = File(dir, "pots-webapp-pin")
        val archive = File(dir, "pots-webapp-dist.tar.xz")
        val current = stamp.isFile && archive.isFile && stamp.readText().trim() == pin
        if (!current) {
            assets.open(ASSET_ARCHIVE).use { input -> archive.outputStream().use { input.copyTo(it) } }
            stamp.writeText("$pin\n")
        }
        true
    }.onFailure { Log.w(TAG, "could not stage the Hermes panel bundle", it) }.getOrDefault(false)
}
