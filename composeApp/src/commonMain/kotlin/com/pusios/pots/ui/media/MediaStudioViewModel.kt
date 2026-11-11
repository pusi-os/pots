package com.pusios.pots.ui.media

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.MAX_INLINE_REFERENCE_BYTES
import com.pusios.pots.media.MediaException
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaJobRequest
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.MediaJobStatus
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaLog
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.MediaReference
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.media.compressReferenceToLimit
import com.pusios.pots.media.getInstanceMediaImageModelId
import com.pusios.pots.media.getInstanceMediaVideoModelId
import com.pusios.pots.media.setInstanceMediaImageModelId
import com.pusios.pots.media.setInstanceMediaVideoModelId
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
) : ViewModel() {

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

    private fun generate(prompt: String, opts: Map<String, String>, kind: MediaKind) {
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
        viewModelScope.launch {
            val oversized = withContext(Dispatchers.Default) { oversizedReferenceIndex(references) }
            if (oversized != null) {
                MediaLog.w("Reference ${oversized + 1} exceeds $MAX_INLINE_REFERENCE_BYTES bytes")
                _error.value = MediaStudioError(
                    "Референс ${oversized + 1} больше 8 МБ — сожми его, чтобы отправить",
                    oversizedReferenceIndex = oversized,
                )
                return@launch
            }
            runCatching {
                repository.generate(
                    key,
                    MediaJobRequest(model = model.id, prompt = prompt, opts = opts, references = references),
                ) { _jobs.update { m -> m + (it.jobId to it) } }
            }.onFailure { showError("Генерация не запустилась", it) }
        }
    }

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
