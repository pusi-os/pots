package com.pusios.pots.ui.media

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.MAX_INLINE_REFERENCE_BYTES
import com.pusios.pots.media.MediaException
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaHistoryEntry
import com.pusios.pots.media.MediaHistoryStatus
import com.pusios.pots.media.MediaHistoryStore
import com.pusios.pots.media.MediaJobRequest
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.MediaJobStatus
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaLog
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.MediaReference
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.media.PromptEnhancer
import com.pusios.pots.media.compressReferenceToLimit
import com.pusios.pots.media.getInstanceMediaImageModelId
import com.pusios.pots.media.getInstanceMediaVideoModelId
import com.pusios.pots.media.setInstanceMediaImageModelId
import com.pusios.pots.media.setInstanceMediaVideoModelId
import com.pusios.pots.media.toHistoryReference
import com.pusios.pots.media.userMessageRu
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.saveFileToDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Error shown in the studio snackbar. [isKeyProblem] offers a settings shortcut;
 * [oversizedReferenceIndex] offers compressing that reference in place.
 */
@Immutable
data class MediaStudioError(
    val message: String,
    val isKeyProblem: Boolean = false,
    val oversizedReferenceIndex: Int? = null,
)

private data class PendingGeneration(val prompt: String, val opts: Map<String, String>, val kind: MediaKind)

