package com.pusios.pots.speech.sherpa

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The one owner of the neural (sherpa-onnx) speech models on this device.
 *
 * The models are **not** in the APK: each is two files downloaded on first use
 * from the Hugging Face mirror of the upstream sherpa-onnx release, at a pinned
 * revision, and checked against a pinned SHA-256 before the model is marked
 * installed. The runtime that reads them (`libsherpa-onnx-jni.so`) already
 * ships with the app for Piper, so the engine itself adds no native code.
 *
 * One download runs at a time; [progress]/[status] describe it for the settings
 * card. A partially downloaded file is kept under `.part` and resumed with an
 * HTTP `Range` request, so a dropped connection costs the tail, not the whole
 * 214 MB.
 */
internal object SherpaSttModels {
    private const val TAG = "SherpaSttModels"

    /** One file of a model: where it comes from and what it must hash to. */
    data class ModelFile(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    /** One downloadable model. Labels live in the Compose string resources. */
    data class ModelSpec(
        val id: String,
        /** Approximate installed size, for the settings UI. */
        val sizeMb: Int,
        /** True when the model also emits punctuation and capitalisation. */
        val punctuated: Boolean,
        val files: List<ModelFile>,
    )

    private const val MODEL_FILE = "model.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"

    /** Written last: its presence is what «installed» means. */
    private const val MARKER_FILE = ".verified"

    private const val SAMPLE_RATE_HZ = 16_000
    private const val FEATURE_DIM = 80

    /** Two threads: four measured slower than two on the same ARM core cluster. */
    private const val THREADS = 2

    /** The GigaAM v3 CTC exports published by the sherpa-onnx project. */
    private val GIGAAM_PUNCT = "https://huggingface.co/csukuangfj/" +
        "sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16/resolve/" +
        "4fb5407ff028a69fec516cdf4c10fac9ddea7c16"
    private val GIGAAM_PLAIN = "https://huggingface.co/csukuangfj/" +
        "sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/resolve/" +
        "32a4c7cc81809bd132e2d935ab99e9e6ab47fbec"

    val MODELS: List<ModelSpec> = listOf(
        ModelSpec(
            id = "gigaam-v3-ctc-punct-int8",
            sizeMb = 214,
            punctuated = true,
            files = listOf(
                ModelFile(
                    name = MODEL_FILE,
                    url = "$GIGAAM_PUNCT/$MODEL_FILE",
                    sizeBytes = 224_893_661,
                    sha256 = "d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35",
                ),
                ModelFile(
                    name = TOKENS_FILE,
                    url = "$GIGAAM_PUNCT/$TOKENS_FILE",
                    sizeBytes = 2_007,
                    sha256 = "142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd",
                ),
            ),
        ),
        ModelSpec(
            id = "gigaam-v3-ctc-int8",
            sizeMb = 214,
            punctuated = false,
            files = listOf(
                ModelFile(
                    name = MODEL_FILE,
                    url = "$GIGAAM_PLAIN/$MODEL_FILE",
                    sizeBytes = 224_721_476,
                    sha256 = "f86ebfa0429ced91be6054fc344827e9c6c2572f3c318416cd974b06f66437ec",
                ),
                ModelFile(
                    name = TOKENS_FILE,
                    url = "$GIGAAM_PLAIN/$TOKENS_FILE",
                    sizeBytes = 196,
                    sha256 = "17cc514451bcceac9c280068c71502f8448f99e9fb1456b8d0761651fd0392f2",
                ),
            ),
        ),
    )

    fun model(id: String): ModelSpec = MODELS.firstOrNull { it.id == id } ?: MODELS[0]

    private val lock = Any()
    private val loaded = HashMap<String, OfflineRecognizer>()

    @Volatile
    private var cancelRequested = false

    /** Download progress 0–100 (-1 = idle or indeterminate). */
    @Volatile
    var progress: Int = -1
        private set

    /** The model a download is running for, or null when idle. */
    @Volatile
    var installingId: String? = null
        private set

    /** Human-readable state for diagnostics and the settings card. */
    @Volatile
    var status: String = "init"
        private set

    /** True while a download is in flight. */
    fun isDownloading(): Boolean = installingId != null

    private fun dir(ctx: Context, id: String) = File(ctx.filesDir, "stt/${model(id).id}")

    /**
     * True when every file of [id] is on disk at its pinned size and the model
     * was verified after the download. Cheap enough to call from composition.
     */
    fun isInstalled(ctx: Context, id: String): Boolean {
        val spec = model(id)
        val d = dir(ctx, id)
        if (!File(d, MARKER_FILE).isFile) return false
        return spec.files.all { File(d, it.name).let { f -> f.isFile && f.length() == it.sizeBytes } }
    }

    /** Total bytes of an installed model on disk (0 = not installed). */
    fun installedSize(ctx: Context, id: String): Long {
        val d = dir(ctx, id)
        if (!d.isDirectory) return 0
        var s = 0L
        d.walkTopDown().forEach { if (it.isFile) s += it.length() }
        return s
    }

    /** Stops the running download and forgets its partial file. */
    fun cancel() {
        cancelRequested = true
    }

    /**
     * Downloads and verifies [id]. Blocking — run it off the main thread.
     *
     * Returns true when the model is installed and ready to load. Cancelling
     * leaves nothing behind: the partial file is removed so the next attempt
     * starts from a known state.
     */
    fun download(ctx: Context, id: String): Boolean {
        val spec = model(id)
        synchronized(lock) {
            if (installingId != null) return false
            installingId = spec.id
            cancelRequested = false
        }
        try {
            val d = dir(ctx, spec.id)
            d.mkdirs()
            File(d, MARKER_FILE).delete()
            val totalBytes = spec.files.sumOf { it.sizeBytes }
            var doneBefore = 0L
            for (file in spec.files) {
                val dest = File(d, file.name)
                if (dest.isFile && dest.length() == file.sizeBytes && sha256Of(dest) == file.sha256) {
                    doneBefore += file.sizeBytes
                    progress = ((doneBefore * 100) / totalBytes).toInt()
                    continue
                }
                if (!fetch(file, dest, doneBefore, totalBytes)) {
                    File(d, "${file.name}.part").delete()
                    if (cancelRequested) {
                        status = "cancelled"
                        d.deleteRecursively()
                    } else {
                        status = "download failed"
                    }
                    return false
                }
                doneBefore += file.sizeBytes
            }
            File(d, MARKER_FILE).writeText(spec.files.joinToString("\n") { "${it.sha256}  ${it.name}" })
            progress = 100
            status = "installed"
            Log.i(TAG, "neural model installed: ${spec.id}")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "download failed: ${spec.id}", t)
            status = "download: ${t::class.simpleName}: ${t.message}"
            return false
        } finally {
            installingId = null
        }
    }

