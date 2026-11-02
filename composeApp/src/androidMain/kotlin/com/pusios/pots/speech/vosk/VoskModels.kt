package com.pusios.pots.speech.vosk

import android.content.Context
import android.util.Log
import org.vosk.Model
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The one owner of Vosk models on this device.
 *
 * A model is installed from the zip bundled in the app assets when it has one
 * (the out-of-the-box Russian model), or downloaded from alphacephei.com
 * otherwise. Installed models live in the app's files dir and are loaded once
 * into memory, then shared by every consumer: the hands-free recognizer and
 * the ESP32 companion dialogue.
 *
 * One install runs at a time; [progress]/[status] describe it for the UI.
 */
internal object VoskModels {
    private const val TAG = "VoskModels"

    data class ModelSpec(
        /** Stable id, persisted in settings (matches the upstream model dir). */
        val id: String,
        val label: String,
        val language: String,
        val dir: String,
        /** Zip bundled with the APK ("installed from the box"), if any. */
        val assetPath: String?,
        val urls: List<String>,
        /** Approximate installed size, for the settings UI. */
        val sizeMb: Int,
    )

    /** The bundled Russian small model is the out-of-the-box default. */
    const val DEFAULT_MODEL_ID = "vosk-model-small-ru-0.22"

    val MODELS: List<ModelSpec> = listOf(
        ModelSpec(
            id = "vosk-model-small-ru-0.22",
            label = "Русский (офлайн, рекомендуется)",
            language = "ru",
            dir = "vosk-model-small-ru-0.22",
            assetPath = "vosk/vosk-model-small-ru-0.22.zip",
            urls = listOf("https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"),
            sizeMb = 45,
        ),
        ModelSpec(
            id = "vosk-model-small-en-us-0.15",
            label = "English (offline)",
            language = "en",
            dir = "vosk-model-small-en-us-0.15",
            assetPath = null,
            urls = listOf("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
            sizeMb = 40,
        ),
        ModelSpec(
            id = "vosk-model-ru-0.42",
            label = "Русский (большая, высокая точность)",
            language = "ru",
            dir = "vosk-model-ru-0.42",
            assetPath = null,
            urls = listOf("https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip"),
            sizeMb = 1800,
        ),
    )

    fun model(id: String): ModelSpec = MODELS.firstOrNull { it.id == id } ?: MODELS[0]

    private val loaded = HashMap<String, Model>()
    private val failed = HashSet<String>()

    @Volatile
    private var installing = false

    /** Install/download progress 0–100 (-1 = idle or indeterminate). */
    @Volatile
    var progress: Int = -1
        private set

    /** The model an install is currently running for, or null when idle. */
    @Volatile
    var installingId: String? = null
        private set

    /** Human-readable state for diagnostics and the settings UI. */
    @Volatile
    var status: String = "init"
        private set

    /** True once the given model is loaded (not merely present on disk). */
    fun ready(id: String): Boolean = loaded.containsKey(id)

    /** The loaded model instance, or null when it is not loaded yet. */
    fun loadedModel(id: String): Model? = loaded[id]

    /** True while an install/download/unpack is in flight. */
    fun isInstalling(): Boolean = installing

    private fun dir(ctx: Context, id: String) = File(ctx.filesDir, model(id).dir)

    private fun filesPresent(dir: File): Boolean = File(dir, "conf/mfcc.conf").isFile &&
        File(dir, "am/final.mdl").isFile && File(dir, "graph/HCLr.fst").isFile &&
        File(dir, "graph/Gr.fst").isFile

    fun modelPresent(ctx: Context, id: String): Boolean = filesPresent(dir(ctx, id))

    /** Total bytes of an installed model on disk (0 = not installed). */
    fun installedSize(ctx: Context, id: String): Long {
        val d = dir(ctx, id)
        if (!d.isDirectory) return 0
        var s = 0L
        d.walkTopDown().forEach { if (it.isFile) s += it.length() }
        return s
    }

    /** Remove a model from disk and forget the loaded instance. */
    fun deleteModel(ctx: Context, id: String) {
        synchronized(this) {
            loaded.remove(id)
            failed.remove(id)
            progress = -1
            status = "removed"
            dir(ctx, id).deleteRecursively()
        }
    }

    /**
     * Load the model (installing it first if needed: bundled asset, then
     * network download). Safe to call repeatedly; blocking — run it off the
     * main thread.
     */
    fun ensure(ctx: Context, id: String): Model? {
        loaded[id]?.let { return it }
        if (failed.contains(id)) return null
        synchronized(this) {
            loaded[id]?.let { return it }
            try {
                val spec = model(id)
                val d = dir(ctx, id)
                if (!filesPresent(d)) {
                    d.deleteRecursively()
                    status = "installing"
                    if (!install(ctx, spec, d)) {
                        failed.add(id)
                        status = "install failed"
                        return null
                    }
                }
                status = "loading"
                val m = Model(d.absolutePath)
                loaded[id] = m
                status = "ready"
                Log.i(TAG, "vosk model loaded: $id")
                return m
            } catch (t: Throwable) {
                Log.w(TAG, "model load failed: $id", t)
                failed.add(id)
                status = "load: ${t::class.simpleName}: ${t.message}"
                return null
            }
        }
    }

    /** Forget a failed load so the next [ensure] retries from scratch. */
    fun retry(id: String) {
        synchronized(this) {
            failed.remove(id)
            status = "init"
        }
    }

    private fun install(ctx: Context, spec: ModelSpec, dest: File): Boolean {
        if (installing) {
            status = "busy"
            return false
        }
        installing = true
        installingId = spec.id
        try {
            // The bundled zip wins: it needs no network and is exactly the
            // out-of-the-box model the settings promise.
            if (spec.assetPath != null && installFromAsset(ctx, spec, dest)) return true
            return downloadAndUnzip(ctx, spec, dest)
        } finally {
            installing = false
            installingId = null
            progress = -1
        }
    }

    private fun installFromAsset(ctx: Context, spec: ModelSpec, dest: File): Boolean {
        val input: InputStream = try {
            ctx.assets.open(spec.assetPath!!)
        } catch (t: Throwable) {
            Log.w(TAG, "bundled model not in assets: ${spec.assetPath}", t)
            return false
        }
        val tmp = File(ctx.cacheDir, "vosk-${spec.id}.zip")
        return try {
            status = "installing from the app bundle"
            progress = -1
            input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
            val ok = unzipInto(ctx, tmp, dest)
            if (!ok) dest.deleteRecursively()
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "asset install failed: ${spec.id}", t)
            dest.deleteRecursively()
            false
        } finally {
            tmp.delete()
        }
    }

