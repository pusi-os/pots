package com.pusios.pots.media

import com.pusios.pots.writeAppStorageBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Async media generation queue. Submits once per user action (idempotency
 * key), polls politely, and downloads result bytes immediately — providers
 * keep results only days.
 *
 * Polling: 3 s → 5 s → 8 s … capped at 30 s (per PlusVibe docs).
 * Cancelling the caller only stops polling; the job continues server-side
 * and can be resumed by polling the same `jobId` on reopen.
 */
class MediaGenerationRepository(
    private val provider: MediaProvider,
    private val scope: CoroutineScope,
) {
    private val _jobs = MutableStateFlow<Map<String, MediaJobState>>(emptyMap())
    val jobs: StateFlow<Map<String, MediaJobState>> = _jobs.asStateFlow()

    private val active = mutableMapOf<String, Job>()

    /** Catalog cache per api-key fingerprint (key is never stored). */
    private val catalogCache = mutableMapOf<String, List<MediaModel>>()

    suspend fun models(apiKey: String): List<MediaModel> {
        val fingerprint = apiKey.hashCode().toString()
        catalogCache[fingerprint]?.let {
            MediaLog.d("Catalog cache hit: ${it.size} models")
            return it
        }
        val fresh = provider.listModels(apiKey)
        catalogCache[fingerprint] = fresh
        return fresh
    }

    suspend fun refreshModels(apiKey: String): List<MediaModel> {
        val fresh = provider.listModels(apiKey)
        catalogCache[apiKey.hashCode().toString()] = fresh
        return fresh
    }

    /** Quote-then-submit: tries a real quote first, falls back to direct submit. */
    suspend fun generate(
        apiKey: String,
        request: MediaJobRequest,
        onQuote: ((MediaPriceQuote) -> Unit)? = null,
        onUpdate: ((MediaJobState) -> Unit)? = null,
    ): MediaJobState {
        // A repeat of a failed submit already carries its quote: keep it, so the body
        // stays identical to the one behind the idempotency key.
        val quoted = if (request.quoteId != null) {
            null
        } else {
            runCatching { provider.quotePrice(apiKey, request) }
                .onFailure { MediaLog.w("Quote failed (will submit without quote): ${it.message}") }
                .getOrNull()
        }
        quoted?.let { onQuote?.invoke(it) }
        val submit = request.copy(
            quoteId = request.quoteId ?: quoted?.quoteId,
            maxPriceRub = request.maxPriceRub ?: quoted?.priceRub,
            idempotencyKey = request.idempotencyKey ?: newKey(),
        )
        val created = provider.submitJob(apiKey, submit)
        _jobs.update { it + (created.jobId to created) }
        onUpdate?.invoke(created)
        startPolling(apiKey, created.jobId, onUpdate)
        return created
    }

    fun startPolling(apiKey: String, jobId: String, onUpdate: ((MediaJobState) -> Unit)? = null) {
        if (active[jobId]?.isActive == true) return
        MediaLog.d("Polling started: jobId=$jobId")
        active[jobId] = scope.launch {
            var waitMs = 3000L
            while (true) {
                delay(waitMs)
                val state = try {
                    provider.pollJob(apiKey, jobId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Transient network error: keep polling with backoff.
                    MediaLog.w("Poll transient error (retry in ${waitMs * 2} ms): jobId=$jobId ${e.message}")
                    waitMs = min(waitMs * 2, 30_000L)
                    continue
                }
                _jobs.update { it + (state.jobId to state) }
                onUpdate?.invoke(state)
                if (state.isTerminal) {
                    MediaLog.i("Job terminal: jobId=$jobId status=${state.status} priceRub=${state.priceRub} fail=${state.failMsg ?: "-"}")
                    if (state.status == MediaJobStatus.Success && state.localPath == null) {
                        downloadAndCache(apiKey, state, onUpdate)
                    }
                    break
                }
                // `submitting` (uncertain) stays non-terminal: poll same id, never resubmit.
                waitMs = min(waitMs * 2, 30_000L)
            }
        }
    }

    /** Re-poll a known job (e.g. after reopening the studio). */
    suspend fun resume(apiKey: String, jobId: String, onUpdate: ((MediaJobState) -> Unit)? = null) {
        val state = provider.pollJob(apiKey, jobId)
        _jobs.update { it + (state.jobId to state) }
        onUpdate?.invoke(state)
        if (!state.isTerminal) {
            startPolling(apiKey, jobId, onUpdate)
        } else if (state.status == MediaJobStatus.Success && state.localPath == null) {
            downloadAndCache(apiKey, state, onUpdate)
        }
    }

    fun cancelPolling(jobId: String) {
        active.remove(jobId)?.cancel()
    }

    suspend fun download(apiKey: String, url: String): ByteArray = provider.downloadResult(apiKey, url)

    suspend fun downloadAndCache(apiKey: String, state: MediaJobState, onUpdate: ((MediaJobState) -> Unit)? = null) {
        val first = state.resultUrls.firstOrNull() ?: return
        val bytes = try {
            provider.downloadResult(apiKey, first)
        } catch (e: Exception) {
            // Keep remote URL; timeout/expired will surface on view.
            MediaLog.w("Result download failed (keeping remote URL): jobId=${state.jobId} ${e.message}")
            return
        }
        val ext = mediaResultExtension(first, state.kind)
        val path = "media/jobs/${state.jobId}/0.$ext"
        writeAppStorageBytes(path, bytes)
        MediaLog.i("Result cached: jobId=${state.jobId} path=$path (${bytes.size} bytes)")
        val cached = state.copy(localPath = path)
        _jobs.update { it + (state.jobId to cached) }
        onUpdate?.invoke(cached)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newKey(): String = Uuid.random().toString()

    companion object {
        /** Job history is modeled as JSON blobs, not a table — no migration involved. */
        val POLL_START_MS: Long = 3000L
        val POLL_MAX_MS: Long = 30_000L

        fun backoff(current: Long): Long = min(current * 2, POLL_MAX_MS)
    }
}

/**
 * Local file extension for a downloaded result. Signed proxy URLs carry no
 * extension, so the kind decides the default (audio → `mp3`, the provider's
 * TTS/music default format).
 */
internal fun mediaResultExtension(url: String, kind: MediaKind?): String {
    val path = url.substringBefore('?').lowercase()
    return when {
        path.contains(".mp4") || path.contains(".mov") || path.contains(".webm") -> "mp4"
        path.contains(".png") -> "png"
        path.contains(".jpg") || path.contains(".jpeg") -> "jpg"
        path.contains(".webp") -> "webp"
        path.contains(".mp3") -> "mp3"
        path.contains(".wav") -> "wav"
        path.contains(".opus") -> "opus"
        path.contains(".flac") -> "flac"
        path.contains(".aac") || path.contains(".m4a") -> "m4a"
        path.contains(".ogg") -> "ogg"
        kind == MediaKind.Video -> "mp4"
        kind == MediaKind.Audio || kind == MediaKind.Music -> "mp3"
        else -> "png"
    }
}
