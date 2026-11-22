package com.pusios.pots.media

import com.pusios.pots.readAppStorageBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Helpers shared by the media provider adapters (Polza, AITUNNEL, …).
// PlusVibe predates this file and keeps its own copies — it is the reference
// implementation and stays untouched.

/** Decodes a `data:<mime>;base64,…` URL; null when the string is not a data URI. */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeMediaDataUri(url: String): ByteArray? {
    if (!url.startsWith("data:")) return null
    val payload = url.substringAfter(";base64,", missingDelimiterValue = "")
    if (payload.isEmpty()) return null
    return runCatching { Base64.decode(payload) }.getOrNull()
}

/**
 * Reference → `data:<mime>;base64,…`: the explicit [MediaReference.base64DataUri],
 * a `data:` URL, or local app-storage bytes encoded on the fly. Null when the
 * reference carries nothing readable.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun resolveMediaReferenceDataUri(ref: MediaReference): String? {
    ref.base64DataUri?.let { return it }
    ref.url?.takeIf { it.startsWith("data:") }?.let { return it }
    val path = ref.localPath ?: return null
    val bytes = readAppStorageBytes(path) ?: return null
    val mime = when {
        path.endsWith(".png", ignoreCase = true) -> "image/png"
        path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        path.endsWith(".webp", ignoreCase = true) -> "image/webp"
        path.endsWith(".gif", ignoreCase = true) -> "image/gif"
        path.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        path.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
        else -> "application/octet-stream"
    }
    return "data:$mime;base64," + Base64.encode(bytes)
}

/**
 * HTTP error mapping shared by the providers (the contract tables in
 * `docs/knowledge/media-providers/polza.md` and `aitunnel.md` agree on the
 * status semantics: 402 → insufficient balance, 429 → retry later,
 * 401/403 → key). `detail` comes from the provider's `error.message`.
 */
internal fun mapMediaHttpError(code: Int, body: String): MediaException {
    val detail = runCatching {
        val root = PlusVibeMediaProvider.JsonTolerant.parseToJsonElement(body).jsonObject
        (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return when (code) {
        400 -> MediaException.BadRequest(detail)
        401, 403 -> MediaException.InvalidKey(detail)
        402 -> MediaException.InsufficientBalance(detail)
        404 -> MediaException.ModelNotFound(detail)
        408, 502, 504 -> MediaException.UpstreamFailed(detail)
        429 -> MediaException.RateLimited(detail = detail)
        503 -> MediaException.Unavailable(detail)
        else -> MediaException.Unknown(detail ?: "HTTP $code")
    }
}
