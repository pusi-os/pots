package com.pusios.pots.inference

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.VectorizationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

private const val EMBEDDING_MODEL_ID = "embeddinggemma-2-text-vision-440m"
private const val STORE_FILE = "pots_vector_store.json"

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

    private val _status = MutableStateFlow("")
    override val status: StateFlow<String> = _status.asStateFlow()

    private val _autoPhotos = MutableStateFlow(appSettings.isAutoVectorizePhotos())
    override val autoIndexPhotos: StateFlow<Boolean> = _autoPhotos.asStateFlow()

    private val _autoDocs = MutableStateFlow(appSettings.isAutoVectorizeDocuments())
    override val autoIndexDocuments: StateFlow<Boolean> = _autoDocs.asStateFlow()

    private val embeddingEngine = MutableStateFlow<EmbeddingEngine?>(null)
    private val entries = mutableListOf<VectorEntry>()

    init {
        loadStore()
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

    private fun refreshModelReady() {
        val downloaded = engine.getDownloadedModels().any { it.id == EMBEDDING_MODEL_ID }
        _modelReady.value = downloaded
    }

    private suspend fun ensureEngine(): EmbeddingEngine? {
        refreshModelReady()
        if (!_modelReady.value) return null
        embeddingEngine.value?.let { return it }
        val downloaded = engine.getDownloadedModels().firstOrNull { it.id == EMBEDDING_MODEL_ID } ?: return null
        return withContext(Dispatchers.IO) {
            val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = downloaded.filePath, backend = Backend.CPU()))
            e.initialize()
            embeddingEngine.value = e
            e
        }
    }

    override suspend fun indexPhotos() {
        val e = ensureEngine() ?: return
        _indexing.value = true
        _progress.value = 0f
        _status.value = "Indexing photos"
        entries.removeAll { it.source == "photo" }
        val photos = queryPhotoRecords()
        photos.forEachIndexed { idx, rec ->
            val bytes = readBytes(Uri.parse(rec.uri))
            val vec = if (bytes != null) embed(e, InputData.Image(bytes)) else null
            val finalVec = vec ?: embed(e, listOfNotNull(rec.name, rec.bucket).joinToString(" "))
            if (finalVec == null) return@forEachIndexed
            val text = listOfNotNull(rec.name, rec.bucket).joinToString(" ")
            entries += VectorEntry(
                id = "photo:${rec.id}",
                source = "photo",
                title = rec.name,
                text = text,
                uri = rec.uri,
                vector = finalVec,
            )
            _progress.value = (idx + 1).toFloat() / photos.size.coerceAtLeast(1)
        }
        saveStore()
        _indexing.value = false
        _progress.value = null
        _status.value = "Photos indexed: ${entries.count { it.source == "photo" }}"
    }

    override suspend fun indexDocuments() {
        val e = ensureEngine() ?: return
        _indexing.value = true
        _progress.value = 0f
        _status.value = "Indexing documents"
        entries.removeAll { it.source == "document" }
        val docs = queryDocumentRecords()
        docs.forEachIndexed { idx, rec ->
            val text = rec.text ?: return@forEachIndexed
            val chunks = chunkText(text)
            chunks.forEach { chunk ->
                val vec = embed(e, chunk) ?: return@forEach
                entries += VectorEntry(
                    id = "doc:${rec.uri}:$chunk",
                    source = "document",
                    title = rec.name,
                    text = chunk,
                    uri = rec.uri,
                    vector = vec,
                )
            }
            _progress.value = (idx + 1).toFloat() / docs.size.coerceAtLeast(1)
        }
        saveStore()
        _indexing.value = false
        _progress.value = null
        _status.value = "Documents indexed: ${entries.count { it.source == "document" }}"
    }

    override suspend fun search(query: String, source: String?, limit: Int): List<Map<String, Any?>> {
        val e = ensureEngine() ?: return emptyList()
        val qv = embed(e, query) ?: return emptyList()
        return entries
            .filter { source == null || it.source == source }
            .map { it to cosine(qv, it.vector) }
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
    }

    private suspend fun embed(engine: EmbeddingEngine, input: InputData): FloatArray? = withContext(Dispatchers.IO) {
        runCatching { engine.computeEmbedding(listOf(input)).embedding }.getOrNull()
    }

    private suspend fun embed(engine: EmbeddingEngine, text: String): FloatArray? = withContext(Dispatchers.IO) {
        runCatching {
            engine.computeEmbedding(listOf(InputData.Text(text))).embedding
        }.getOrNull()
    }

    private fun loadStore() {
        val file = File(context.filesDir, STORE_FILE)
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("entries")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val vecArr = o.getJSONArray("vector")
                val vec = FloatArray(vecArr.length()) { vecArr.getDouble(it).toFloat() }
                entries += VectorEntry(
                    id = o.getString("id"),
                    source = o.getString("source"),
                    title = o.optString("title"),
                    text = o.optString("text"),
                    uri = o.optString("uri").ifBlank { null },
                    vector = vec,
                )
            }
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
            val v = JSONArray()
            e.vector.forEach { v.put(it.toDouble()) }
            o.put("vector", v)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("entries", arr)
        File(context.filesDir, STORE_FILE).writeText(root.toString())
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
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

    private data class PhotoRecord(val id: Long, val name: String, val bucket: String?, val uri: String)

    private fun queryPhotoRecords(): List<PhotoRecord> {
        val result = mutableListOf<PhotoRecord>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
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
            while (cursor.moveToNext() && result.size < 500) {
                val id = cursor.getLong(idIdx)
                result += PhotoRecord(
                    id = id,
                    name = cursor.getString(nameIdx) ?: "photo_$id",
                    bucket = cursor.getString(bucketIdx),
                    uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
                )
            }
        }
        return result
    }

    private data class DocumentRecord(val name: String, val uri: String, val text: String?)

    private fun queryDocumentRecords(): List<DocumentRecord> {
        val result = mutableListOf<DocumentRecord>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            arrayOf("text/%"),
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            while (cursor.moveToNext() && result.size < 200) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: continue
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                val text = readText(uri)
                result += DocumentRecord(name = name, uri = uri.toString(), text = text)
            }
        }
        return result
    }

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
)

actual fun createVectorizationManager(
    localInferenceEngine: LocalInferenceEngine?,
    appSettings: AppSettings,
): VectorizationManager {
    val context: Context = org.koin.java.KoinJavaComponent.get(Context::class.java)
    if (localInferenceEngine == null) return com.pusios.pots.data.NoOpVectorizationManager()
    return AndroidVectorizationManager(context, appSettings, localInferenceEngine)
}
