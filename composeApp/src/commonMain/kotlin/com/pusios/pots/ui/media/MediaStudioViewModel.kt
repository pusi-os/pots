package com.pusios.pots.ui.media

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.AudioClipStyle
import com.pusios.pots.media.AudioFlow
import com.pusios.pots.media.LOCAL_TTS_PROVIDER_MARK
import com.pusios.pots.media.LocalTtsSynthesizer
import com.pusios.pots.media.MAX_INLINE_REFERENCE_BYTES
import com.pusios.pots.media.MEDIA_PROJECT_SCHEMA_VERSION
import com.pusios.pots.media.MediaErrorCategory
import com.pusios.pots.media.MediaErrorInfo
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
import com.pusios.pots.media.MediaPriceTable
import com.pusios.pots.media.MediaProjectElement
import com.pusios.pots.media.MediaProjectException
import com.pusios.pots.media.MediaProjectFile
import com.pusios.pots.media.MediaProjectMeta
import com.pusios.pots.media.MediaProjectSequence
import com.pusios.pots.media.MediaProjectSequenceClip
import com.pusios.pots.media.MediaProjectSettings
import com.pusios.pots.media.MediaProjectStore
import com.pusios.pots.media.MediaReference
import com.pusios.pots.media.MediaVoice
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.media.PromptEnhancer
import com.pusios.pots.media.PromptTarget
import com.pusios.pots.media.SequenceClip
import com.pusios.pots.media.SequenceTransition
import com.pusios.pots.media.VoiceException
import com.pusios.pots.media.VoiceLevel
import com.pusios.pots.media.applyMediaOptDefaults
import com.pusios.pots.media.audioFlow
import com.pusios.pots.media.audioFlowModels
import com.pusios.pots.media.audioFlowModelsWithLocal
import com.pusios.pots.media.compressReferenceToLimit
import com.pusios.pots.media.createLocalTtsSynthesizer
import com.pusios.pots.media.decodeMediaProject
import com.pusios.pots.media.defaultAudioModel
import com.pusios.pots.media.encodeMediaProject
import com.pusios.pots.media.getInstanceMediaAudioModelId
import com.pusios.pots.media.getInstanceMediaAudioMusicModelId
import com.pusios.pots.media.getInstanceMediaAudioTtsModelId
import com.pusios.pots.media.getInstanceMediaImageModelId
import com.pusios.pots.media.getInstanceMediaVideoModelId
import com.pusios.pots.media.getMediaAudioFlow
import com.pusios.pots.media.getMediaDraft
import com.pusios.pots.media.getMediaQuality
import com.pusios.pots.media.isKnownLocalTtsId
import com.pusios.pots.media.isLocalTts
import com.pusios.pots.media.isLocalTtsId
import com.pusios.pots.media.isNetworkFailure
import com.pusios.pots.media.mediaErrorInfo
import com.pusios.pots.media.mediaKind
import com.pusios.pots.media.mediaMissingKeyError
import com.pusios.pots.media.mediaStorageError
import com.pusios.pots.media.probeVideoAspect
import com.pusios.pots.media.projectSequenceToClips
import com.pusios.pots.media.sequenceAdd
import com.pusios.pots.media.sequenceMove
import com.pusios.pots.media.sequenceRemoveAt
import com.pusios.pots.media.setInstanceMediaAudioMusicModelId
import com.pusios.pots.media.setInstanceMediaAudioTtsModelId
import com.pusios.pots.media.setInstanceMediaImageModelId
import com.pusios.pots.media.setInstanceMediaVideoModelId
import com.pusios.pots.media.setMediaAudioFlow
import com.pusios.pots.media.setMediaDraft
import com.pusios.pots.media.setMediaQuality
import com.pusios.pots.media.toHistoryReference
import com.pusios.pots.media.userMessageRu
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.saveFileToDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The studio error in the unified §8 shape: [title] is the essence, [detail]
 * one human sentence of cause, [category] the failure bucket — all built by
 * `mediaErrorInfo` / its siblings, so the snackbar, the journal and the
 * editor cards share one pattern (spec §8). [isKeyProblem] offers a settings
 * shortcut; [oversizedReferenceIndex] offers compressing that reference.
 */
@Immutable
data class MediaStudioError(
    val title: String,
    val detail: String = "",
    val category: MediaErrorCategory = MediaErrorCategory.Other,
    val isKeyProblem: Boolean = false,
    val oversizedReferenceIndex: Int? = null,
) {
    /** The snackbar line: essence plus cause. */
    val message: String get() = if (detail.isBlank()) title else "$title. $detail"

    companion object {
        fun fromInfo(info: MediaErrorInfo): MediaStudioError = MediaStudioError(info.title, info.detail, info.category, info.keyProblem)
    }
}

private data class PendingGeneration(val prompt: String, val opts: Map<String, String>, val kind: MediaKind)

