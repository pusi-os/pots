package com.pusios.pots.companion.cophone

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline speech recognition with Vosk, fed the cube's decoded PCM (ported from
 * the PixelTag CoPhone reference). The model is downloaded once into the app's
 * files dir and reused. Everything degrades gracefully — [transcribe] returns
 * null when the model is not ready or the native lib is missing, and the caller
 * falls back to the cloud transcription endpoint.
 *
 * One download runs at a time ([isDownloading]/[progress]/[status] describe it).
 */
internal object CoPhoneAsr {
    private const val TAG = "CoPhoneAsr"

    data class ModelSpec(
        val id: String,
        val label: String,
        val dir: String,
        val urls: List<String>,
    )

    val MODELS: List<ModelSpec> = listOf(
        ModelSpec(
            "ru",
            "Русский",
            "vosk-model-small-ru-0.22",
            listOf(
                "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
            ),
        ),
        ModelSpec(
            "en",
            "English",
            "vosk-model-small-en-us-0.15",
            listOf(
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            ),
        ),
    )

    fun model(id: String): ModelSpec = MODELS.firstOrNull { it.id == id } ?: MODELS[0]

    private val loaded = HashMap<String, Model>()
    private val failed = HashSet<String>()

    @Volatile
    private var downloading = false

    /** Download progress 0–100 (-1 = idle / not downloading). */
    @Volatile
    var progress: Int = -1

    /** Human-readable state for a diagnostic line. */
    @Volatile
    var status: String = "init"

    /** True once the given model is loaded (not merely present on disk). */
    fun ready(id: String): Boolean = loaded.containsKey(id)

    /** True while a download/unzip is in flight (UI progress). */
    fun isDownloading(): Boolean = downloading

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

    /** Load (downloading the model first if needed). Safe to call repeatedly. */
    fun ensure(ctx: Context, id: String): Model? {
        loaded[id]?.let { return it }
        if (failed.contains(id)) return null
        synchronized(this) {
            loaded[id]?.let { return it }
            try {
                val d = dir(ctx, id)
                if (!filesPresent(d)) {
                    d.deleteRecursively()
                    status = "downloading"
                    if (!downloadAndUnzip(ctx, d, id)) {
                        failed.add(id)
                        status = "download failed"
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

    /**
     * Transcribe 16 kHz mono PCM with the given model. Returns null when the
     * model is unavailable, the text is empty, or recognition failed.
     */
    fun transcribe(pcm16: ShortArray, id: String): String? {
        val m = loaded[id]
        if (m == null) {
            status = "no model ($status)"
            return null
        }
        if (pcm16.isEmpty()) return null
        var rec: Recognizer? = null
        return try {
            rec = Recognizer(m, 16000f)
            val bytes = CoPhoneOpus.pcmToBytes(pcm16)
            rec.acceptWaveForm(bytes, bytes.size)
            val json = JSONObject(rec.finalResult)
            val text = json.optString("text").trim()
            status = if (text.isBlank()) "empty" else "ok(${text.length})"
            text.ifBlank { null }
        } catch (t: Throwable) {
            Log.w(TAG, "recognize failed", t)
            status = "rec: ${t::class.simpleName}: ${t.message}"
            null
        } finally {
            try {
                rec?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun downloadAndUnzip(ctx: Context, dest: File, id: String): Boolean {
        if (downloading) {
            status = "dl busy"
            return false
        }
        downloading = true
        val tmp = File(ctx.cacheDir, "vosk-$id.zip")
        try {
            var lastErr = "no url"
            for (url in model(id).urls) {
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
            downloading = false
            progress = -1
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
        status = if (ok) "unpacked" else "unpacked but no conf"
        Log.i(TAG, "vosk unpack done ok=$ok")
        return ok
    }
}
