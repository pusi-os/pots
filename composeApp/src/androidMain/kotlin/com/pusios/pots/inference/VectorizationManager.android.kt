package com.pusios.pots.inference

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.VectorizationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.sqrt

private const val EMBEDDING_MODEL_ID = "embeddinggemma-2-text-vision-440m"
private const val STORE_FILE = "pots_vector_store.json"
private const val SCHEMA_VERSION = 2
private const val PROMPT_VERSION = 2
private const val OUTPUT_DIM = 768
private const val VISION_TOKENS_PER_IMAGE = 140
private val ZERO_SHOT_LABELS = listOf("кот", "собака", "человек", "еда", "машина", "документ", "скриншот", "пейзаж", "животное", "текст", "природа", "город")

@Suppress("ktlint:standard:backing-property-naming")
class AndroidVectorizationManager(
    private val context: Context,
    private val appSettings: AppSettings,
    private val engine: LocalInferenceEngine,
) : VectorizationManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _modelReady = MutableStateFlow(false)
    override val isModelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    private val _indexing = MutableStateFlow(false)
    override val isIndexing: StateFlow<Boolean> = _indexing.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    override val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _indexedPhotoCount = MutableStateFlow(0)
    override val indexedPhotoCount: StateFlow<Int> = _indexedPhotoCount.asStateFlow()

    private val _indexedDocumentCount = MutableStateFlow(0)
    override val indexedDocumentCount: StateFlow<Int> = _indexedDocumentCount.asStateFlow()

    private val _currentTotalCount = MutableStateFlow(0)
    override val currentTotalCount: StateFlow<Int> = _currentTotalCount.asStateFlow()

    private val _currentProcessedCount = MutableStateFlow(0)
    override val currentProcessedCount: StateFlow<Int> = _currentProcessedCount.asStateFlow()

    private val _status = MutableStateFlow("")
    override val status: StateFlow<String> = _status.asStateFlow()

    private val _needsPhotoPermission = MutableStateFlow(false)
    override val needsPhotoPermission: StateFlow<Boolean> = _needsPhotoPermission.asStateFlow()

    private val _needsDocumentPermission = MutableStateFlow(false)
    override val needsDocumentPermission: StateFlow<Boolean> = _needsDocumentPermission.asStateFlow()

    override val requiredPhotoPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
    override val requiredDocumentPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "" else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private val _autoPhotos = MutableStateFlow(appSettings.isAutoVectorizePhotos())
    override val autoIndexPhotos: StateFlow<Boolean> = _autoPhotos.asStateFlow()

    private val _autoDocs = MutableStateFlow(appSettings.isAutoVectorizeDocuments())
    override val autoIndexDocuments: StateFlow<Boolean> = _autoDocs.asStateFlow()

    private val _indexStale = MutableStateFlow(false)
    override val indexStale: StateFlow<Boolean> = _indexStale.asStateFlow()

    private val _indexedAt = MutableStateFlow<Long?>(null)
    override val indexedAt: StateFlow<Long?> = _indexedAt.asStateFlow()

    private val _skippedPhotoCount = MutableStateFlow(0)
    override val skippedPhotoCount: StateFlow<Int> = _skippedPhotoCount.asStateFlow()

    private val _firstIndexError = MutableStateFlow<String?>(null)
    override val firstIndexError: StateFlow<String?> = _firstIndexError.asStateFlow()

    private val embeddingEngine = MutableStateFlow<EmbeddingEngine?>(null)
    private val entries = mutableListOf<VectorEntry>()
    private var cancelRequested = false

    init {
        loadStore()
        _indexedPhotoCount.value = entries.count { it.source == "photo" }
        _indexedDocumentCount.value = entries.count { it.source == "document" }
        _needsPhotoPermission.value = !hasPhotoPermission()
        _needsDocumentPermission.value = requiredDocumentPermission.isNotBlank() && !hasDocumentPermission()
        registerPhotoObserver()
        refreshModelReady()
        scope.launch {
            engine.downloadingModelId.collect { id ->
                if (id == null) refreshModelReady()
            }
        }
        if (_autoPhotos.value || _autoDocs.value) {
            scope.launch { runCatching { if (_autoPhotos.value) indexPhotos() } }
            scope.launch { runCatching { if (_autoDocs.value) indexDocuments() } }
        }
    }

    override fun setAutoIndexPhotos(enabled: Boolean) {
        appSettings.setAutoVectorizePhotos(enabled)
        _autoPhotos.value = enabled
    }

    override fun setAutoIndexDocuments(enabled: Boolean) {
        appSettings.setAutoVectorizeDocuments(enabled)
        _autoDocs.value = enabled
    }

    override fun downloadModel() {
        val model = MODEL_CATALOG.firstOrNull { it.id == EMBEDDING_MODEL_ID } ?: return
        engine.startDownload(model)
    }

    override fun cancelDownload() {
        engine.cancelDownload()
    }

    private fun registerPhotoObserver() {
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        if (_autoPhotos.value && !_indexing.value) {
                            scope.launch { runCatching { indexPhotos() } }
                        }
                    }
                },
            )
        }
    }

    private fun refreshModelReady() {
        val downloaded = engine.getDownloadedModels().any { it.id == EMBEDDING_MODEL_ID }
        _modelReady.value = downloaded
    }

    private fun hasPhotoPermission(): Boolean = ContextCompat.checkSelfPermission(context, requiredPhotoPermission) == PackageManager.PERMISSION_GRANTED

    private fun hasDocumentPermission(): Boolean = requiredDocumentPermission.isBlank() || ContextCompat.checkSelfPermission(context, requiredDocumentPermission) == PackageManager.PERMISSION_GRANTED

    private suspend fun ensureEngine(): EmbeddingEngine? {
        refreshModelReady()
        if (!_modelReady.value) return null
        embeddingEngine.value?.let { return it }
        val downloaded = engine.getDownloadedModels().firstOrNull { it.id == EMBEDDING_MODEL_ID } ?: return null
        return withContext(Dispatchers.IO) {
            val e = EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = downloaded.filePath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    audioBackend = Backend.CPU(),
                    cacheDir = context.cacheDir.path,
                    visionTokensPerImage = VISION_TOKENS_PER_IMAGE,
                    activationDataType = com.google.ai.edge.litertlm.ActivationDataType.FLOAT32,
                ),
            )
            e.initialize()
            embeddingEngine.value = e
            e
        }
    }

    private fun releaseEngine() {
        val e = embeddingEngine.value
        embeddingEngine.value = null
        runCatching { e?.close() }
    }

    override fun cancelIndexing() {
        cancelRequested = true
    }

    override suspend fun indexPhotos() {
        if (_indexing.value) return
        if (!hasPhotoPermission()) {
            _needsPhotoPermission.value = true
            _status.value = "Нужно разрешение на доступ к фото"
            return
        }
        _needsPhotoPermission.value = false
        val e = ensureEngine() ?: run {
            _status.value = "Сначала скачайте модель эмбеддингов"
            return
        }
        cancelRequested = false
        _indexing.value = true
        _progress.value = 0f
        _currentProcessedCount.value = 0
        _skippedPhotoCount.value = 0
        _firstIndexError.value = null
        _status.value = "Подготовка индексации фото…"

        val photos = queryPhotoRecords()
        val known = entries.filter { it.source == "photo" }.associate { it.id to it.mtime }
        val toDelete = known.keys - photos.map { "photo:${it.id}" }.toSet()
        entries.removeAll { it.source == "photo" && it.id in toDelete }
        val toIndex = photos.filter { known["photo:${it.id}"] != it.mtime }

        _currentTotalCount.value = toIndex.size
        var indexed = 0
        var processed = 0
        var skipped = 0
        try {
            for (rec in toIndex) {
                if (cancelRequested || !scope.isActive) break
                val bytes = readBytes(Uri.parse(rec.uri))?.let { downscaleImage(it) }
                val baseText = listOfNotNull(rec.name, rec.bucket).joinToString(" ")
                if (bytes == null) {
                    skipped++
                    if (_firstIndexError.value == null) _firstIndexError.value = "не удалось прочитать изображение"
                    processed++
                    _currentProcessedCount.value = processed
                    _progress.value = if (toIndex.size > 0) processed.toFloat() / toIndex.size else 1f
                    continue
                }
                val vec = embedImage(e, bytes)
                if (vec == null) {
                    skipped++
                    processed++
                    _currentProcessedCount.value = processed
                    _progress.value = if (toIndex.size > 0) processed.toFloat() / toIndex.size else 1f
                    continue
                }
                val labels = zeroShotLabels(e, vec)
                val text = (labels + listOfNotNull(rec.bucket)).joinToString(" ").ifBlank { baseText }
                entries += VectorEntry(
                    id = "photo:${rec.id}",
                    source = "photo",
                    title = rec.name,
                    text = text,
                    uri = rec.uri,
                    vector = vec,
                    mtime = rec.mtime,
                )
                indexed++
                processed++
                _currentProcessedCount.value = processed
                _progress.value = if (toIndex.size > 0) processed.toFloat() / toIndex.size else 1f
                _status.value = "Индексация фото: $processed/${toIndex.size}"
            }
            _skippedPhotoCount.value = skipped
            saveStore()
            _indexedPhotoCount.value = entries.count { it.source == "photo" }
            _status.value = "Проиндексировано фото: ${_indexedPhotoCount.value}" + if (skipped > 0) " (пропущено: $skipped)" else ""
        } catch (t: Throwable) {
            _status.value = "Ошибка индексации фото: ${t.message ?: t::class.simpleName}"
        } finally {
            _indexing.value = false
            _progress.value = null
            _currentTotalCount.value = 0
            _currentProcessedCount.value = 0
            releaseEngine()
        }
    }

    override suspend fun indexDocuments() {
        if (!hasDocumentPermission()) {
            _needsDocumentPermission.value = true
            _status.value = "Нужно разрешение на доступ к документам"
            return
        }
        _needsDocumentPermission.value = false
        val e = ensureEngine() ?: run {
            _status.value = "Сначала скачайте модель эмбеддингов"
            return
        }
        cancelRequested = false
        _indexing.value = true
        _progress.value = 0f
        _currentProcessedCount.value = 0
        _status.value = "Подготовка индексации документов…"
        entries.removeAll { it.source == "document" }
        val docs = queryDocumentRecords()
        _currentTotalCount.value = docs.size
        var indexedChunks = 0
        var processed = 0
        try {
            for (rec in docs) {
                if (cancelRequested || !scope.isActive) break
                when {
                    rec.mime == "application/pdf" -> {
                        val pages = renderPdfPages(Uri.parse(rec.uri))
                        for ((idx, bytes) in pages.withIndex()) {
                            if (cancelRequested || !scope.isActive) break
                            val b = bytes?.let { downscaleImage(it) } ?: continue
                            val vec = embedImage(e, b)
                            if (vec != null) {
                                entries += VectorEntry(
                                    id = "doc:${rec.uri}:page${idx + 1}",
                                    source = "document",
                                    title = rec.name,
                                    text = "${rec.name} — страница ${idx + 1}",
                                    uri = rec.uri,
                                    vector = vec,
                                    mtime = 0L,
                                )
                                indexedChunks++
                            }
                        }
                    }

                    rec.mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                        val text = readDocxText(Uri.parse(rec.uri))
                        if (!text.isNullOrBlank()) {
                            val chunks = chunkText(text)
                            for (chunk in chunks) {
                                if (cancelRequested || !scope.isActive) break
                                val vec = embed(e, "title: ${rec.name} | text: $chunk")
                                if (vec != null) {
                                    entries += VectorEntry(
                                        id = "doc:${rec.uri}:$chunk",
                                        source = "document",
                                        title = rec.name,
                                        text = chunk,
                                        uri = rec.uri,
                                        vector = vec,
                                        mtime = 0L,
                                    )
                                    indexedChunks++
                                }
                            }
                        }
                    }

                    else -> {
                        val text = rec.text
                        if (!text.isNullOrBlank()) {
                            val chunks = chunkText(text)
                            for (chunk in chunks) {
                                if (cancelRequested || !scope.isActive) break
                                val vec = embed(e, "title: ${rec.name} | text: $chunk")
                                if (vec != null) {
                                    entries += VectorEntry(
                                        id = "doc:${rec.uri}:$chunk",
                                        source = "document",
                                        title = rec.name,
                                        text = chunk,
                                        uri = rec.uri,
                                        vector = vec,
                                        mtime = 0L,
                                    )
                                    indexedChunks++
                                }
                            }
                        }
                    }
                }
                processed++
                _currentProcessedCount.value = processed
                _progress.value = processed.toFloat() / docs.size.coerceAtLeast(1)
                _status.value = "Индексация документов: $processed/${docs.size}"
            }
            saveStore()
            _indexedDocumentCount.value = entries.count { it.source == "document" }
            _status.value = "Проиндексировано фрагментов документов: ${_indexedDocumentCount.value}"
        } catch (t: Throwable) {
            _status.value = "Ошибка индексации документов: ${t.message ?: t::class.simpleName}"
        } finally {
            _indexing.value = false
            _progress.value = null
            _currentTotalCount.value = 0
            _currentProcessedCount.value = 0
            releaseEngine()
        }
    }

    override suspend fun search(query: String, source: String?, limit: Int, minScore: Double): List<Map<String, Any?>> {
        if (_indexStale.value) return emptyList()
        val e = ensureEngine() ?: return emptyList()
        val qv = embed(e, "task: search result | query: $query") ?: return emptyList()
        val filtered = entries
            .filter { source == null || it.source == source }
            .map { it to cosine(qv, it.vector) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (entry, score) ->
                mapOf(
                    "source" to entry.source,
                    "title" to entry.title,
                    "text" to entry.text,
                    "uri" to entry.uri,
                    "score" to score,
                )
            }
        releaseEngine()
        return filtered
    }

    private suspend fun zeroShotLabels(engine: EmbeddingEngine, vec: FloatArray): List<String> = withContext(Dispatchers.IO) {
        val labelVecs = ZERO_SHOT_LABELS.associateWith { label ->
            runCatching {
                engine.computeEmbedding(listOf(InputData.Text("title: none | text: $label")), EmbeddingOptions(normalize = true, outputSize = OUTPUT_DIM)).embedding
            }.getOrNull()
        }
        labelVecs.mapNotNull { (label, lv) ->
            if (lv == null) null else label to cosine(vec, lv)
        }.filter { it.second >= 0.05 }.sortedByDescending { it.second }.take(3).map { it.first }
    }

    private suspend fun embedImage(engine: EmbeddingEngine, bytes: ByteArray): FloatArray? = withContext(Dispatchers.IO) {
        runCatching {
            engine.computeEmbedding(
                listOf(InputData.Image(bytes)),
                EmbeddingOptions(normalize = true, outputSize = OUTPUT_DIM),
            ).embedding
        }.onFailure { t ->
            if (_firstIndexError.value == null) _firstIndexError.value = t.message ?: t::class.simpleName
        }.getOrNull()
    }

    private suspend fun embed(engine: EmbeddingEngine, input: InputData): FloatArray? = withContext(Dispatchers.IO) {
        runCatching {
            engine.computeEmbedding(listOf(input), EmbeddingOptions(normalize = true, outputSize = OUTPUT_DIM)).embedding
        }.getOrNull()
    }

    private suspend fun embed(engine: EmbeddingEngine, text: String): FloatArray? = withContext(Dispatchers.IO) {
        runCatching {
            engine.computeEmbedding(listOf(InputData.Text(text)), EmbeddingOptions(normalize = true, outputSize = OUTPUT_DIM)).embedding
        }.getOrNull()
    }

    private fun loadStore() {
        val file = File(context.filesDir, STORE_FILE)
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            val schema = json.optInt("schema", -1)
            val modelId = json.optString("modelId", "")
            val dim = json.optInt("dim", -1)
            val promptVersion = json.optInt("promptVersion", -1)
            val vtpi = json.optInt("visionTokensPerImage", -1)
            val indexedAt = if (json.has("indexedAt")) json.optLong("indexedAt") else null
            val compatible = schema == SCHEMA_VERSION &&
                modelId == EMBEDDING_MODEL_ID &&
                dim == OUTPUT_DIM &&
                promptVersion == PROMPT_VERSION &&
                vtpi == VISION_TOKENS_PER_IMAGE
            if (!compatible) {
                file.delete()
                _indexStale.value = true
                _indexedAt.value = null
                entries.clear()
                return@runCatching
            }
            val arr = json.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val vecArr = o.getJSONArray("vector")
                val vec = FloatArray(vecArr.length()) { vecArr.getDouble(it).toFloat() }
                val normalized = l2normalize(vec)
                entries += VectorEntry(
                    id = o.getString("id"),
                    source = o.getString("source"),
                    title = o.optString("title"),
                    text = o.optString("text"),
                    uri = o.optString("uri").ifBlank { null },
                    vector = normalized,
                    mtime = o.optLong("mtime", 0L),
                )
            }
            _indexedAt.value = indexedAt
            _indexStale.value = false
        }
    }

    private fun saveStore() {
        val arr = JSONArray()
        entries.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id)
            o.put("source", e.source)
            o.put("title", e.title)
            o.put("text", e.text)
            o.put("uri", e.uri ?: "")
            o.put("mtime", e.mtime)
            val v = JSONArray()
            e.vector.forEach { v.put(it.toDouble()) }
            o.put("vector", v)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("schema", SCHEMA_VERSION)
        root.put("modelId", EMBEDDING_MODEL_ID)
        root.put("dim", OUTPUT_DIM)
        root.put("promptVersion", PROMPT_VERSION)
        root.put("visionTokensPerImage", VISION_TOKENS_PER_IMAGE)
        val now = System.currentTimeMillis()
        root.put("indexedAt", now)
        root.put("entries", arr)
        File(context.filesDir, STORE_FILE).writeText(root.toString())
        _indexedAt.value = now
        _indexStale.value = false
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "dim mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        if (n == 0.0) return v
        val s = sqrt(n).toFloat()
        return FloatArray(v.size) { v[it] / s }
    }

    private fun downscaleImage(bytes: ByteArray, target: Int = 1024): ByteArray? = runCatching {
        val exif = runCatching { ExifInterface(bytes.inputStream()) }.getOrNull()
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val rotated = applyExifOrientation(bitmap, orientation)
        val output = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 90, output)
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
        output.toByteArray()
    }.getOrNull()

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private fun chunkText(text: String, maxChars: Int = 900): List<String> {
        val paragraphs = text.split(Regex("\n\\s*\n"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (paragraph in paragraphs) {
            if (current.length + paragraph.length > maxChars && current.isNotEmpty()) {
                chunks += current.toString().trim()
                current.clear()
            }
            if (paragraph.length > maxChars) {
                if (current.isNotEmpty()) {
                    chunks += current.toString().trim()
                    current.clear()
                }
                paragraph.chunked(maxChars).forEach { chunks += it.trim() }
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            }
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks.filter { it.isNotBlank() }
    }

    private data class PhotoRecord(val id: Long, val name: String, val bucket: String?, val uri: String, val mtime: Long)

    private fun queryPhotoRecords(): List<PhotoRecord> {
        val result = mutableListOf<PhotoRecord>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val mtimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext() && result.size < 100000) {
                val id = cursor.getLong(idIdx)
                result += PhotoRecord(
                    id = id,
                    name = cursor.getString(nameIdx) ?: "photo_$id",
                    bucket = cursor.getString(bucketIdx),
                    uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    mtime = cursor.getLong(mtimeIdx),
                )
            }
        }
        return result
    }

    private data class DocumentRecord(val name: String, val uri: String, val mime: String?, val text: String?)

    private fun queryDocumentRecords(): List<DocumentRecord> {
        val result = mutableListOf<DocumentRecord>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            arrayOf("text/%", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            while (cursor.moveToNext() && result.size < 100000) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: continue
                val mime = cursor.getString(mimeIdx)
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                val text = if (mime?.startsWith("text/") == true) readText(uri) else null
                result += DocumentRecord(name = name, uri = uri.toString(), mime = mime, text = text)
            }
        }
        return result
    }

    private fun renderPdfPages(uri: Uri): List<ByteArray?> = runCatching {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        val renderer = PdfRenderer(pfd)
        val result = mutableListOf<ByteArray?>()
        val maxPages = minOf(renderer.pageCount, 20)
        for (i in 0 until maxPages) {
            val page = renderer.openPage(i)
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bitmap.recycle()
            page.close()
            result += out.toByteArray()
        }
        renderer.close()
        pfd.close()
        result
    }.getOrElse { emptyList() }

    private fun readDocxText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val zis = java.util.zip.ZipInputStream(input)
            val sb = StringBuilder()
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val bytes = zis.readBytes()
                    val xml = String(bytes, Charsets.UTF_8)
                    sb.append(xml.replace(Regex("<[^>]+>"), " "))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            sb.toString().replace(Regex("\\s+"), " ").trim().take(200_000)
        }
    }.getOrNull()

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText().take(200_000)
        }
    }.getOrNull()
}

private data class VectorEntry(
    val id: String,
    val source: String,
    val title: String,
    val text: String,
    val uri: String?,
    val vector: FloatArray,
    val mtime: Long = 0L,
)

actual fun createVectorizationManager(
    localInferenceEngine: LocalInferenceEngine?,
    appSettings: AppSettings,
): VectorizationManager {
    val context: Context = org.koin.java.KoinJavaComponent.get(Context::class.java)
    if (localInferenceEngine == null) return com.pusios.pots.data.NoOpVectorizationManager()
    return AndroidVectorizationManager(context, appSettings, localInferenceEngine)
}
