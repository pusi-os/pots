package com.pusios.pots.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaJobRequest
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.MediaJobStatus
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.media.getInstanceMediaImageModelId
import com.pusios.pots.media.getInstanceMediaVideoModelId
import com.pusios.pots.media.setInstanceMediaImageModelId
import com.pusios.pots.media.setInstanceMediaVideoModelId
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.saveFileToDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Reference URLs merged into the next generation (`input_urls`). */
    private val _references = MutableStateFlow<List<String>>(emptyList())
    val references: StateFlow<List<String>> = _references.asStateFlow()

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
                .onSuccess { _models.value = it }
                .onFailure { _error.value = it.message }
        }
    }

    fun generateImage(prompt: String, opts: Map<String, String> = emptyMap()) {
        val instanceId = instanceIdProvider()
        val model = appSettings.getInstanceMediaImageModelId(instanceId).ifBlank {
            _models.value.firstOrNull { it.kind == MediaKind.Image }?.id.orEmpty()
        }
        if (model.isBlank()) {
            _error.value = "Выбери модель изображения в настройках сервисов"
            return
        }
        val merged = buildMap {
            putAll(opts)
            if (_references.value.isNotEmpty()) put("input_urls", _references.value.joinToString(","))
        }
        viewModelScope.launch {
            runCatching {
                repository.generate(apiKeyProvider(), MediaJobRequest(model, prompt, merged)) { _jobs.update { m -> m + (it.jobId to it) } }
            }.onFailure { _error.value = it.message }
        }
    }

    fun generateVideo(prompt: String, opts: Map<String, String> = emptyMap()) {
        val instanceId = instanceIdProvider()
        val model = appSettings.getInstanceMediaVideoModelId(instanceId).ifBlank {
            _models.value.firstOrNull { it.kind == MediaKind.Video }?.id.orEmpty()
        }
        if (model.isBlank()) {
            _error.value = "Выбери модель видео в настройках сервисов"
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.generate(apiKeyProvider(), MediaJobRequest(model, prompt, opts)) { _jobs.update { m -> m + (it.jobId to it) } }
            }.onFailure { _error.value = it.message }
        }
    }

    fun selectImageModel(modelId: String) {
        appSettings.setInstanceMediaImageModelId(instanceIdProvider(), modelId)
    }

    fun selectVideoModel(modelId: String) {
        appSettings.setInstanceMediaVideoModelId(instanceIdProvider(), modelId)
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
                val url = source.url ?: source.localPath ?: return
                _references.update { (it + url).distinct() }
            }

            CanvasAction.ClearSelection -> _selectedId.value = null

            CanvasAction.Move, CanvasAction.Resize, CanvasAction.Rotate -> Unit
        }
    }

    fun clearReferences() {
        _references.value = emptyList()
    }

    fun dismissError() {
        _error.value = null
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
                ?: runCatching { repositoryDownload(element.url ?: "") }.getOrNull()
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
