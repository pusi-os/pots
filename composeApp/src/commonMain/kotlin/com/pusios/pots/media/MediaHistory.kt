package com.pusios.pots.media

import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.writeAppStorageFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val MEDIA_PROVIDER_NAME = "PlusVibeAPI"

private const val HISTORY_FILE = "media/history.json"
private const val HISTORY_LIMIT = 300

/** Status as the user sees it in the journal. */
@Serializable
enum class MediaHistoryStatus { Sending, Processing, Success, Failed }

@Serializable
data class MediaHistoryReference(
    val url: String? = null,
    val localPath: String? = null,
    val isVideo: Boolean = false,
)

/**
 * One generation in the journal. [localId] doubles as the `Idempotency-Key` of the first
 * submit, so retrying a failed submit cannot create a second paid job.
 */
@Serializable
data class MediaHistoryEntry(
    val localId: String,
    val createdAtMs: Long,
    val isVideo: Boolean,
    val model: String,
    val provider: String = MEDIA_PROVIDER_NAME,
    val prompt: String,
    val opts: Map<String, String> = emptyMap(),
    val references: List<MediaHistoryReference> = emptyList(),
    /** References that existed only in memory (base64 from chat tools); such entries cannot be repeated. */
    val droppedReferences: Int = 0,
    val status: MediaHistoryStatus = MediaHistoryStatus.Sending,
    val jobId: String? = null,
    /** Quote taken before submit, RUB. */
    val estimateRub: Double? = null,
    /** Quote id from the first submit; a repeat reuses it so the body matches the idempotency key. */
    val quoteId: String? = null,
    val maxPriceRub: Double? = null,
    /** Final charge reported by the provider, RUB. */
    val priceRub: Double? = null,
    val resultUrl: String? = null,
    /** Local copy in app storage, set once the result is downloaded. */
    val localPath: String? = null,
    val failMsg: String? = null,
    val progress: Float? = null,
) {
    val isActive: Boolean get() = status == MediaHistoryStatus.Sending || status == MediaHistoryStatus.Processing
    val canRepeat: Boolean get() = !isActive && droppedReferences == 0
    val hasPreview: Boolean get() = status == MediaHistoryStatus.Success && (localPath != null || resultUrl != null)

    /** A failed submit never got a jobId, so retrying it reuses [localId] safely. */
    val canRetrySubmit: Boolean get() = status == MediaHistoryStatus.Failed && jobId == null && droppedReferences == 0
}

fun MediaReference.toHistoryReference(): MediaHistoryReference? {
    if (url == null && localPath == null) return null
    return MediaHistoryReference(url = url, localPath = localPath, isVideo = isVideo)
}

/**
 * Journal of generations, persisted as JSON in app storage. Follows
 * [MediaGenerationRepository.jobs], so entries update while the studio is closed.
 */
class MediaHistoryStore(
    private val repository: MediaGenerationRepository,
    private val scope: CoroutineScope,
    private val apiKeyProvider: () -> String,
) {
    private val _entries = MutableStateFlow<List<MediaHistoryEntry>>(emptyList())
    val entries: StateFlow<List<MediaHistoryEntry>> = _entries.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val writeLock = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true
        val stored = readAppStorageBytes(HISTORY_FILE)?.decodeToString()
        val restored = stored?.let { text ->
            runCatching { json.decodeFromString<List<MediaHistoryEntry>>(text) }
                .onFailure { MediaLog.w("History file unreadable, starting empty: ${it.message}") }
                .getOrDefault(emptyList())
        }.orEmpty().map { entry ->
            // The app was closed before the server answered: nothing to poll, the user decides on retry.
            if (entry.status == MediaHistoryStatus.Sending && entry.jobId == null) {
                entry.copy(
                    status = MediaHistoryStatus.Failed,
                    failMsg = "Приложение закрылось до ответа сервера. Повтори: тот же ключ не создаст вторую задачу.",
                )
            } else {
                entry
            }
        }
        _entries.value = restored
        persist()
        restored.filter { it.isActive && it.jobId != null }.forEach { entry ->
            scope.launch {
                runCatching { repository.resume(apiKeyProvider(), entry.jobId!!) }
                    .onFailure { error ->
                        MediaLog.w("Resume failed: jobId=${entry.jobId} ${error.message}")
                        if (error is MediaException.ModelNotFound) {
                            modify(entry.localId) { it.copy(status = MediaHistoryStatus.Failed, failMsg = "Провайдер не знает эту задачу") }
                        }
                    }
            }
        }
        scope.launch { repository.jobs.collect { jobs -> syncWithJobs(jobs) } }
    }

    fun upsert(entry: MediaHistoryEntry) = mutate { list ->
        listOf(entry) + list.filterNot { it.localId == entry.localId }
    }

    fun modify(localId: String, transform: (MediaHistoryEntry) -> MediaHistoryEntry) = mutate { list ->
        list.map { if (it.localId == localId) transform(it) else it }
    }

    /** Removes the journal record only; the canvas copy and the gallery file are not touched. */
    fun remove(localId: String) = mutate { list -> list.filterNot { it.localId == localId } }

    /** Drops everything except generations still in flight. */
    fun clearFinished() = mutate { list -> list.filter { it.isActive } }

    private fun syncWithJobs(jobs: Map<String, MediaJobState>) {
        mutate { list ->
            list.map { entry ->
                val state = entry.jobId?.let { jobs[it] } ?: return@map entry
                entry.applying(state)
            }
        }
    }

    private fun mutate(transform: (List<MediaHistoryEntry>) -> List<MediaHistoryEntry>) {
        var changed = false
        _entries.update { current ->
            transform(current).take(HISTORY_LIMIT).also { changed = it != current }
        }
        if (changed) persist()
    }

    private fun persist() {
        scope.launch(Dispatchers.Default) {
            writeLock.withLock {
                // Writes the latest snapshot, so out-of-order coroutines cannot leave stale data on disk.
                val text = json.encodeToString(_entries.value)
                if (!writeAppStorageFile(HISTORY_FILE, text)) MediaLog.w("History write failed")
            }
        }
    }

    private fun MediaHistoryEntry.applying(state: MediaJobState): MediaHistoryEntry = copy(
        jobId = state.jobId,
        status = when (state.status) {
            MediaJobStatus.Success -> MediaHistoryStatus.Success
            MediaJobStatus.Fail -> MediaHistoryStatus.Failed
            MediaJobStatus.Submitting -> MediaHistoryStatus.Sending
            MediaJobStatus.Processing -> MediaHistoryStatus.Processing
        },
        priceRub = state.priceRub.takeIf { it > 0.0 } ?: priceRub,
        resultUrl = state.resultUrls.firstOrNull() ?: resultUrl,
        localPath = state.localPath ?: localPath,
        failMsg = if (state.status == MediaJobStatus.Fail) state.failMsg ?: failMsg else null,
        progress = state.progress,
    )
}