@OptIn(ExperimentalUuidApi::class)
class MediaStudioViewModel(
    private val appSettings: AppSettings,
    private val apiKeyProvider: () -> String,
    private val instanceIdProvider: () -> String,
    private val repository: MediaGenerationRepository,
    private val history: MediaHistoryStore,
    private val enhancer: suspend (String) -> String,
    /**
     * Probes the frame aspect (width / height) of a video landing on the
     * canvas. Injectable for tests; the real probe is platform code (ffprobe
     * on desktop, MediaMetadataRetriever on Android, null elsewhere).
     */
    private val videoAspectProber: suspend (String) -> Float? = { probeVideoAspect(it) },
) : ViewModel() {

    /** Journal of generations, newest first. Survives app restarts. */
    val historyEntries: StateFlow<List<MediaHistoryEntry>> = history.entries

    /**
     * The canvas in draw order: the position in this list is the z-order, so a
     * reorder is a list move and nothing else. Persisted as `zIndex = index`.
     */
    private val _elements = MutableStateFlow<List<CanvasLayer>>(emptyList())
    val elements: StateFlow<List<CanvasLayer>> = _elements.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /**
     * The strokes of the running drawing session (see [DrawSession]): the canvas
     * holds the layers, this holds the ids undo and the eraser are allowed to
     * touch. Not persisted — the strokes themselves are, as layers.
     */
    private val _drawSession = MutableStateFlow(DrawSession())
    val drawSession: StateFlow<DrawSession> = _drawSession.asStateFlow()

    /** Collapsed strip ↔ open list; remembered in `MediaSettings`, not in the project. */
    private val _layersPanelOpen = MutableStateFlow(appSettings.getMediaLayersPanelOpen())
    val layersPanelOpen: StateFlow<Boolean> = _layersPanelOpen.asStateFlow()

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

    /** Audio model selection is per flow (spec §14): TTS and music remember separate models. */
    private val _selectedTtsModelId = MutableStateFlow(appSettings.getInstanceMediaAudioTtsModelId(instanceIdProvider()))
    val selectedTtsModelId: StateFlow<String> = _selectedTtsModelId.asStateFlow()

    private val _selectedMusicModelId = MutableStateFlow(appSettings.getInstanceMediaAudioMusicModelId(instanceIdProvider()))
    val selectedMusicModelId: StateFlow<String> = _selectedMusicModelId.asStateFlow()

    /** Remembered audio flow (spec §13): first tap enables audio with it, re-tap offers the choice. */
    private val _audioFlow = MutableStateFlow(appSettings.getMediaAudioFlow())
    val audioFlow: StateFlow<AudioFlow> = _audioFlow.asStateFlow()

    /** Prompt drafts keyed per mode and flow (`mediaDraftKey`); errors and switching never erase them. */
    private val _drafts = MutableStateFlow(loadDrafts())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    private var legacyAudioModelRouted = false

    /** Emits when a new job succeeds; host announces via TTS ("генерация готова"). */
    private val _justFinished = MutableStateFlow<MediaJobState?>(null)
    val justFinished: StateFlow<MediaJobState?> = _justFinished.asStateFlow()

    /** True while an offline (local engine) synthesis runs; no provider job exists meanwhile. */
    private val _isLocalSynthesizing = MutableStateFlow(false)
    val isLocalSynthesizing: StateFlow<Boolean> = _isLocalSynthesizing.asStateFlow()

    /** Cloned voices «Мой голос»; null until the first list request. */
    private val _myVoices = MutableStateFlow<List<MediaVoice>?>(null)
    val myVoices: StateFlow<List<MediaVoice>?> = _myVoices.asStateFlow()

    /** True while `POST /api/media/voices` is in flight. */
    private val _isCreatingVoice = MutableStateFlow(false)
    val isCreatingVoice: StateFlow<Boolean> = _isCreatingVoice.asStateFlow()

    /** Assembly sequence (spec §7): persisted by the project autosave (§12). */
    private val _sequence = MutableStateFlow(MediaSequenceState())
    val sequence: StateFlow<MediaSequenceState> = _sequence.asStateFlow()

    /** Studio mode: drives chips and the dock; part of the project snapshot (spec §12). */
    private val _mode = MutableStateFlow(MediaMode.Image)
    val mode: StateFlow<MediaMode> = _mode.asStateFlow()

    fun selectMode(newMode: MediaMode) {
        _mode.value = newMode
    }

    /** Current parameter chips; part of the project snapshot (spec §12). */
    private val _params = MutableStateFlow<Map<String, String>>(emptyMap())
    val params: StateFlow<Map<String, String>> = _params.asStateFlow()

    fun setParam(key: String, value: String) = _params.update { it + (key to value) }

    fun resetParams() {
        _params.value = emptyMap()
    }

    /** Drops selections the current model does not accept (spec §19.4 silent rollback). */
    fun pruneParams(acceptedKeys: Set<String>) = _params.update { current ->
        if (current.keys.any { it !in acceptedKeys }) current.filterKeys { it in acceptedKeys } else current
    }

    // -- Projects (spec §12): autosave, manual save, import, export ------------

    private val projectStore = MediaProjectStore()

    /** Named projects for the «Проекты» sheet, newest first. */
    private val _projects = MutableStateFlow<List<MediaProjectMeta>>(emptyList())
    val projects: StateFlow<List<MediaProjectMeta>> = _projects.asStateFlow()

    /** «Сохранено» mark in the top capsule: on after a write, off on the next change. */
    private val _projectSaved = MutableStateFlow(false)
    val projectSaved: StateFlow<Boolean> = _projectSaved.asStateFlow()

    /** The UI fades the «Сохранено» mark a moment after it appears. */
    fun acknowledgeProjectSaved() {
        _projectSaved.value = false
    }

    private var activeProjectName = defaultProjectName(nowMs())
    private var activeProjectCreatedAtMs = nowMs()
    private var autosaveStarted = false

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
                        if (_elements.value.none { it is MediaLayer && it.jobId == state.jobId }) {
                            addElement(state)
                            _justFinished.value = state
                        }
                    }
            }
        }
        loadModels()
        refreshProjects()
        restoreActiveProject()
        startAutosave()
    }

    fun loadModels() {
        val key = apiKeyProvider()
        viewModelScope.launch {
            runCatching { repository.models(key) }
                .onSuccess { catalog ->
                    _models.value = catalog
                    // Drop selections that no longer exist in the catalog.
                    if (catalog.isNotEmpty()) {
                        routeLegacyAudioModel(catalog)
                        if (catalog.none { it.id == _selectedImageModelId.value }) _selectedImageModelId.value = ""
                        if (catalog.none { it.id == _selectedVideoModelId.value }) _selectedVideoModelId.value = ""
                        if (audioFlowModels(catalog, AudioFlow.Tts).none { it.id == _selectedTtsModelId.value } &&
                            !isKnownLocalTtsId(_selectedTtsModelId.value)
                        ) {
                            _selectedTtsModelId.value = ""
                        }
                        if (audioFlowModels(catalog, AudioFlow.Music).none { it.id == _selectedMusicModelId.value }) {
                            _selectedMusicModelId.value = ""
                        }
                    }
                }
                .onFailure { showError("Каталог моделей не загрузился", it) }
        }
    }

    /** One-time migration: the pre-flow audio selection moves to the flow its model belongs to. */
    private fun routeLegacyAudioModel(catalog: List<MediaModel>) {
        if (legacyAudioModelRouted) return
        legacyAudioModelRouted = true
        val legacyId = appSettings.getInstanceMediaAudioModelId(instanceIdProvider())
        if (legacyId.isBlank()) return
        val flow = catalog.firstOrNull { it.id == legacyId }?.audioFlow() ?: return
        val target = if (flow == AudioFlow.Tts) _selectedTtsModelId else _selectedMusicModelId
        if (target.value.isBlank()) {
            target.value = legacyId
            persistAudioModelId(flow, legacyId)
        }
    }

    fun generateImage(prompt: String, opts: Map<String, String> = emptyMap()) {
        generate(prompt, opts, MediaKind.Image)
    }

    fun generateVideo(prompt: String, opts: Map<String, String> = emptyMap()) {
        generate(prompt, opts, MediaKind.Video)
    }

    fun generateAudio(prompt: String, opts: Map<String, String> = emptyMap()) {
        generate(prompt, opts, MediaKind.Audio)
    }

    /** Rewrites [prompt] with the active chat model and hands the result back. Ignored while one is running. */
    fun enhancePrompt(prompt: String, onResult: (String) -> Unit) {
        if (prompt.isBlank() || _isEnhancing.value) return
        _isEnhancing.value = true
        viewModelScope.launch {
            try {
                runCatching { enhancer(PromptEnhancer.buildPrompt(prompt, currentPromptTarget())) }
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

    /** The enhancement target follows the toolbar mode and the remembered audio flow (spec §13). */
    private fun currentPromptTarget(): PromptTarget = when (_mode.value) {
        MediaMode.Image -> PromptTarget.Image
        MediaMode.Video -> PromptTarget.Video
        MediaMode.Audio -> if (_audioFlow.value == AudioFlow.Tts) PromptTarget.Tts else PromptTarget.Music
    }

    private fun generate(prompt: String, opts: Map<String, String>, kind: MediaKind) {
        if (isGenerationBusy()) return
        val instanceId = instanceIdProvider()
        val model = resolveModel(kind, instanceId)
        if (model == null) {
            _error.value = MediaStudioError(
                "Нет доступной модели",
                when (kind) {
                    MediaKind.Image -> "Каталог не вернул модель изображения. Обновите каталог и попробуйте снова."
                    MediaKind.Video -> "Каталог не вернул модель видео. Обновите каталог и попробуйте снова."
                    else -> "Каталог не вернул аудио-модель. Обновите каталог и попробуйте снова."
                },
                MediaErrorCategory.Provider,
            )
            MediaLog.w("Generate refused: no $kind model available")
            return
        }
        // Local engines synthesize offline: no API key, no provider job (spec §14).
        if (model.isLocalTts) {
            generateLocalTts(model, prompt, applyMediaOptDefaults(opts, model, null))
            return
        }
        val key = apiKeyProvider()
        if (key.isBlank()) {
            _error.value = MediaStudioError.fromInfo(mediaMissingKeyError())
            MediaLog.w("Generate refused: no API key configured")
            return
        }
        val references = _references.value
        // Explicit opt defaults (spec §19.1, §19.2): quality is always sent for
        // selector models (generation and editing alike), the sound toggle rides
        // its catalog default. The journal keeps exactly these opts, so `repeat`
        // resubmits the same combination.
        val finalOpts = applyMediaOptDefaults(opts, model, appSettings.getMediaQuality(model.id))
        pendingGeneration = PendingGeneration(prompt, finalOpts, kind)
        MediaLog.i("Generate: kind=$kind model=${model.id} opts=${finalOpts.keys} refs=${references.size}")
        val entry = MediaHistoryEntry(
            localId = Uuid.random().toString(),
            createdAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            isVideo = kind == MediaKind.Video,
            isAudio = kind == MediaKind.Audio || kind == MediaKind.Music,
            model = model.id,
            prompt = prompt,
            opts = finalOpts,
            references = references.mapNotNull { it.toHistoryReference() },
            droppedReferences = references.count { it.toHistoryReference() == null },
        )
        history.upsert(entry)
        _isStarting.value = true
        viewModelScope.launch {
            try {
                submitGeneration(key, entry.localId, model.id, prompt, finalOpts, references, kind)
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
        kind: MediaKind,
        savedQuoteId: String? = null,
    ) {
        // An empty list measures nothing: skipping the hop keeps the whole submit on the
        // caller's dispatcher, so nothing has to wait on the real-time pool.
        val oversized = if (references.isEmpty()) null else withContext(Dispatchers.Default) { oversizedReferenceIndex(references) }
        if (oversized != null) {
            MediaLog.w("Reference ${oversized + 1} exceeds $MAX_INLINE_REFERENCE_BYTES bytes")
            history.remove(localId)
            _error.value = MediaStudioError(
                "Референс слишком большой",
                "Референс ${oversized + 1} больше 8 МБ. Сожмите его, чтобы отправить.",
                MediaErrorCategory.File,
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
                kind = kind,
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

    private fun journalFailureText(error: Throwable): String {
        val info = mediaErrorInfo(error)
        return "${info.title}. ${info.detail}"
    }

    /**
     * Offline synthesis (spec §14): the platform engine writes a WAV into app
     * storage, the journal gets a «локально» entry without a provider, and the
     * result lands on the canvas like any audio element. No key, no billing,
     * no polling — the journal entry is persisted once, in its terminal state.
     */
    private fun generateLocalTts(model: MediaModel, prompt: String, opts: Map<String, String>) {
        if (_isLocalSynthesizing.value) return
        val synthesizer = createLocalTtsSynthesizer(model.id)
        if (synthesizer == null) {
            _error.value = MediaStudioError(
                "Локальный синтез недоступен",
                "Эта платформа не умеет синтезировать речь в файл.",
                MediaErrorCategory.Other,
            )
            return
        }
        val speed = opts["speed"]?.toFloatOrNull() ?: 1f
        val localId = Uuid.random().toString()
        val path = "media/local/$localId.wav"
        _isLocalSynthesizing.value = true
        MediaLog.i("Local TTS: model=${model.id} chars=${prompt.length} speed=$speed")
        viewModelScope.launch {
            val bytes = runCatching { synthesizer.synthesizeToWav(prompt, speed) }.getOrNull()
            val saved = bytes != null && com.pusios.pots.writeAppStorageBytes(path, bytes)
            _isLocalSynthesizing.value = false
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!saved) {
                MediaLog.w("Local TTS failed: model=${model.id}")
                history.upsert(
                    MediaHistoryEntry(
                        localId = localId,
                        createdAtMs = now,
                        isVideo = false,
                        isAudio = true,
                        model = model.id,
                        provider = LOCAL_TTS_PROVIDER_MARK,
                        prompt = prompt,
                        opts = opts,
                        status = MediaHistoryStatus.Failed,
                        failMsg = "Не удалось синтезировать речь локально. Проверь голоса устройства и попробуй ещё раз.",
                    ),
                )
                _error.value = MediaStudioError(
                    "Не удалось синтезировать речь",
                    "Проверьте голоса устройства и попробуйте ещё раз.",
                    MediaErrorCategory.Other,
                )
                return@launch
            }
            history.upsert(
                MediaHistoryEntry(
                    localId = localId,
                    createdAtMs = now,
                    isVideo = false,
                    isAudio = true,
                    model = model.id,
                    provider = LOCAL_TTS_PROVIDER_MARK,
                    prompt = prompt,
                    opts = opts,
                    status = MediaHistoryStatus.Success,
                    localPath = path,
                    priceRub = 0.0,
                ),
            )
            importFromGallery(path, isVideo = false, isAudio = true)
            MediaLog.i("Local TTS ready: ${bytes!!.size} bytes at $path")
        }
    }

    /**
     * Repeats an entry with its original references and prompt. A failed submit keeps its
     * idempotency key, so the provider cannot charge twice; a finished job gets a new key.
     */
    fun repeat(localId: String) {
        val source = history.entries.value.firstOrNull { it.localId == localId } ?: return
        if (!source.canRepeat || isGenerationBusy()) return
        // Local entries repeat offline — no key, no provider.
        if (source.model.isLocalTtsId()) {
            val model = com.pusios.pots.media.localTtsCatalog().firstOrNull { it.id == source.model } ?: return
            generateLocalTts(model, source.prompt, source.opts)
            return
        }
        val key = apiKeyProvider()
        if (key.isBlank()) {
            _error.value = MediaStudioError.fromInfo(mediaMissingKeyError())
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
                submitGeneration(
                    key,
                    newEntry.localId,
                    source.model,
                    source.prompt,
                    source.opts,
                    references,
                    kind = source.mediaKind(),
                    savedQuoteId = newEntry.quoteId,
                )
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
                _error.value = MediaStudioError(
                    "Файл ещё не скачан",
                    "Результат не сохранился локально. Попробуйте через минуту.",
                    MediaErrorCategory.File,
                )
                return@launch
            }
            saveFileToDevice(bytes, "pots-${entry.localId.take(8)}", galleryExtension(entry.localPath, entry.isVideo, entry.isAudio))
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
            kind = when {
                entry.isAudio -> MediaKind.Audio
                entry.isVideo -> MediaKind.Video
                else -> MediaKind.Image
            },
            resultUrls = listOfNotNull(entry.resultUrl),
            localPath = entry.localPath,
        )
        if (_elements.value.none { it is MediaLayer && it.jobId == state.jobId }) addElement(state)
    }

    /** True while a submit is in flight, a job is still running, or a local synthesis is busy; the create button stays locked meanwhile. */
    fun isGenerationBusy(): Boolean = _isStarting.value || _isLocalSynthesizing.value || _jobs.value.values.any { !it.isTerminal } || history.entries.value.any { it.isActive }

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
                _error.value = MediaStudioError(
                    "Не удалось сжать референс",
                    "Файл всё ещё больше 8 МБ. Выберите файл поменьше.",
                    MediaErrorCategory.File,
                )
                return@launch
            }
            val newPath = "media/refs/${Uuid.random()}.jpg"
            if (!com.pusios.pots.writeAppStorageBytes(newPath, compressed)) {
                _error.value = MediaStudioError.fromInfo(mediaStorageError())
                return@launch
            }
            _references.update { list ->
                list.mapIndexed { i, ref -> if (i == index) ref.copy(localPath = newPath) else ref }
            }
            MediaLog.i("Reference ${index + 1} compressed: ${source.size} -> ${compressed.size} bytes")
            pendingGeneration?.let { generate(it.prompt, it.opts, it.kind) }
        }
    }

    /** Saved choice wins; otherwise auto-pick and persist (audio uses the research defaults per flow). */
    private fun resolveModel(kind: MediaKind, instanceId: String): MediaModel? {
        if (kind == MediaKind.Audio || kind == MediaKind.Music) {
            val flow = _audioFlow.value
            // TTS candidates include this platform's local engines (spec §14).
            val candidates = audioFlowModelsWithLocal(_models.value, flow, hasSunoHistory())
            val savedId = if (flow == AudioFlow.Tts) _selectedTtsModelId.value else _selectedMusicModelId.value
            val saved = candidates.firstOrNull { it.id == savedId }
            if (saved != null) return saved
            val fallback = defaultAudioModel(_models.value, flow) ?: return null
            selectAudioModel(fallback.id, flow)
            MediaLog.i("Auto-selected audio model for flow ${flow.storageKey}: ${fallback.id}")
            return fallback
        }
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

    private fun hasSunoHistory(): Boolean = history.entries.value.any { it.model == "suno" }

    fun selectImageModel(modelId: String) {
        appSettings.setInstanceMediaImageModelId(instanceIdProvider(), modelId)
        _selectedImageModelId.value = modelId
    }

    fun selectVideoModel(modelId: String) {
        appSettings.setInstanceMediaVideoModelId(instanceIdProvider(), modelId)
        _selectedVideoModelId.value = modelId
    }

    fun selectAudioModel(modelId: String, flow: AudioFlow = _audioFlow.value) {
        persistAudioModelId(flow, modelId)
        if (flow == AudioFlow.Tts) _selectedTtsModelId.value = modelId else _selectedMusicModelId.value = modelId
    }

    private fun persistAudioModelId(flow: AudioFlow, modelId: String) {
        if (flow == AudioFlow.Tts) {
            appSettings.setInstanceMediaAudioTtsModelId(instanceIdProvider(), modelId)
        } else {
            appSettings.setInstanceMediaAudioMusicModelId(instanceIdProvider(), modelId)
        }
    }

    /** Remembers the audio flow; the next first tap enables audio with it (spec §13). */
    fun selectAudioFlow(flow: AudioFlow) {
        if (_audioFlow.value == flow) return
        appSettings.setMediaAudioFlow(flow)
        _audioFlow.value = flow
        MediaLog.i("Audio flow selected: ${flow.storageKey}")
    }

    /** The image quality selection per model (spec §19.1): one control for generation and editing. */
    fun selectQuality(modelId: String, quality: String) {
        appSettings.setMediaQuality(modelId, quality)
    }

    fun savedQuality(modelId: String): String? = appSettings.getMediaQuality(modelId)

    // ── Voice cloning «Мой голос» (spec §16; cloud-only, provider voices API) ──

    /** The voices API shares the media contract; the VM keeps its own adapter. */
    private val voiceApi = PlusVibeMediaProvider()

    /** Idempotent-create state: one key per attempt, reused while the body is identical. */
    private var voiceCreateFingerprint = ""
    private var voiceCreateKey = ""

    /** Loads the account's cloned voices; failures surface as a snackbar. */
    fun loadMyVoices() {
        val key = apiKeyProvider()
        if (key.isBlank()) {
            _error.value = MediaStudioError.fromInfo(mediaMissingKeyError())
            return
        }
        viewModelScope.launch {
            runCatching { voiceApi.listVoices(key) }
                .onSuccess { _myVoices.value = it }
                .onFailure { e ->
                    MediaLog.w("Load voices failed: ${e.message}")
                    _error.value = voiceErrorUi(e)
                }
        }
    }

    /**
     * Creates a cloned voice. [samplePath] must already pass
     * [com.pusios.pots.media.validateVoiceSample]; consent is mandatory (the
     * UI blocks the button until it is ticked). [onDone] reports success so
     * the sheet can close. Retries of the same attempt reuse one idempotency
     * key, so a duplicate voice is never created.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun createMyVoice(name: String, level: VoiceLevel, samplePath: String, consent: Boolean, onDone: (Boolean) -> Unit) {
        if (_isCreatingVoice.value) return
        if (!consent) {
            _error.value = MediaStudioError(
                "Нужно согласие",
                "Подтвердите, что это ваш голос или у вас есть право его использовать.",
                MediaErrorCategory.Other,
            )
            onDone(false)
            return
        }
        val engine = level.engine
        if (engine == null) {
            _error.value = MediaStudioError(
                "Уровень пока недоступен",
                "Провайдер не задокументировал движок создания для этого уровня.",
                MediaErrorCategory.Provider,
            )
            onDone(false)
            return
        }
        val key = apiKeyProvider()
        if (key.isBlank()) {
            _error.value = MediaStudioError.fromInfo(mediaMissingKeyError())
            onDone(false)
            return
        }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.Default) { readAppStorageBytes(samplePath) }
            if (bytes == null) {
                _error.value = MediaStudioError(
                    "Файл сэмпла не найден",
                    "Выберите запись заново.",
                    MediaErrorCategory.File,
                )
                onDone(false)
                return@launch
            }
            val mime = if (samplePath.endsWith(".mp3", ignoreCase = true)) "audio/mpeg" else "audio/wav"
            val dataUri = "data:$mime;base64," + Base64.encode(bytes)
            val fingerprint = "$name|$engine|$samplePath|${bytes.size}"
            if (voiceCreateFingerprint != fingerprint || voiceCreateKey.isEmpty()) {
                voiceCreateFingerprint = fingerprint
                voiceCreateKey = PlusVibeMediaProvider.newIdempotencyKey()
            }
            _isCreatingVoice.value = true
            MediaLog.i("Create voice: name=$name level=$level sample=${bytes.size} bytes")
            runCatching { voiceApi.createVoice(key, name, engine, dataUri, voiceCreateKey) }
                .onSuccess { voice ->
                    voiceCreateKey = ""
                    voiceCreateFingerprint = ""
                    _myVoices.value = _myVoices.value.orEmpty().filterNot { it.id == voice.id } + voice
                    onDone(true)
                }
                .onFailure { e ->
                    MediaLog.w("Create voice failed: ${e.message}")
                    _error.value = voiceErrorUi(e)
                    onDone(false)
                }
            _isCreatingVoice.value = false
        }
    }

    /** Deletes a cloned voice after the UI confirms with the voice name. */
    fun deleteMyVoice(voiceId: String) {
        val key = apiKeyProvider()
        if (key.isBlank()) {
            _error.value = MediaStudioError.fromInfo(mediaMissingKeyError())
            return
        }
        viewModelScope.launch {
            runCatching { voiceApi.deleteVoice(key, voiceId) }
                .onSuccess { _myVoices.value = _myVoices.value.orEmpty().filterNot { it.id == voiceId } }
                .onFailure { e ->
                    MediaLog.w("Delete voice failed: ${e.message}")
                    _error.value = voiceErrorUi(e)
                }
        }
    }

    private fun voiceErrorUi(e: Throwable): MediaStudioError = when (e) {
        is VoiceException -> MediaStudioError(
            e.userMessageRu(),
            category = if (e is VoiceException.KeyProblem) MediaErrorCategory.Key else MediaErrorCategory.Provider,
            isKeyProblem = e is VoiceException.KeyProblem,
        )

        else -> MediaStudioError(
            "Не удалось выполнить операцию с голосом",
            "Проверьте связь и попробуйте ещё раз.",
            MediaErrorCategory.Network.takeIf { isNetworkFailure(e) } ?: MediaErrorCategory.Provider,
        )
    }

    /** Cached price tables for the quality dropdown; null means the note mode (spec §19.1). */
    private val _priceTables = MutableStateFlow<Map<String, MediaPriceTable?>>(emptyMap())
    val priceTables: StateFlow<Map<String, MediaPriceTable?>> = _priceTables.asStateFlow()

    /** Fetches the price table once per model; failures stay cached (no retry storm on every open). */
    fun loadPriceTable(modelId: String) {
        if (_priceTables.value.containsKey(modelId)) return
        val key = apiKeyProvider()
        viewModelScope.launch {
            val table = repository.priceTable(key, modelId)
            _priceTables.update { it + (modelId to table) }
        }
    }

    private fun loadDrafts(): Map<String, String> = MediaMode.entries
        .flatMap { mode -> AudioFlow.entries.map { flow -> mediaDraftKey(mode, flow) } }
        .distinct()
        .associateWith { appSettings.getMediaDraft(it) }

    /** Saves the prompt draft of one mode+flow pair; survives switching and errors. */
    fun setDraft(mode: MediaMode, flow: AudioFlow, text: String) {
        val key = mediaDraftKey(mode, flow)
        _drafts.update { it + (key to text) }
        appSettings.setMediaDraft(key, text)
    }

    // -- Projects (spec §12): autosave, manual save, import, export ------------

    /** Autosave (spec §12): ~2 s after the last change, into the active snapshot, no modals. */
    @OptIn(FlowPreview::class)
    private fun startAutosave() {
        if (autosaveStarted) return
        autosaveStarted = true
        val canvasSide = combine(_elements, _sequence, _mode, _params, _audioFlow) { _, _, _, _, _ -> 0 }
        val settingsSide = combine(_drafts, _selectedImageModelId, _selectedVideoModelId, _selectedTtsModelId, _selectedMusicModelId) { _, _, _, _, _ -> 0 }
        viewModelScope.launch {
            combine(canvasSide, settingsSide) { _, _ -> 0 }
                .drop(1)
                .debounce(AUTOSAVE_DEBOUNCE_MS)
                .collect { saveActiveSnapshot() }
        }
    }

    private fun saveActiveSnapshot() {
        val saved = projectStore.save(MediaProjectStore.ACTIVE_ID, snapshotProject(activeProjectName, activeProjectCreatedAtMs))
        _projectSaved.value = saved
        if (!saved) MediaLog.w("Project autosave failed: storage write refused")
    }

    /** The full studio state as one serializable file (spec §12). */
    private fun snapshotProject(name: String, createdAtMs: Long): MediaProjectFile {
        val sequenceState = _sequence.value
        return MediaProjectFile(
            schemaVersion = MEDIA_PROJECT_SCHEMA_VERSION,
            name = name,
            createdAtMs = createdAtMs,
            updatedAtMs = nowMs(),
            // The list order is the z-order; each element is written with its
            // own index as `zIndex` so schema-1 readers keep the same stacking.
            elements = _elements.value.toProjectElements(),
            sequence = MediaProjectSequence(
                clips = sequenceState.clips.map { clip ->
                    MediaProjectSequenceClip(clip.elementId, clip.relPath, clip.isVideo, clip.durationMs)
                },
                transitions = sequenceState.transitions.map { it.name },
            ),
            settings = MediaProjectSettings(
                mode = _mode.value.name,
                audioFlow = _audioFlow.value.storageKey,
                imageModelId = _selectedImageModelId.value,
                videoModelId = _selectedVideoModelId.value,
                ttsModelId = _selectedTtsModelId.value,
                musicModelId = _selectedMusicModelId.value,
                params = _params.value,
                drafts = _drafts.value.filterValues { it.isNotBlank() },
            ),
        )
    }

    /** Restores the last session from the active snapshot. */
    private fun restoreActiveProject() {
        val project = projectStore.load(MediaProjectStore.ACTIVE_ID) ?: return
        applyProject(project)
        MediaLog.i("Active project restored: elements=${project.elements.size} clips=${project.sequence.clips.size}")
    }

    /** Applies a project to the studio state; open and import share this path. */
    private fun applyProject(project: MediaProjectFile) {
        val settings = project.settings
        _selectedId.value = null
        _elements.value = project.elements.toCanvasLayers()
        val (clips, transitions) = projectSequenceToClips(project.sequence)
        _sequence.value = MediaSequenceState(clips, transitions)
        _mode.value = mediaModeFromProject(settings.mode)
        val flow = AudioFlow.fromStorage(settings.audioFlow)
        appSettings.setMediaAudioFlow(flow)
        _audioFlow.value = flow
        _params.value = settings.params
        settings.drafts.forEach { (key, text) -> appSettings.setMediaDraft(key, text) }
        _drafts.value = loadDrafts()
        // Model selections ride the project; the catalog load prunes ids that no longer exist.
        val instanceId = instanceIdProvider()
        _selectedImageModelId.value = settings.imageModelId
        appSettings.setInstanceMediaImageModelId(instanceId, settings.imageModelId)
        _selectedVideoModelId.value = settings.videoModelId
        appSettings.setInstanceMediaVideoModelId(instanceId, settings.videoModelId)
        _selectedTtsModelId.value = settings.ttsModelId
        appSettings.setInstanceMediaAudioTtsModelId(instanceId, settings.ttsModelId)
        _selectedMusicModelId.value = settings.musicModelId
        appSettings.setInstanceMediaAudioMusicModelId(instanceId, settings.musicModelId)
        activeProjectName = project.name
        activeProjectCreatedAtMs = project.createdAtMs
    }

    /** Manual save (spec §12): a named copy of the current state appears in «Проекты». */
    fun saveProjectNow(): Boolean {
        val project = snapshotProject(activeProjectName, activeProjectCreatedAtMs)
        val id = Uuid.random().toString()
        val saved = projectStore.save(id, project)
        if (saved) {
            _projectSaved.value = true
            refreshProjects()
            MediaLog.i("Project saved: $id (${project.elements.size} elements)")
        } else {
            _error.value = MediaStudioError.fromInfo(mediaStorageError())
        }
        return saved
    }

    fun openProject(id: String) {
        val project = projectStore.load(id) ?: return
        applyProject(project)
        MediaLog.i("Project opened: $id (${project.elements.size} elements)")
    }

    /**
     * Block 6 (owner): «+» in the project sheet — start a new empty project.
     * The current one is saved first with an EXPLICIT call, not the autosave
     * timer (@linus, spec §12): a dirty canvas whose debounce never fired must
     * not be lost silently. A failed save aborts the reset (the error card is
     * already up); a pristine empty canvas saves nothing to avoid junk entries.
     */
    fun newProject() {
        val dirty = _elements.value.isNotEmpty() || _sequence.value.clips.isNotEmpty()
        if (dirty && !saveProjectNow()) return
        _selectedId.value = null
        _elements.value = emptyList()
        _sequence.value = MediaSequenceState()
        activeProjectName = defaultProjectName(nowMs())
        activeProjectCreatedAtMs = nowMs()
        saveActiveSnapshot()
        refreshProjects()
        MediaLog.i("New project started (canvas cleared)")
    }

    fun deleteProject(id: String) {
        if (projectStore.delete(id)) refreshProjects()
    }

    private fun refreshProjects() {
        _projects.value = projectStore.list()
    }

    /** The `.potsproj` bytes of a saved project for the platform file saver. */
    fun exportProject(id: String): Pair<String, ByteArray>? {
        val project = projectStore.load(id) ?: return null
        return project.name to encodeMediaProject(project).encodeToByteArray()
    }

    /** The `.potsproj` bytes of the current state (export without a manual save). */
    fun exportActiveProject(): Pair<String, ByteArray> {
        val project = snapshotProject(activeProjectName, activeProjectCreatedAtMs)
        return project.name to encodeMediaProject(project).encodeToByteArray()
    }

    /** Import (spec §12): opens as a new project; existing projects stay untouched. */
    fun importProject(bytes: ByteArray): Boolean {
        val project = try {
            decodeMediaProject(bytes.decodeToString())
        } catch (e: MediaProjectException) {
            MediaLog.w("Project import refused: ${e.message}")
            _error.value = MediaStudioError(e.title, e.detail, MediaErrorCategory.File)
            return false
        }
        val id = Uuid.random().toString()
        if (!projectStore.save(id, project)) {
            _error.value = MediaStudioError.fromInfo(mediaStorageError())
            return false
        }
        applyProject(project)
        refreshProjects()
        MediaLog.i("Project imported: $id (${project.elements.size} elements)")
        return true
    }

    /** The picked file could not be read at all (before the project codec sees it). */
    fun reportProjectFileUnreadable() {
        _error.value = MediaStudioError(
            "Файл не читается",
            "Не удалось прочитать выбранный файл. Попробуйте ещё раз.",
            MediaErrorCategory.File,
        )
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    fun addElement(state: MediaJobState) {
        val url = state.resultUrls.firstOrNull() ?: return
        val isAudio = state.kind == MediaKind.Audio || state.kind == MediaKind.Music || AUDIO_EXTENSIONS.any { url.endsWith(it, ignoreCase = true) }
        val isVideo = !isAudio && (state.kind == MediaKind.Video || url.endsWith(".mp4"))
        val id = Uuid.random().toString()
        _elements.update { list ->
            // New layers land on top: appending to the draw list is the top.
            list + mediaLayer(
                id = id,
                jobId = state.jobId,
                url = url,
                localPath = state.localPath,
                isVideo = isVideo,
                isAudio = isAudio,
                x = list.size * 32f,
                y = list.size * 32f,
            )
        }
        if (isVideo) probeAspectFor(id, state.localPath ?: url)
    }

    /** The one place that turns "which kind of result is this" into a layer type. */
    private fun mediaLayer(
        id: String,
        jobId: String?,
        url: String?,
        localPath: String?,
        isVideo: Boolean,
        isAudio: Boolean,
        x: Float,
        y: Float,
    ): CanvasLayer {
        val transform = LayerTransform(x = x, y = y)
        return when {
            isAudio -> AudioLayer(id = id, transform = transform, jobId = jobId, url = url, localPath = localPath)
            isVideo -> VideoLayer(id = id, transform = transform, jobId = jobId, url = url, localPath = localPath)
            else -> ImageLayer(id = id, transform = transform, jobId = jobId, url = url, localPath = localPath)
        }
    }

    /**
     * Block 4: a video element's box keeps the frame proportions (@joni's
     * contract) — probe the source once when the element lands and patch the
     * element on success. Off the calling thread, failure degrades to null
     * (the canvas falls back to its default box); never blocks the landing.
     */
    private fun probeAspectFor(elementId: String, source: String?) {
        if (source.isNullOrBlank()) return
        viewModelScope.launch {
            val aspect = runCatching { videoAspectProber(source) }.getOrNull() ?: return@launch
            _elements.update { list ->
                list.map { if (it.id == elementId) it.withProbedAspect(aspect) else it }
            }
        }
    }

    fun move(id: String, dx: Float, dy: Float) {
        _elements.update { list ->
            list.map { if (it.id == id) it.moved(dx, dy) else it }
        }
    }

    fun scale(id: String, factor: Float) {
        _elements.update { list ->
            list.map { if (it.id == id) it.scaled(factor) else it }
        }
    }

    fun rotate(id: String, degrees: Float) {
        _elements.update { list ->
            list.map { if (it.id == id) it.rotated(degrees) else it }
        }
    }

    fun select(id: String?) {
        _selectedId.value = id
    }

    /**
     * Adds an empty text layer on top of the draw list and returns it, so the
     * screen can open the editor on it right away. [x]/[y] are the box's
     * top-left corner in canvas px — the screen centres it in the visible area.
     */
    fun addTextLayer(x: Float, y: Float): TextLayer {
        val layer = newTextLayer(Uuid.random().toString(), x, y)
        _elements.update { list -> list + layer }
        return layer
    }

    /**
     * Changes one text layer in place: text, font, size, colour, alignment and
     * box width all arrive through here. One hook instead of six setters — the
     * layer is immutable, so a change is a `copy`.
     */
    fun editText(id: String, change: (TextLayer) -> TextLayer) {
        _elements.update { list -> list.map { if (it is TextLayer && it.id == id) change(it) else it } }
    }

    /** Collapsed strip ↔ open list; remembered in `MediaSettings`, not in the project. */
    fun setLayersPanelOpen(open: Boolean) {
        _layersPanelOpen.value = open
        appSettings.setMediaLayersPanelOpen(open)
    }

    /**
     * Panel reorder: the draw list is the z-order, so this is a list move. Both
     * indices are draw-order; the panel converts from its reversed view. The
     * saved `zIndex` is the index (`toProjectElements`), so the order is dense
     * 0…n−1 by construction — nothing to renumber.
     */
    fun reorderLayer(fromDrawIndex: Int, toDrawIndex: Int) {
        _elements.update { it.movedLayer(fromDrawIndex, toDrawIndex) }
    }

    fun setLayerVisible(id: String, visible: Boolean) {
        _elements.update { list -> list.map { if (it.id == id) it.withVisible(visible) else it } }
    }

    /** Layer-wide opacity; the canvas composites the whole layer with this alpha. */
    fun setLayerOpacity(id: String, opacity: Float) {
        _elements.update { list -> list.map { if (it.id == id) it.withOpacity(opacity) else it } }
    }

    // -- Drawing on the canvas (draw mode) ------------------------------------
    // The strokes are ordinary layers: they take part in the z-order, the
    // opacity, the visibility, the autosave and (later) the export. Nothing is
    // baked into a PNG, so a stroke can still be removed, hidden or faded after
    // it was drawn.

    /** Starts a drawing session: the counter resets and undo cannot reach older work. */
    fun beginDrawSession() {
        _drawSession.value = DrawSession()
    }

    /** Ends the session; the strokes stay on the canvas as layers. */
    fun endDrawSession() {
        _drawSession.value = DrawSession()
    }

    /**
     * Puts one finished stroke on top of the canvas and remembers it for undo.
     * An empty stroke (a tap that never landed a point) is dropped.
     */
    fun addStroke(points: List<Offset>, colorArgb: Int, widthPx: Float) {
        if (points.isEmpty()) return
        val layer = StrokeLayer(
            id = Uuid.random().toString(),
            points = points,
            colorArgb = colorArgb,
            widthPx = widthPx,
        )
        // New ink lands on top: appending to the draw list is the top.
        _elements.update { it.withStroke(layer) }
        _drawSession.update { it.with(layer.id) }
    }

    /** Removes the last stroke of this session; earlier work is out of reach. */
    fun undoLastStroke() {
        val id = _drawSession.value.strokeIds.lastOrNull() ?: return
        _elements.update { it.withoutStrokes(setOf(id)) }
        _drawSession.update { it.undone() }
    }

    /**
     * The eraser: whole strokes the finger crossed, removed from the canvas.
     * Cutting a hole in a vector line would mean splitting it into pieces and
     * rewriting its points — and the layer model has no notion of a gap.
     */
    fun eraseStrokesAt(point: Offset) {
        val hits = _elements.value.filter { it.isStrokeHitBy(point) }.map { it.id }.toSet()
        if (hits.isEmpty()) return
        _elements.update { it.withoutStrokes(hits) }
        _drawSession.update { it.without(hits) }
    }

    fun applyAction(id: String, action: CanvasAction) {
        when (action) {
            CanvasAction.Delete -> _elements.update { it.filterNot { e -> e.id == id } }

            CanvasAction.Duplicate -> _elements.update { list ->
                val index = list.indexOfFirst { it.id == id }
                if (index < 0) return@update list
                // The copy sits right above its source — that is the layer the
                // old `zIndex = source + 1` was trying to name.
                list.toMutableList().apply { add(index + 1, list[index].duplicated(Uuid.random().toString())) }
            }

            // Draw order is the list itself: «выше/ниже по слоям» are list moves,
            // so there is no arithmetic left to drift or to collide.
            CanvasAction.BringForward -> _elements.update { it.toTop(id) }

            CanvasAction.SendBack -> _elements.update { it.toBottom(id) }

            CanvasAction.UseAsReference -> {
                val source = _elements.value.firstOrNull { it.id == id } as? MediaLayer ?: return
                val url = source.url?.takeIf { it.startsWith("https://") }
                val localPath = source.localPath
                if (url == null && localPath == null) return
                val reference = MediaReference(url = url, localPath = localPath, isVideo = source is VideoLayer)
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
        MediaLog.e(prefix, cause)
        _error.value = MediaStudioError.fromInfo(mediaErrorInfo(cause))
    }

    /** Add an imported gallery asset straight to the canvas (bytes already cached). */
    fun importFromGallery(localPath: String, isVideo: Boolean, bytes: ByteArray? = null, isAudio: Boolean = false) {
        if (bytes != null) com.pusios.pots.writeAppStorageBytes(localPath, bytes)
        val id = Uuid.random().toString()
        _elements.update { list ->
            list + mediaLayer(
                id = id,
                jobId = null,
                url = null,
                localPath = localPath,
                isVideo = isVideo,
                isAudio = isAudio,
                x = list.size * 32f,
                y = list.size * 32f,
            )
        }
        if (isVideo) probeAspectFor(id, localPath)
    }

    /**
     * Saves an edited audio result (always WAV) under `media/edits/` and
     * places it as a new audio element. The source element is never touched.
     * Returns false when the storage write fails (surfaced as ExportFailed).
     */
    fun addEditedAudioElement(bytes: ByteArray): Boolean {
        val path = "media/edits/${Uuid.random()}.wav"
        if (!com.pusios.pots.writeAppStorageBytes(path, bytes)) {
            MediaLog.e("Audio edit export failed: storage write refused for $path")
            return false
        }
        importFromGallery(path, isVideo = false, isAudio = true)
        MediaLog.d("Audio edit saved: $path (${bytes.size} bytes)")
        return true
    }

    /**
     * Saves an edited image result (always PNG) under `media/edits/` and
     * places it as a new canvas element. The source element is never touched.
     * Returns false when the storage write fails (surfaced as ExportFailed).
     */
    fun addEditedImageElement(pngBytes: ByteArray): Boolean {
        val path = "media/edits/${Uuid.random()}.png"
        if (!com.pusios.pots.writeAppStorageBytes(path, pngBytes)) {
            MediaLog.e("Image edit export failed: storage write refused for $path")
            return false
        }
        importFromGallery(path, isVideo = false)
        MediaLog.d("Image edit saved: $path (${pngBytes.size} bytes)")
        return true
    }

    /**
     * Places an edited video result as a new canvas element. The file was
     * already written by the video processor under `media/edits/`; the source
     * element is never touched. Returns false when the file is missing.
     */
    fun addEditedVideoElement(relativePath: String): Boolean {
        if (!relativePath.startsWith("media/edits/")) {
            MediaLog.e("Video edit export refused: unexpected path $relativePath")
            return false
        }
        importFromGallery(relativePath, isVideo = true)
        MediaLog.d("Video edit saved: $relativePath")
        return true
    }

    // -- Video sequence assembly (spec §7) -----------------------------------

    /** Appends a canvas element to the sequence; video durations are probed by the sheet. */
    fun addSequenceClip(element: MediaLayer, durationMs: Long): Boolean {
        val relPath = element.localPath ?: return false
        if (element is AudioLayer) return false
        val clip = SequenceClip(elementId = element.id, relPath = relPath, isVideo = element is VideoLayer, durationMs = durationMs)
        _sequence.update { state ->
            val (clips, transitions) = sequenceAdd(state.clips, state.transitions, clip)
            MediaSequenceState(clips, transitions)
        }
        return true
    }

    fun removeSequenceClip(index: Int) = _sequence.update { state ->
        val (clips, transitions) = sequenceRemoveAt(state.clips, state.transitions, index)
        MediaSequenceState(clips, transitions)
    }

    fun moveSequenceClip(from: Int, to: Int) = _sequence.update { state ->
        val (clips, transitions) = sequenceMove(state.clips, state.transitions, from, to)
        MediaSequenceState(clips, transitions)
    }

    /** Image clips change screen time; video clips always play full length. */
    fun setSequenceClipDuration(index: Int, durationMs: Long) = _sequence.update { state ->
        state.copy(
            clips = state.clips.mapIndexed { i, clip ->
                if (i == index && !clip.isVideo) clip.copy(durationMs = durationMs) else clip
            },
        )
    }

    fun setSequenceTransition(index: Int, transition: SequenceTransition) = _sequence.update { state ->
        state.copy(transitions = state.transitions.mapIndexed { i, current -> if (i == index) transition else current })
    }

    fun clearSequence() {
        _sequence.value = MediaSequenceState()
    }

    /**
     * Result of a local assembly (the §7 export): the file is already under
     * `media/edits/`, the journal gets a «локально» 0 ₽ entry persisted once
     * in its terminal state (the local-TTS precedent), and the clip lands on
     * the canvas. Returns false when the path is unexpected.
     */
    fun addAssembledVideoElement(
        relPath: String,
        clipCount: Int,
        durationMs: Long,
        withAudio: Boolean,
        withSubtitles: Boolean,
    ): Boolean {
        if (!relPath.startsWith("media/edits/")) {
            MediaLog.e("Sequence export refused: unexpected path $relPath")
            return false
        }
        val localId = Uuid.random().toString()
        history.upsert(
            MediaHistoryEntry(
                localId = localId,
                createdAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                isVideo = true,
                model = "video-assembly",
                provider = LOCAL_TTS_PROVIDER_MARK,
                prompt = "Сборка видео из $clipCount клипов · ${durationMs / 1000} с" +
                    (if (withSubtitles) " · субтитры" else "") +
                    (if (withAudio) " · звук" else ""),
                opts = mapOf(
                    "clips" to clipCount.toString(),
                    "duration_ms" to durationMs.toString(),
                    "audio" to withAudio.toString(),
                    "subtitles" to withSubtitles.toString(),
                ),
                status = MediaHistoryStatus.Success,
                localPath = relPath,
                priceRub = 0.0,
            ),
        )
        importFromGallery(relPath, isVideo = true)
        MediaLog.d("Video sequence assembled: $relPath ($clipCount clips)")
        return true
    }

    /** Failed assemblies also land in the journal — the local-TTS honesty precedent. */
    fun recordAssemblyFailure(clipCount: Int, reason: String) {
        history.upsert(
            MediaHistoryEntry(
                localId = Uuid.random().toString(),
                createdAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                isVideo = true,
                model = "video-assembly",
                provider = LOCAL_TTS_PROVIDER_MARK,
                prompt = "Сборка видео из $clipCount клипов",
                status = MediaHistoryStatus.Failed,
                failMsg = "Не удалось собрать видео. $reason",
                priceRub = 0.0,
            ),
        )
    }

    // -- Audio-reactive clip (spec §11) ----------------------------------------

    /**
     * Result of the audio-reactive clip (spec §11): the file is already under
     * `media/edits/`, the journal gets the «клип, 0 ₽» entry (deterministic
     * local flow, no LLM, no quote), and the clip lands on the canvas.
     * Returns false when the path is unexpected.
     */
    fun addAudioClipElement(relPath: String, style: AudioClipStyle, trackDurationMs: Long): Boolean {
        if (!relPath.startsWith("media/edits/")) {
            MediaLog.e("Audio clip export refused: unexpected path $relPath")
            return false
        }
        history.upsert(
            MediaHistoryEntry(
                localId = Uuid.random().toString(),
                createdAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                isVideo = true,
                model = "audio-clip",
                provider = LOCAL_TTS_PROVIDER_MARK,
                prompt = "Аудио-реактивный клип · ${style.labelRu} · трек ${trackDurationMs / 1000} с",
                opts = mapOf("style" to style.name, "track_ms" to trackDurationMs.toString()),
                status = MediaHistoryStatus.Success,
                localPath = relPath,
                priceRub = 0.0,
            ),
        )
        importFromGallery(relPath, isVideo = true)
        MediaLog.d("Audio-reactive clip assembled: $relPath (style=${style.name})")
        return true
    }

    /** Failed clip builds also land in the journal — the local-TTS honesty precedent. */
    fun recordAudioClipFailure(reason: String) {
        history.upsert(
            MediaHistoryEntry(
                localId = Uuid.random().toString(),
                createdAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                isVideo = true,
                model = "audio-clip",
                provider = LOCAL_TTS_PROVIDER_MARK,
                prompt = "Аудио-реактивный клип",
                status = MediaHistoryStatus.Failed,
                failMsg = "Не удалось собрать клип. $reason",
                priceRub = 0.0,
            ),
        )
    }

    /** Save a canvas element to the device gallery via platform writer. */
    fun exportToGallery(elementId: String) {
        val layer = _elements.value.firstOrNull { it.id == elementId } as? MediaLayer ?: return
        viewModelScope.launch {
            val bytes = layer.localPath?.let { readAppStorageBytes(it) }
                ?: layer.url?.let { runCatching { repositoryDownload(it) }.getOrNull() }
                ?: return@launch
            saveFileToDevice(bytes, "pots-media-${elementId.take(8)}", galleryExtension(layer))
        }
    }

    /** Extension from the cached path when present, otherwise by layer type. */
    private fun galleryExtension(layer: MediaLayer): String = when (layer) {
        is AudioLayer -> galleryExtension(layer.localPath, isVideo = false, isAudio = true)
        is VideoLayer -> galleryExtension(layer.localPath, isVideo = true, isAudio = false)
        else -> galleryExtension(layer.localPath, isVideo = false, isAudio = false)
    }

    /** Extension from the cached path when present, otherwise by the type flags. */
    private fun galleryExtension(localPath: String?, isVideo: Boolean, isAudio: Boolean): String {
        val fromPath = localPath?.substringAfterLast('.', "")?.takeIf { it.length <= 4 }
        if (!fromPath.isNullOrBlank()) return fromPath
        return when {
            isAudio -> "mp3"
            isVideo -> "mp4"
            else -> "png"
        }
    }

    private suspend fun repositoryDownload(url: String): ByteArray {
        // Provider download is key-free for signed URLs; reuse repository's next
        // available mechanism by fetching through a fresh provider call.
        return PlusVibeMediaProvider().downloadResult(apiKeyProvider(), url)
    }

    companion object {
        private const val AUTOSAVE_DEBOUNCE_MS = 2000L
        private val AUDIO_EXTENSIONS = listOf(".mp3", ".wav", ".opus", ".flac", ".m4a", ".aac", ".ogg")
        private val RU_MONTHS_SHORT = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

        /** «Проект, 14 ноя» — the spec §12 auto name for a new project. */
        fun defaultProjectName(epochMs: Long): String {
            val date = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            return "Проект, ${date.dayOfMonth} ${RU_MONTHS_SHORT[date.monthNumber - 1]}"
        }
    }
}