@OptIn(ExperimentalUuidApi::class)
class MediaStudioViewModel(
    private val appSettings: AppSettings,
    private val apiKeyProvider: () -> String,
    private val instanceIdProvider: () -> String,
    private val repository: MediaGenerationRepository,
    private val history: MediaHistoryStore,
    private val enhancer: suspend (String) -> String,
) : ViewModel() {

    /** Journal of generations, newest first. Survives app restarts. */
    val historyEntries: StateFlow<List<MediaHistoryEntry>> = history.entries

    private val _elements = MutableStateFlow<List<CanvasElement>>(emptyList())
    val elements: StateFlow<List<CanvasElement>> = _elements.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _models = MutableStateFlow<List<MediaModel>>(emptyList())
    val models: StateFlow<List<MediaModel>> = _models.asStateFlow()

    private val _jobs = MutableStateFlow<Map<String, MediaJobState>>(emptyMap())
    val jobs: StateFlow<Map<String, MediaJobState>> = _jobs.asStateFlow()

    private var pendingGeneration: PendingGeneration? = null

    private val _error = MutableStateFlow<MediaStudioError?>(null)
    val error: StateFlow<MediaStudioError?> = _error.asStateFlow()

    /** True from the tap until the job is submitted (or refused), before it shows up in [jobs]. */
    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    /** True while the prompt is being rewritten by the active chat model. */
    private val _isEnhancing = MutableStateFlow(false)
    val isEnhancing: StateFlow<Boolean> = _isEnhancing.asStateFlow()

    /** References merged into the next generation (mapped onto model opts by the provider). */
    private val _references = MutableStateFlow<List<MediaReference>>(emptyList())
    val references: StateFlow<List<MediaReference>> = _references.asStateFlow()

    /** Persisted per-instance model ids; empty until the catalog or settings say otherwise. */
    private val _selectedImageModelId = MutableStateFlow(appSettings.getInstanceMediaImageModelId(instanceIdProvider()))
    val selectedImageModelId: StateFlow<String> = _selectedImageModelId.asStateFlow()

    private val _selectedVideoModelId = MutableStateFlow(appSettings.getInstanceMediaVideoModelId(instanceIdProvider()))
    val selectedVideoModelId: StateFlow<String> = _selectedVideoModelId.asStateFlow()

    /** Emits when a new job succeeds; host announces via TTS ("генерация готова"). */
    private val _justFinished = MutableStateFlow<MediaJobState?>(null)
    val justFinished: StateFlow<MediaJobState?> = _justFinished.asStateFlow()

    fun consumeFinished() {
        _justFinished.value = null
    }

    init {
        history.start()
        viewModelScope.launch {
            repository.jobs.collect { map ->
                _jobs.value = map
                // Auto-place finished results on the canvas once.
                map.values.filter { it.status == MediaJobStatus.Success }
                    .forEach { state ->
                        if (_elements.value.none { it.jobId == state.jobId }) {
                            addElement(state)
                            _justFinished.value = state
                        }
                    }
            }
        }
        loadModels()
    }

    fun loadModels() {
        val key = apiKeyProvider()
        viewModelScope.launch {
            runCatching { repository.models(key) }
                .onSuccess { catalog ->
                    _models.value = catalog
                    // Drop selections that no longer exist in the catalog.
                    if (catalog.isNotEmpty()) {
                        if (catalog.none { it.id == _selectedImageModelId.value }) _selectedImageModelId.value = ""
                        if (catalog.none { it.id == _selectedVideoModelId.value }) _selectedVideoModelId.value = ""
                    }
                }
                .onFailure { showError("Каталог моделей не загрузился", it) }
        }
    }

    fun generateImage(prompt: String, opts: Map<String, String> = emptyMap()) {
        generate(prompt, opts, MediaKind.Image)
    }

    fun generateVideo(prompt: String, opts: Map<String, String> = emptyMap()) {
        generate(prompt, opts, MediaKind.Video)
    }

    /** Rewrites [prompt] with the active chat model and hands the result back. Ignored while one is running. */
    fun enhancePrompt(prompt: String, onResult: (String) -> Unit) {
        if (prompt.isBlank() || _isEnhancing.value) return
        _isEnhancing.value = true
        viewModelScope.launch {
            try {
                runCatching { enhancer(PromptEnhancer.buildPrompt(prompt)) }
                    .onSuccess { result ->
                        PromptEnhancer.clean(result).takeIf { it.isNotBlank() }?.let(onResult)
                            ?: showError("Не удалось улучшить промпт", IllegalStateException("empty answer"))
                    }
                    .onFailure { showError("Не удалось улучшить промпт", it) }
            } finally {
                _isEnhancing.value = false
            }
        }
    }

    private fun generate(prompt: String, opts: Map<String, String>, kind: MediaKind) {
        if (isGenerationBusy()) return
        val key = apiKeyProvider()
        if (key.isBlank()) {
            _error.value = MediaStudioError(
                "Нет API-ключа сервиса — добавь его в настройках сервисов",
                isKeyProblem = true,
            )
            MediaLog.w("Generate refused: no API key configured")
            return
        }
        val instanceId = instanceIdProvider()
        val model = resolveModel(kind, instanceId)
        if (model == null) {
            _error.value = MediaStudioError(
                if (kind == MediaKind.Image) "Нет доступной модели изображения — обнови каталог" else "Нет доступной модели видео — обнови каталог",
            )
            MediaLog.w("Generate refused: no $kind model available")
            return
        }
        val references = _references.value
        pendingGeneration = PendingGeneration(prompt, opts, kind)
        MediaLog.i("Generate: kind=$kind model=${model.id} opts=${opts.keys} refs=${references.size}")
        val entry = MediaHistoryEntry(
            localId = Uuid.random().toString(),
            createdAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            isVideo = kind == MediaKind.Video,
            model = model.id,
            prompt = prompt,
            opts = opts,
            references = references.mapNotNull { it.toHistoryReference() },
            droppedReferences = references.count { it.toHistoryReference() == null },
        )
        history.upsert(entry)
        _isStarting.value = true
        viewModelScope.launch {
            try {
                submitGeneration(key, entry.localId, model.id, prompt, opts, references)
            } finally {
                _isStarting.value = false
            }
        }
    }

    private suspend fun submitGeneration(
        key: String,
        localId: String,
        modelId: String,
        prompt: String,
        opts: Map<String, String>,
        references: List<MediaReference>,
        savedQuoteId: String? = null,
    ) {
        val oversized = withContext(Dispatchers.Default) { oversizedReferenceIndex(references) }
        if (oversized != null) {
            MediaLog.w("Reference ${oversized + 1} exceeds $MAX_INLINE_REFERENCE_BYTES bytes")
            history.remove(localId)
            _error.value = MediaStudioError(
                "Референс ${oversized + 1} больше 8 МБ — сожми его, чтобы отправить",
                oversizedReferenceIndex = oversized,
            )
            return
        }
        runCatching {
            repository.generate(
                key,
                MediaJobRequest(
                    model = modelId,
                    prompt = prompt,
                    opts = opts,
                    references = references,
                    quoteId = savedQuoteId,
                    idempotencyKey = localId,
                ),
                onQuote = { quote ->
                    history.modify(localId) {
                        it.copy(estimateRub = quote.priceRub, quoteId = quote.quoteId, maxPriceRub = quote.priceRub)
                    }
                },
            ) { state ->
                _jobs.update { m -> m + (state.jobId to state) }
                history.modify(localId) { it.copy(jobId = state.jobId) }
            }
        }.onSuccess { state ->
            history.modify(localId) { it.copy(jobId = state.jobId, status = MediaHistoryStatus.Processing) }
        }.onFailure { error ->
            history.modify(localId) { it.copy(status = MediaHistoryStatus.Failed, failMsg = journalFailureText(error)) }
            showError("Генерация не запустилась", error)
        }
    }

    private fun journalFailureText(error: Throwable): String = when (error) {
        is MediaException -> error.userMessageRu()
        else -> "Не удалось отправить запрос. Повтори — ключ защищает от двойного списания."
    }

    /**
     * Repeats an entry with its original references and prompt. A failed submit keeps its
     * idempotency key, so the provider cannot charge twice; a finished job gets a new key.
     */
    fun repeat(localId: String) {
        val source = history.entries.value.firstOrNull { it.localId == localId } ?: return
        if (!source.canRepeat || isGenerationBusy()) return
        val key = apiKeyProvider()
        if (key.isBlank()) {
            _error.value = MediaStudioError("Нет API-ключа сервиса — добавь его в настройках сервисов", isKeyProblem = true)
            return
        }
        val reuseKey = source.canRetrySubmit
        val newEntry = source.copy(
            quoteId = if (reuseKey) source.quoteId else null,
            maxPriceRub = if (reuseKey) source.maxPriceRub else null,
            localId = if (reuseKey) source.localId else Uuid.random().toString(),
            createdAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            status = MediaHistoryStatus.Sending,
            jobId = null,
            priceRub = null,
            resultUrl = null,
            localPath = null,
            failMsg = null,
            progress = null,
        )
        if (reuseKey) history.modify(source.localId) { newEntry } else history.upsert(newEntry)
        val references = source.references.map { MediaReference(url = it.url, localPath = it.localPath, isVideo = it.isVideo) }
        _isStarting.value = true
        viewModelScope.launch {
            try {
                submitGeneration(key, newEntry.localId, source.model, source.prompt, source.opts, references, newEntry.quoteId)
            } finally {
                _isStarting.value = false
            }
        }
    }

    /** Saves a finished journal entry to the device gallery. */
    fun saveToGallery(localId: String) {
        val entry = history.entries.value.firstOrNull { it.localId == localId } ?: return
        viewModelScope.launch {
            val bytes = entry.localPath?.let { withContext(Dispatchers.Default) { readAppStorageBytes(it) } }
                ?: entry.resultUrl?.let { runCatching { repository.download(apiKeyProvider(), it) }.getOrNull() }
            if (bytes == null) {
                _error.value = MediaStudioError("Файл ещё не скачан — попробуй через минуту")
                return@launch
            }
            saveFileToDevice(bytes, "pots-${entry.localId.take(8)}", if (entry.isVideo) "mp4" else "png")
        }
    }

    /** Removes the entry from the journal. The job itself is not cancelled, and any canvas copy stays. */
    fun deleteFromHistory(localId: String) {
        history.remove(localId)
    }

    fun clearFinishedHistory() {
        history.clearFinished()
    }

    /** Adds a finished journal result to the canvas. */
    fun placeOnCanvas(localId: String) {
        val entry = history.entries.value.firstOrNull { it.localId == localId } ?: return
        if (entry.status != MediaHistoryStatus.Success) return
        val state = MediaJobState(
            jobId = entry.jobId ?: return,
            status = MediaJobStatus.Success,
            kind = if (entry.isVideo) MediaKind.Video else MediaKind.Image,
            resultUrls = listOfNotNull(entry.resultUrl),
            localPath = entry.localPath,
        )
        if (_elements.value.none { it.jobId == state.jobId }) addElement(state)
    }

    /** True while a submit is in flight or a job is still running; the create button stays locked meanwhile. */
    fun isGenerationBusy(): Boolean = _isStarting.value || _jobs.value.values.any { !it.isTerminal } || history.entries.value.any { it.isActive }

    private fun oversizedReferenceIndex(references: List<MediaReference>): Int? = references.indexOfFirst { ref ->
        val path = ref.localPath
        ref.url == null && ref.base64DataUri == null && !ref.isVideo && path != null &&
            (readAppStorageBytes(path)?.size?.toLong() ?: 0L) > MAX_INLINE_REFERENCE_BYTES
    }.takeIf { it >= 0 }

    /**
     * Re-encodes the reference at [index] under the size limit, stores it as a new JPEG,
     * and repeats the last generation so the user does not have to retype the prompt.
     */
    fun compressReference(index: Int) {
        val path = _references.value.getOrNull(index)?.localPath ?: return
        viewModelScope.launch {
            val source = withContext(Dispatchers.Default) { readAppStorageBytes(path) }
            val compressed = source?.let { withContext(Dispatchers.Default) { compressReferenceToLimit(it) } }
            if (source == null || compressed == null) {
                MediaLog.w("Reference ${index + 1} compression failed")
                _error.value = MediaStudioError("Не удалось сжать референс до 8 МБ — выбери файл поменьше")
                return@launch
            }
            val newPath = "media/refs/${Uuid.random()}.jpg"
            if (!com.pusios.pots.writeAppStorageBytes(newPath, compressed)) {
                _error.value = MediaStudioError("Не удалось сохранить сжатый референс")
                return@launch
            }
            _references.update { list ->
                list.mapIndexed { i, ref -> if (i == index) ref.copy(localPath = newPath) else ref }
            }
            MediaLog.i("Reference ${index + 1} compressed: ${source.size} -> ${compressed.size} bytes")
            pendingGeneration?.let { generate(it.prompt, it.opts, it.kind) }
        }
    }

    /** Saved choice wins; otherwise auto-pick the first catalog model and persist it. */
    private fun resolveModel(kind: MediaKind, instanceId: String): MediaModel? {
        val candidates = _models.value.filter { it.kind == kind }
        val savedId = if (kind == MediaKind.Image) _selectedImageModelId.value else _selectedVideoModelId.value
        val saved = candidates.firstOrNull { it.id == savedId }
        if (saved != null) return saved
        val fallback = candidates.firstOrNull() ?: return null
        if (kind == MediaKind.Image) {
            appSettings.setInstanceMediaImageModelId(instanceId, fallback.id)
            _selectedImageModelId.value = fallback.id
        } else {
            appSettings.setInstanceMediaVideoModelId(instanceId, fallback.id)
            _selectedVideoModelId.value = fallback.id
        }
        MediaLog.i("Auto-selected $kind model: ${fallback.id}")
        return fallback
    }

    fun selectImageModel(modelId: String) {
        appSettings.setInstanceMediaImageModelId(instanceIdProvider(), modelId)
        _selectedImageModelId.value = modelId
    }

    fun selectVideoModel(modelId: String) {
        appSettings.setInstanceMediaVideoModelId(instanceIdProvider(), modelId)
        _selectedVideoModelId.value = modelId
    }

    fun addElement(state: MediaJobState) {
        val url = state.resultUrls.firstOrNull() ?: return
        val isVideo = state.kind == MediaKind.Video || url.endsWith(".mp4")
        val id = Uuid.random().toString()
        _elements.update {
            it + CanvasElement(
                id = id,
                jobId = state.jobId,
                url = url,
                localPath = state.localPath,
                isVideo = isVideo,
                x = (it.size * 32f),
                y = (it.size * 32f),
                zIndex = (it.maxOfOrNull { e -> e.zIndex } ?: 0) + 1,
            )
        }
    }

    fun move(id: String, dx: Float, dy: Float) {
        _elements.update { list ->
            list.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it }
        }
    }

    fun scale(id: String, factor: Float) {
        _elements.update { list ->
            list.map { if (it.id == id) it.copy(scale = (it.scale * factor).coerceIn(0.2f, 4f)) else it }
        }
    }

    fun rotate(id: String, degrees: Float) {
        _elements.update { list ->
            list.map { if (it.id == id) it.copy(rotation = it.rotation + degrees) else it }
        }
    }

    fun select(id: String?) {
        _selectedId.value = id
    }

    fun applyAction(id: String, action: CanvasAction) {
        when (action) {
            CanvasAction.Delete -> _elements.update { it.filterNot { e -> e.id == id } }

            CanvasAction.Duplicate -> {
                val source = _elements.value.firstOrNull { it.id == id } ?: return
                _elements.update {
                    it + source.copy(
                        id = Uuid.random().toString(),
                        x = source.x + 24f,
                        y = source.y + 24f,
                        zIndex = source.zIndex + 1,
                    )
                }
            }

            CanvasAction.BringForward -> _elements.update { l ->
                val maxZ = l.maxOfOrNull { it.zIndex } ?: 0
                l.map { if (it.id == id) it.copy(zIndex = maxZ + 1) else it }
            }

            CanvasAction.SendBack -> _elements.update { l ->
                val minZ = l.minOfOrNull { it.zIndex } ?: 0
                l.map { if (it.id == id) it.copy(zIndex = minZ - 1) else it }
            }

            CanvasAction.UseAsReference -> {
                val source = _elements.value.firstOrNull { it.id == id } ?: return
                val url = source.url?.takeIf { it.startsWith("https://") }
                val localPath = source.localPath
                if (url == null && localPath == null) return
                val reference = MediaReference(url = url, localPath = localPath, isVideo = source.isVideo)
                addReference(reference)
            }

            CanvasAction.ClearSelection -> _selectedId.value = null

            CanvasAction.Move, CanvasAction.Resize, CanvasAction.Rotate -> Unit
        }
    }

    /** Add a reference for the next generation (deduped by url/path). */
    fun addReference(reference: MediaReference) {
        _references.update { list ->
            if (list.any { it.url == reference.url && it.localPath == reference.localPath }) list else list + reference
        }
        MediaLog.d("Reference added: url=${reference.url != null} base64=${reference.base64DataUri != null} local=${reference.localPath} video=${reference.isVideo}")
    }

    fun removeReference(index: Int) {
        _references.update { it.filterIndexed { i, _ -> i != index } }
    }

    fun clearReferences() {
        _references.value = emptyList()
    }

    fun dismissError() {
        _error.value = null
    }

    private fun showError(prefix: String, cause: Throwable) {
        val isKeyProblem = cause is MediaException.InvalidKey
        val message = when (cause) {
            is MediaException -> cause.userMessageRu()
            else -> "$prefix: ${cause.message ?: cause::class.simpleName}"
        }
        MediaLog.e(prefix, cause)
        _error.value = MediaStudioError(message, isKeyProblem)
    }

    /** Add an imported gallery asset straight to the canvas (bytes already cached). */
    fun importFromGallery(localPath: String, isVideo: Boolean, bytes: ByteArray? = null) {
        if (bytes != null) com.pusios.pots.writeAppStorageBytes(localPath, bytes)
        val id = Uuid.random().toString()
        _elements.update {
            it + CanvasElement(
                id = id,
                jobId = null,
                url = null,
                localPath = localPath,
                isVideo = isVideo,
                x = (it.size * 32f),
                y = (it.size * 32f),
                zIndex = (it.maxOfOrNull { e -> e.zIndex } ?: 0) + 1,
            )
        }
    }

    /** Save a canvas element to the device gallery via platform writer. */
    fun exportToGallery(elementId: String) {
        val element = _elements.value.firstOrNull { it.id == elementId } ?: return
        viewModelScope.launch {
            val bytes = element.localPath?.let { readAppStorageBytes(it) }
                ?: element.url?.let { runCatching { repositoryDownload(element.url) }.getOrNull() }
                ?: return@launch
            val ext = if (element.isVideo) "mp4" else "png"
            saveFileToDevice(bytes, "pots-media-${elementId.take(8)}", ext)
        }
    }

    private suspend fun repositoryDownload(url: String): ByteArray {
        // Provider download is key-free for signed URLs; reuse repository's next
        // available mechanism by fetching through a fresh provider call.
        return PlusVibeMediaProvider().downloadResult(apiKeyProvider(), url)
    }
}
