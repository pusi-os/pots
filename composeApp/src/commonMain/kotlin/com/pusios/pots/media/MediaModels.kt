package com.pusios.pots.media

import androidx.compose.runtime.Immutable

/** Media kinds exposed by cloud media providers. Studio v1 drives image + video. */
enum class MediaKind {
    Image,
    Video,
    Audio,
    Music,
    Other3d,
}

fun MediaKind.fromProviderId(raw: String): MediaKind = when (raw.lowercase()) {
    "image" -> MediaKind.Image
    "video" -> MediaKind.Video
    "audio" -> MediaKind.Audio
    "music" -> MediaKind.Music
    "3d", "model", "mesh" -> MediaKind.Other3d
    else -> MediaKind.Image
}

@Immutable
data class MediaCapValue(
    val value: String,
    val label: String = value,
)

@Immutable
data class MediaParamDef(
    /** Key inside `opts`, e.g. `aspect_ratio`, `duration`, `resolution`. */
    val key: String,
    val label: String,
    /** Provider type hint: `select`, `text`, `number`, `boolean`. */
    val type: String = "select",
    val defaultValue: String? = null,
    val options: List<MediaCapValue> = emptyList(),
    val required: Boolean = false,
)

@Immutable
data class MediaVariantPrice(
    /** Routable id (`gpt-image-2:network`, `flux-2:flow`, …). */
    val addressAs: String,
    val label: String? = null,
    /** Price for one full job in RUB, null when unavailable or per-unit. */
    val priceRub: Double? = null,
    val priceUnavailable: Boolean = false,
    val priceStatus: String? = null,
)

@Immutable
data class MediaModel(
    /** Public id sent as `model` (`gpt-image-2`, `veo-3.1`, …). */
    val id: String,
    val kind: MediaKind,
    val unit: String? = null,
    val note: String? = null,
    /** Minimal “from” price in RUB; exact only when `priceStatus == "exact"`. */
    val fromRub: Double? = null,
    val priceStatus: String? = null,
    val priceUnavailable: Boolean = false,
    val generationAvailable: Boolean = true,
    val variants: List<MediaVariantPrice> = emptyList(),
    val params: List<MediaParamDef> = emptyList(),
    /** Provider task hint (`tts`, `asr`, `sfx`, `utility`, …); null when absent. */
    val task: String? = null,
    /** Model accepts a source image (edit / image-to-image / I2V). */
    val acceptsImageReference: Boolean = false,
    /** Model accepts a source video. */
    val acceptsVideoReference: Boolean = false,
    /** Model consumes audio input (STT / transforms) — excluded from the audio sheet. */
    val acceptsAudioReference: Boolean = false,
)

@Immutable
data class MediaPriceQuote(
    /** Quoted price for one job in RUB. */
    val priceRub: Double,
    /** `mq_…` — pass back as `quoteId` on submit. Null for providers without quotes. */
    val quoteId: String? = null,
    val expiresAt: String? = null,
)

/**
 * One user-supplied reference asset for image/video generation.
 * Exactly one of [url] / [base64DataUri] / [localPath] is set:
 * - [url] — public `https://` address the provider downloads itself;
 * - [base64DataUri] — full `data:<mime>;base64,…` string (URL fields reject `data:`);
 * - [localPath] — app-storage path; resolved to a data URI by the provider.
 */
@Immutable
data class MediaReference(
    val url: String? = null,
    val base64DataUri: String? = null,
    val localPath: String? = null,
    val isVideo: Boolean = false,
)

@Immutable
data class MediaJobRequest(
    val model: String,
    val prompt: String,
    val opts: Map<String, String> = emptyMap(),
    val quoteId: String? = null,
    val maxPriceRub: Double? = null,
    /** Idempotency key; provider replays the same job for 24 h. */
    val idempotencyKey: String? = null,
    /** Source assets (edit / image-to-image / first frame). Mapped into `opts` per model. */
    val references: List<MediaReference> = emptyList(),
)

enum class MediaJobStatus {
    Submitting,
    Processing,
    Success,
    Fail,
}

@Immutable
data class MediaJobState(
    val jobId: String,
    val status: MediaJobStatus,
    val model: String? = null,
    val kind: MediaKind? = null,
    val resultUrls: List<String> = emptyList(),
    /** Final charge in RUB (0 until success). */
    val priceRub: Double = 0.0,
    val failMsg: String? = null,
    val errorCode: String? = null,
    /** Provider advice (`retry_later`, `fix_input`, …). */
    val advice: String? = null,
    /** True when the job is finally decided and will not start later. */
    val definitive: Boolean = false,
    /** Local copy path in app storage, set after download completes. */
    val localPath: String? = null,
    /** Estimated progress 0..1 while processing, null when unknown. */
    val progress: Float? = null,
) {
    val isTerminal: Boolean get() = status == MediaJobStatus.Success || status == MediaJobStatus.Fail
    val isUncertainSubmission: Boolean get() = status == MediaJobStatus.Submitting
}

/** Typed media errors mapped from provider HTTP codes; none of these imply a charge. */
sealed class MediaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class BadRequest(val detail: String? = null) : MediaException(detail ?: "Bad media request")
    data class InvalidKey(val detail: String? = null) : MediaException(detail ?: "Invalid media API key")
    data class InsufficientBalance(val detail: String? = null) : MediaException(detail ?: "Insufficient balance")
    data class ModelNotFound(val detail: String? = null) : MediaException(detail ?: "Media model not found")
    data class RateLimited(val retryAfterSeconds: Long? = null, val detail: String? = null) : MediaException(detail ?: "Media rate limited")
    data class UpstreamFailed(val detail: String? = null) : MediaException(detail ?: "Media upstream failed")
    data class Unavailable(val detail: String? = null) : MediaException(detail ?: "Media model unavailable")
    data class Expired(val detail: String? = null) : MediaException(detail ?: "Media result expired")
    data class Unknown(val detail: String? = null) : MediaException(detail ?: "Unknown media error")
}