    /** Removes a model from disk and releases the loaded recognizer. */
    fun delete(ctx: Context, id: String) {
        synchronized(lock) {
            loaded.remove(model(id).id)?.release()
            dir(ctx, id).deleteRecursively()
            status = "removed"
            progress = -1
        }
    }

    /**
     * The loaded recognizer for [id], or null when the model is not installed
     * or could not be loaded. Blocking on the first call — run it off the main
     * thread.
     */
    fun recognizer(ctx: Context, id: String): OfflineRecognizer? = synchronized(lock) {
        val spec = model(id)
        loaded[spec.id]?.let { return it }
        if (!isInstalled(ctx, spec.id)) {
            status = "not installed"
            return null
        }
        val d = dir(ctx, spec.id)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = FEATURE_DIM),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = File(d, MODEL_FILE).absolutePath),
                tokens = File(d, TOKENS_FILE).absolutePath,
                numThreads = THREADS,
                modelType = "nemo_ctc",
                debug = false,
            ),
        )
        val created = runCatching { OfflineRecognizer(ctx.assets, config) }
            .onFailure { Log.w(TAG, "load failed: ${spec.id}", it) }
            .getOrNull()
        if (created != null) {
            loaded[spec.id] = created
            status = "ready"
        } else {
            status = "load failed"
        }
        created
    }

    /**
     * Transcribes one utterance of 16 kHz mono PCM.
     *
     * Blocking: the model is offline and returns the whole phrase at once, so
     * callers run this on the recognition thread, never on the main one.
     */
    fun transcribe(recognizer: OfflineRecognizer, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** One file: resume from `.part` when the server allows it, verify, then move into place. */
    private fun fetch(file: ModelFile, dest: File, doneBefore: Long, totalBytes: Long): Boolean {
        val part = File(dest.parentFile, "${dest.name}.part")
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            if (cancelRequested) return false
            var resumeFrom = if (part.isFile) part.length() else 0L
            if (resumeFrom > file.sizeBytes) {
                part.delete()
                resumeFrom = 0L
            }
            if (resumeFrom == file.sizeBytes && sha256Of(part) == file.sha256) {
                if (!part.renameTo(dest)) return false
                return true
            }
            try {
                val conn = URL(file.url).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Pots/1")
                if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        status = "http $code"
                        continue
                    }
                    // A server that ignored Range answers 200 with the whole
                    // file: start the part over rather than appending to it.
                    val resuming = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
                    if (!resuming) resumeFrom = 0L
                    var got = resumeFrom
                    conn.inputStream.use { ins ->
                        FileOutputStream(part, resuming).use { out ->
                            val buf = ByteArray(BUFFER_BYTES)
                            while (true) {
                                if (cancelRequested) return false
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                progress = (((doneBefore + got) * 100) / totalBytes).toInt().coerceIn(0, 100)
                            }
                        }
                    }
                    if (got != file.sizeBytes) {
                        status = "short ${part.length()}/${file.sizeBytes}"
                        part.delete()
                        continue
                    }
                    if (sha256Of(part) != file.sha256) {
                        status = "checksum mismatch: ${file.name}"
                        part.delete()
                        continue
                    }
                    if (!part.renameTo(dest)) return false
                    return true
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                if (cancelRequested) return false
                status = "dl ${t::class.simpleName}: ${t.message}"
                Log.w(TAG, "download attempt $attempt failed for ${file.name}", t)
            }
        }
        return false
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val bytes = digest.digest()
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) hex.append('0')
            hex.append(v.toString(16))
        }
        return hex.toString()
    }

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER_BYTES = 64 * 1024

    /** A dropped connection is retried from the byte it stopped at. */
    private const val MAX_ATTEMPTS = 3
}