    private fun downloadAndUnzip(ctx: Context, spec: ModelSpec, dest: File): Boolean {
        val tmp = File(ctx.cacheDir, "vosk-${spec.id}.zip")
        try {
            var lastErr = "no url"
            for (url in spec.urls) {
                try {
                    Log.i(TAG, "downloading vosk model from $url")
                    status = "downloading"
                    progress = 0
                    var okSize = false
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 180_000
                    conn.setRequestProperty("User-Agent", "Pots/1")
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            lastErr = "http $code"
                            continue
                        }
                        val expected = conn.contentLengthLong
                        var got = 0L
                        val buf = ByteArray(1 shl 16)
                        val ins = conn.inputStream
                        tmp.outputStream().use { out ->
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                if (expected > 0) {
                                    progress = ((got * 100) / expected).toInt().coerceIn(0, 100)
                                }
                            }
                        }
                        okSize = expected <= 0 || tmp.length() == expected
                        if (!okSize) lastErr = "short ${tmp.length()}/$expected"
                    } finally {
                        conn.disconnect()
                    }
                    if (!okSize) continue
                    progress = 100
                    val un = unzipInto(ctx, tmp, dest)
                    if (un) return true
                    lastErr = "unzip"
                } catch (t: Throwable) {
                    dest.deleteRecursively()
                    lastErr = "dl ${t::class.simpleName}: ${t.message}"
                    Log.w(TAG, "download failed from $url", t)
                }
            }
            status = lastErr
            return false
        } finally {
            tmp.delete()
        }
    }

    private fun unzipInto(ctx: Context, tmp: File, dest: File): Boolean {
        val root = ctx.filesDir.canonicalFile
        ZipInputStream(tmp.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(ctx.filesDir, e.name).canonicalFile
                if (!f.path.startsWith(root.path)) {
                    status = "zip slip"
                    return false
                }
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        val ok = filesPresent(dest)
        status = if (ok) "installed" else "unpacked but incomplete"
        Log.i(TAG, "vosk unpack done ok=$ok (${dest.path})")
        return ok
    }
}
