package com.pusios.pots.inference

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.Service
import com.pusios.pots.data.VectorizationManager
import com.pusios.pots.data.getBaseUrl
import com.pusios.pots.data.getSelectedModelId
import com.pusios.pots.httpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.math.sqrt

/**
 * Desktop vector storage backed by an Ollama embedding model instead of LiteRT.
 *
 * On machines without the LiteRT native runtime (no AVX2, non-x86) there is no
 * on-device embedding engine, but the user may already run Ollama with an
 * embedding model (e.g. `nomic-embed-text`, the desktop counterpart of the
 * mobile embeddinggemma). Vectors come from Ollama's `/api/embed` endpoint at
 * the OpenAI-Compatible base URL configured in Services; photos and documents
 * are read from `~/Pictures` and `~/Documents`.
 */
@Suppress("ktlint:standard:backing-property-naming")
class OllamaVectorizationManager(
    private val appSettings: AppSettings,
) : VectorizationManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        httpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private val _modelReady = MutableStateFlow(false)
    override val isModelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    private val _indexing = MutableStateFlow(false)
    override val isIndexing: StateFlow<Boolean> = _indexing.asStateFlow()

    private val _paused = MutableStateFlow(false)
    override val isPaused: StateFlow<Boolean> = _paused.asStateFlow()

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

    override val needsPhotoPermission: StateFlow<Boolean> = MutableStateFlow(false)
    override val needsDocumentPermission: StateFlow<Boolean> = MutableStateFlow(false)
    override val requiredPhotoPermission: String = ""
    override val requiredDocumentPermission: String = ""

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

    private val entries = mutableListOf<DesktopVectorEntry>()
    private var cancelRequested = false
    private var indexingSource: String? = null
    private var cachedDim: Int = 0

    init {
        loadStore()
        _indexedPhotoCount.value = entries.count { it.source == "photo" }
        _indexedDocumentCount.value = entries.count { it.source == "document" }
        scope.launch { runCatching { refreshModelReady() } }
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

    private var pullProcess: Process? = null

    override fun downloadModel() {
        // Pull the embedding model directly through Ollama: keeps the whole flow on
        // one button inside Tools instead of asking the user to run a shell script.
        scope.launch {
            val model = DEFAULT_EMBEDDING_MODEL
            _status.value = "Запуск `ollama pull $model`…"
            pullProcess = null
            runCatching {
                val process = ProcessBuilder("ollama", "pull", model)
                    .redirectErrorStream(true)
                    .start()
                pullProcess = process
                process.inputStream.bufferedReader().forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) _status.value = trimmed.takeLast(200)
                }
                val code = process.waitFor()
                if (code != 0) _status.value = "Ollama вернула код выхода $code"
            }.onFailure { t ->
                _status.value = "Не удалось запустить ollama: ${t.message ?: t::class.simpleName}"
            }
            pullProcess = null
            runCatching { refreshModelReady() }
            if (!_modelReady.value) {
                if (_status.value.isBlank()) _status.value = "Модель пока не готова — запустите Ollama"
            } else {
                _status.value = "Модель $model готова"
            }
        }
    }

    override fun cancelDownload() {
        pullProcess?.destroyForcibly()
        pullProcess = null
        _status.value = "Загрузка отменена"
    }

    private fun ollamaBase(): String {
        val configured = appSettings.getBaseUrl(Service.OpenAICompatible)
            .ifBlank { Service.DEFAULT_OPENAI_COMPATIBLE_BASE_URL }
        val trimmed = configured.trim().trimEnd('/')
        return if (trimmed.endsWith("/v1")) trimmed.dropLast(3).trimEnd('/') else trimmed
    }

    /** Embedding model id: the OpenAI-Compatible chat model when it looks like an embed model, else the default. */
    private fun embeddingModelId(): String {
        val chatModel = appSettings.getSelectedModelId(Service.OpenAICompatible)
        val looksEmbed = chatModel.contains("embed", ignoreCase = true) ||
            chatModel.contains("nomic", ignoreCase = true) ||
            chatModel.contains("mxbai", ignoreCase = true) ||
            chatModel.contains("gemma", ignoreCase = true) ||
            chatModel.contains("embeddinggemma", ignoreCase = true)
        return if (looksEmbed) chatModel else DEFAULT_EMBEDDING_MODEL
    }

    private suspend fun refreshModelReady() {
        _modelReady.value = probeModel() != null
    }

    private suspend fun probeModel(): String? {
        val base = ollamaBase()
        val model = embeddingModelId()
        val ok = runCatching {
            client.get("$base/api/tags").bodyAsText()
        }.map { body ->
            runCatching {
                val root = Json.parseToJsonElement(body).jsonObject
                val names = root["models"]?.jsonArray?.mapNotNull { entry ->
                    entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                }.orEmpty()
                val short = model.substringBefore(':')
                names.any { it == model || it == short || it.startsWith("$short:") || it.startsWith("$model:") }
            }.getOrDefault(false)
        }.getOrDefault(false)
        if (ok) return model
        // Fall back to the bundled default when the chat model is not an embedding one.
        if (model != DEFAULT_EMBEDDING_MODEL) {
            val fallbackOk = runCatching {
                val body = client.get("$base/api/tags").bodyAsText()
                val root = Json.parseToJsonElement(body).jsonObject
                val names = root["models"]?.jsonArray?.mapNotNull { entry ->
                    entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                }.orEmpty()
                names.any { it == DEFAULT_EMBEDDING_MODEL || it.startsWith("$DEFAULT_EMBEDDING_MODEL:") }
            }.getOrDefault(false)
            if (fallbackOk) return DEFAULT_EMBEDDING_MODEL
        }
        return null
    }

    private suspend fun embedTexts(texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val base = ollamaBase()
        val model = probeModel() ?: run {
            _status.value = "Ollama недоступна или нет embedding-модели ($DEFAULT_EMBEDDING_MODEL)"
            return null
        }
        return runCatching {
            val response = client.post("$base/api/embed") {
                contentType(ContentType.Application.Json)
                setBody(
                    JsonObject(
                        mapOf(
                            "model" to JsonPrimitive(model),
                            "input" to JsonArray(texts.map { JsonPrimitive(it) }),
                        ),
                    ),
                )
            }
            if (!response.status.isSuccess()) {
                _status.value = "Ollama вернула ошибку ${response.status.value}"
                return null
            }
            val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val arr = root["embeddings"]?.jsonArray ?: return null
            arr.map { row -> FloatArray(row.jsonArray.size) { i -> row.jsonArray[i].jsonPrimitive.doubleOrNull?.toFloat() ?: 0f } }
        }.onFailure { t ->
            _status.value = "Ошибка Ollama: ${t.message ?: t::class.simpleName}"
            if (_firstIndexError.value == null) _firstIndexError.value = t.message ?: t::class.simpleName
        }.getOrNull()
    }

    override suspend fun indexPhotos() {
        if (_indexing.value) return
        val job = scope.launch { runCatching { indexPhotosInternal() } }
        job.join()
    }

    override suspend fun indexDocuments() {
        if (_indexing.value) return
        val job = scope.launch { runCatching { indexDocumentsInternal() } }
        job.join()
    }

    private suspend fun indexPhotosInternal() {
        if (_indexing.value) return
        val model = probeModel() ?: run {
            _status.value = "Сначала запустите Ollama и скачайте $DEFAULT_EMBEDDING_MODEL"
            return
        }
        _modelReady.value = true
        cancelRequested = false
        _paused.value = false
        _indexing.value = true
        indexingSource = "photo"
        _progress.value = 0f
        _currentProcessedCount.value = 0
        _skippedPhotoCount.value = 0
        _firstIndexError.value = null
        _status.value = "Подготовка индексации фото…"
        try {
            val pictures = File(System.getProperty("user.home"), "Pictures")
            val files = pictures.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in PHOTO_EXTENSIONS }
                .take(5000)
                .toList()
            val known = entries.filter { it.source == "photo" }.associate { it.id to it.mtime }
            val toIndex = files.filter { known["photo:${it.absolutePath}"] != it.lastModified() }
            entries.removeAll { it.source == "photo" && it.id.removePrefix("photo:") !in files.map { f -> f.absolutePath }.toSet() }
            _currentTotalCount.value = toIndex.size
            var processed = 0
            for (file in toIndex) {
                while (_paused.value && !cancelRequested) kotlinx.coroutines.delay(300L)
                if (cancelRequested || !scope.isActive) break
                val vec = embedTexts(listOf("photo ${file.nameWithoutExtension}"))?.firstOrNull()
                if (vec == null) {
                    _skippedPhotoCount.value = _skippedPhotoCount.value + 1
                    processed++
                    _currentProcessedCount.value = processed
                    _progress.value = if (toIndex.isNotEmpty()) processed.toFloat() / toIndex.size else 1f
                    continue
                }
                markDim(vec.size)
                entries += DesktopVectorEntry(
                    id = "photo:${file.absolutePath}",
                    source = "photo",
                    title = file.name,
                    text = file.nameWithoutExtension,
                    uri = file.absolutePath,
                    vector = l2normalize(vec),
                    mtime = file.lastModified(),
                )
                processed++
                _currentProcessedCount.value = processed
                _progress.value = if (toIndex.isNotEmpty()) processed.toFloat() / toIndex.size else 1f
                _status.value = "Индексация фото: $processed/${toIndex.size}"
                if (processed % 25 == 0) saveStore()
            }
            saveStore()
            _indexedPhotoCount.value = entries.count { it.source == "photo" }
            _status.value = "Проиндексировано фото: ${_indexedPhotoCount.value}"
        } catch (t: Throwable) {
            _status.value = "Ошибка индексации фото: ${t.message ?: t::class.simpleName}"
        } finally {
            _indexing.value = false
            _paused.value = false
            indexingSource = null
            _progress.value = null
            _currentTotalCount.value = 0
            _currentProcessedCount.value = 0
        }
    }

    private suspend fun indexDocumentsInternal() {
        if (_indexing.value) return
        val model = probeModel() ?: run {
            _status.value = "Сначала запустите Ollama и скачайте $DEFAULT_EMBEDDING_MODEL"
            return
        }
        _modelReady.value = true
        cancelRequested = false
        _paused.value = false
        _indexing.value = true
        indexingSource = "document"
        _progress.value = 0f
        _currentProcessedCount.value = 0
        _status.value = "Подготовка индексации документов…"
        try {
            val docs = File(System.getProperty("user.home"), "Documents")
            val files = docs.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in DOC_EXTENSIONS && it.length() < 5_000_000 }
                .take(2000)
                .toList()
            val known = entries.filter { it.source == "document" }.associate { it.id to it.mtime }
            _currentTotalCount.value = files.size
            var processed = 0
            for (file in files) {
                while (_paused.value && !cancelRequested) kotlinx.coroutines.delay(300L)
                if (cancelRequested || !scope.isActive) break
                val mtime = file.lastModified()
                if (known["doc:${file.absolutePath}"] == mtime) {
                    processed++
                    _currentProcessedCount.value = processed
                    _progress.value = if (files.isNotEmpty()) processed.toFloat() / files.size else 1f
                    continue
                }
                entries.removeAll { it.source == "document" && it.id == "doc:${file.absolutePath}" }
                val text = runCatching { file.readText(Charsets.UTF_8).take(50_000) }.getOrNull()?.ifBlank { null } ?: file.nameWithoutExtension
                val chunks = chunkText(text)
                val vecs = embedTexts(chunks.map { "document ${file.name}: $it" })
                if (vecs == null) {
                    _status.value = "Ollama недоступна — индексация остановлена"
                    break
                }
                vecs.forEachIndexed { i, vec ->
                    markDim(vec.size)
                    entries += DesktopVectorEntry(
                        id = "doc:${file.absolutePath}#$i",
                        source = "document",
                        title = file.name,
                        text = chunks.getOrElse(i) { text }.take(500),
                        uri = file.absolutePath,
                        vector = l2normalize(vec),
                        mtime = mtime,
                    )
                }
                processed++
                _currentProcessedCount.value = processed
                _progress.value = if (files.isNotEmpty()) processed.toFloat() / files.size else 1f
                _status.value = "Индексация документов: $processed/${files.size}"
                if (processed % 10 == 0) saveStore()
            }
            saveStore()
            _indexedDocumentCount.value = entries.count { it.source == "document" && '#' !in it.id }
            _status.value = "Проиндексировано документов: ${_indexedDocumentCount.value}"
        } catch (t: Throwable) {
            _status.value = "Ошибка индексации документов: ${t.message ?: t::class.simpleName}"
        } finally {
            _indexing.value = false
            _paused.value = false
            indexingSource = null
            _progress.value = null
            _currentTotalCount.value = 0
            _currentProcessedCount.value = 0
        }
    }

    override fun cancelIndexing() {
        cancelRequested = true
        _paused.value = false
    }

    override fun pauseIndexing() {
        _paused.value = true
    }

    override fun resumeIndexing() {
        _paused.value = false
        if (!_indexing.value) {
            val source = indexingSource
            scope.launch {
                runCatching {
                    if (source == "document") indexDocuments() else indexPhotos()
                }
            }
        }
    }

    override suspend fun search(query: String, source: String?, limit: Int, minScore: Double): List<Map<String, Any?>> {
        if (_indexStale.value) return emptyList()
        val qv = embedTexts(listOf("search query: $query"))?.firstOrNull() ?: return emptyList()
        if (cachedDim != 0 && qv.size != cachedDim) return emptyList()
        return entries
            .filter { source == null || it.source == source }
            .filter { cachedDim == 0 || it.vector.size == qv.size }
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
    }

    private fun markDim(dim: Int) {
        if (cachedDim == 0) {
            cachedDim = dim
        } else if (cachedDim != dim) {
            _indexStale.value = true
        }
    }

    private fun storeFile(): File = File(com.pusios.pots.getAppFilesDirectory(), STORE_FILE)

    private fun loadStore() {
        val file = storeFile()
        if (!file.exists()) return
        runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val schema = root["schema"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
            val model = root["modelId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val dim = root["dim"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
            if (schema != SCHEMA_VERSION || model != modelFingerprint()) {
                file.delete()
                _indexStale.value = true
                entries.clear()
                _indexedAt.value = null
                return@runCatching
            }
            cachedDim = dim
            val arr = root["entries"]?.jsonArray ?: JsonArray(emptyList())
            for (element in arr) {
                val o = element.jsonObject
                val vecArr = o["vector"]?.jsonArray ?: continue
                val vec = FloatArray(vecArr.size) { i -> vecArr[i].jsonPrimitive.doubleOrNull?.toFloat() ?: 0f }
                entries += DesktopVectorEntry(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue,
                    source = o["source"]?.jsonPrimitive?.contentOrNull ?: continue,
                    title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    uri = o["uri"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                    vector = l2normalize(vec),
                    mtime = o["mtime"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
            _indexedAt.value = root["_indexedAt"]?.jsonPrimitive?.longOrNull
            _indexStale.value = false
        }
    }

    private fun saveStore() {
        runCatching {
            val arr = JsonArray(
                entries.map { e ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(e.id),
                            "source" to JsonPrimitive(e.source),
                            "title" to JsonPrimitive(e.title),
                            "text" to JsonPrimitive(e.text),
                            "uri" to JsonPrimitive(e.uri.orEmpty()),
                            "mtime" to JsonPrimitive(e.mtime),
                            "vector" to JsonArray(e.vector.map { JsonPrimitive(it.toDouble()) }),
                        ),
                    )
                },
            )
            val root = JsonObject(
                mapOf(
                    "schema" to JsonPrimitive(SCHEMA_VERSION),
                    "modelId" to JsonPrimitive(modelFingerprint()),
                    "dim" to JsonPrimitive(cachedDim),
                    "_indexedAt" to JsonPrimitive(System.currentTimeMillis()),
                    "entries" to arr,
                ),
            )
            storeFile().writeText(root.toString())
            _indexedAt.value = System.currentTimeMillis()
            _indexStale.value = false
        }
    }

    private fun modelFingerprint(): String = "ollama:$DEFAULT_EMBEDDING_MODEL"

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
        return chunks.filter { it.isNotBlank() }.ifEmpty { listOf(text.take(maxChars)) }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
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

    private companion object {
        const val DEFAULT_EMBEDDING_MODEL = "embeddinggemma-2:440m"
        const val STORE_FILE = "vector_store_ollama.json"
        const val SCHEMA_VERSION = 1
        val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        val DOC_EXTENSIONS = setOf("txt", "md", "markdown", "pdf", "docx", "html", "htm", "csv", "json", "xml", "log")
    }
}

private data class DesktopVectorEntry(
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
    // LiteRT only exists on Android builds: on desktop the embedding backend is
    // Ollama, configured through the OpenAI-Compatible service in Settings.
    if (localInferenceEngine == null) return OllamaVectorizationManager(appSettings)
    return com.pusios.pots.data.NoOpVectorizationManager()
}
