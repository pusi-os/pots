package com.pusios.pots.media

import com.pusios.pots.httpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * PlusVibeAPI adapter. Docs snapshot: `docs/knowledge/media-providers/plusvibe.md`.
 *
 * Base URLs: `https://plusvibeapi.ru` (primary), `https://gw.plusvibeapi.ru` (reserve).
 * Auth: `Authorization: Bearer sk-pv-…` (the chat instance key).
 */
class PlusVibeMediaProvider(
    private val baseUrl: String = "https://plusvibeapi.ru",
) : MediaProvider {

    private val client = httpClient()

    private val json = JsonTolerant

    override suspend fun listModels(apiKey: String): List<MediaModel> {
        val body = client.get("$baseUrl/api/media-catalog") { authed(apiKey) }.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        val models = root["models"]?.jsonArray ?: return emptyList()
        return models.mapNotNull { parseModel(it as? JsonObject ?: return@mapNotNull null) }
            .filter { it.id.isNotBlank() && it.generationAvailable && !it.priceUnavailable }
    }

    override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote {
        val body = client.post("$baseUrl/api/media/price") {
            authed(apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildGenerateBody(request))
        }.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        val price = root["priceRub"]?.jsonPrimitive?.doubleOrNull
            ?: root["price"]?.jsonPrimitive?.doubleOrNull
            ?: 0.0
        return MediaPriceQuote(
            priceRub = price,
            quoteId = root["quoteId"]?.jsonPrimitive?.contentOrNull,
            expiresAt = root["expiresAt"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
        val response = client.post("$baseUrl/api/media/generate") {
            authed(apiKey)
            contentType(ContentType.Application.Json)
            request.idempotencyKey?.takeIf { it.isNotBlank() }?.let { header("Idempotency-Key", it) }
            setBody(buildGenerateBody(request))
        }
        val body = response.bodyAsText()
        if (response.status.value == 202) {
            val root = json.parseToJsonElement(body).jsonObject
            val jobId = root["jobId"]?.jsonPrimitive?.contentOrNull
            if (jobId.isNullOrBlank()) throw MediaException.Unknown("Empty jobId from provider")
            return MediaJobState(jobId = jobId, status = MediaJobStatus.Processing, model = request.model)
        }
        throw mapError(response.status.value, body)
    }

    override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState {
        val response = client.get("$baseUrl/api/media/jobs/$jobId") { authed(apiKey) }
        val body = response.bodyAsText()
        if (response.status.value == 404) throw MediaException.ModelNotFound("Job not found")
        if (response.status.value !in 200..299) throw mapError(response.status.value, body)
        val root = json.parseToJsonElement(body).jsonObject
        val statusRaw = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val status = when (statusRaw) {
            "success" -> MediaJobStatus.Success
            "fail", "failed", "error" -> MediaJobStatus.Fail
            "submitting" -> MediaJobStatus.Submitting
            else -> MediaJobStatus.Processing
        }
        val failure = root["failure"] as? JsonObject
        val resultUrls = root["resultUrls"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return MediaJobState(
            jobId = jobId,
            status = status,
            model = root["model"]?.jsonPrimitive?.contentOrNull,
            kind = root["kind"]?.jsonPrimitive?.contentOrNull?.let { MediaKind.Image.fromProviderId(it) },
            resultUrls = resultUrls,
            priceRub = root["priceRub"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            failMsg = root["failMsg"]?.jsonPrimitive?.contentOrNull,
            errorCode = root["errorCode"]?.jsonPrimitive?.contentOrNull,
            advice = root["retryGuidance"]?.jsonPrimitive?.contentOrNull
                ?: failure?.get("advice")?.jsonPrimitive?.contentOrNull,
            definitive = failure?.get("definitive")?.jsonPrimitive?.booleanOrNull ?: (status == MediaJobStatus.Fail),
        )
    }

    override suspend fun downloadResult(apiKey: String, url: String): ByteArray {
        // Signed file URLs need no key; public https uploads also ignore it.
        return client.get(url).body<ByteArray>()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authed(apiKey: String) {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }

    private fun buildGenerateBody(request: MediaJobRequest): JsonObject {
        val opts = JsonObject(
            request.opts.entries.associate { (k, v) -> k to JsonPrimitive(v) },
        )
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        map["model"] = JsonPrimitive(request.model)
        map["prompt"] = JsonPrimitive(request.prompt)
        map["opts"] = opts
        request.quoteId?.let { map["quote_id"] = JsonPrimitive(it) }
        request.maxPriceRub?.let { map["max_price_rub"] = JsonPrimitive(it) }
        return JsonObject(map)
    }

    private fun parseModel(obj: JsonObject): MediaModel? {
        val id = obj["model"]?.jsonPrimitive?.contentOrNull ?: return null
        val kindRaw = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "image"
        val kind = MediaKind.Image.fromProviderId(kindRaw)
        val params = collectParams(obj)
        val acceptsImage = params.any { it.key in IMAGE_REFERENCE_KEYS } ||
            (obj["inputTypes"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "image" } == true)
        val acceptsVideo = params.any { it.key in VIDEO_REFERENCE_KEYS } ||
            (obj["inputTypes"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "video" } == true)
        return MediaModel(
            id = id,
            kind = kind,
            unit = obj["unit"]?.jsonPrimitive?.contentOrNull,
            note = obj["note"]?.jsonPrimitive?.contentOrNull,
            fromRub = obj["fromRub"]?.jsonPrimitive?.doubleOrNull,
            priceStatus = obj["priceStatus"]?.jsonPrimitive?.contentOrNull,
            priceUnavailable = obj["priceUnavailable"]?.jsonPrimitive?.booleanOrNull == true,
            generationAvailable = obj["generationAvailable"]?.jsonPrimitive?.booleanOrNull ?: true,
            variants = parseVariants(obj),
            params = params,
            acceptsImageReference = acceptsImage,
            acceptsVideoReference = acceptsVideo,
        )
    }

    private fun parseVariants(obj: JsonObject): List<MediaVariantPrice> {
        val arr = obj["variants"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val address = o["addressAs"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MediaVariantPrice(
                addressAs = address,
                label = o["label"]?.jsonPrimitive?.contentOrNull,
                priceRub = o["priceRub"]?.jsonPrimitive?.doubleOrNull,
                priceUnavailable = o["priceUnavailable"]?.jsonPrimitive?.booleanOrNull == true,
                priceStatus = o["priceStatus"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private fun collectParams(obj: JsonObject): List<MediaParamDef> {
        val merged = linkedMapOf<String, MediaParamDef>()
        fun add(key: String, label: String, type: String, defaultValue: String?, options: List<MediaCapValue>, required: Boolean) {
            if (key.isBlank() || merged.containsKey(key)) return
            merged[key] = MediaParamDef(key, label, type, defaultValue, options, required)
        }
        fun fromAxes(arr: JsonArray?) {
            arr?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val label = o["label"]?.jsonPrimitive?.contentOrNull ?: key
                val type = o["type"]?.jsonPrimitive?.contentOrNull ?: "select"
                val defaultValue = o["default"]?.jsonPrimitive?.contentOrNull
                val options = (o["options"] as? JsonArray)?.mapNotNull { opt ->
                    (opt as? JsonObject)?.let {
                        val v = it["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        MediaCapValue(v, it["label"]?.jsonPrimitive?.contentOrNull ?: v)
                    }
                }.orEmpty()
                add(key, label, type, defaultValue, options, o["required"]?.jsonPrimitive?.booleanOrNull == true)
            }
        }
        fun fromParams(arr: JsonArray?) {
            arr?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val label = o["label"]?.jsonPrimitive?.contentOrNull ?: key
                val type = o["type"]?.jsonPrimitive?.contentOrNull ?: "select"
                val defaultValue = o["default"]?.jsonPrimitive?.contentOrNull
                val rawOptions = (o["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val labels = (o["optionLabels"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val options = rawOptions.mapIndexed { idx, v -> MediaCapValue(v, labels.getOrElse(idx) { v }) }
                add(key, label, type, defaultValue, options, o["required"]?.jsonPrimitive?.booleanOrNull == true)
            }
        }
        fromParams(obj["params"] as? JsonArray)
        fromAxes(obj["axes"] as? JsonArray)
        return merged.values.toList()
    }

    private fun mapError(code: Int, body: String): MediaException {
        val detail = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            (o["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: o["message"]?.jsonPrimitive?.contentOrNull
                ?: o["failMsg"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when (code) {
            400 -> MediaException.BadRequest(detail)
            401, 403 -> MediaException.InvalidKey(detail)
            402 -> MediaException.InsufficientBalance(detail)
            404, 422 -> MediaException.ModelNotFound(detail)
            429 -> MediaException.RateLimited(detail = detail)
            410 -> MediaException.Expired(detail)
            503 -> MediaException.Unavailable(detail)
            502 -> MediaException.UpstreamFailed(detail)
            else -> MediaException.Unknown(detail ?: "HTTP $code")
        }
    }

    companion object {
        private val IMAGE_REFERENCE_KEYS = setOf(
            "image_url", "image_base64", "image_file_id", "image_urls", "input_urls", "input_base64",
            "reference_image", "reference_image_urls", "reference_image_base64", "reference_image_file_id",
            "first_frame_url", "first_frame_base64", "first_frame_file_id",
            "last_frame_url", "last_frame_base64", "last_frame_file_id", "mask_url", "mask_base64",
        )
        private val VIDEO_REFERENCE_KEYS = setOf(
            "video_url",
            "video_base64",
            "video_file_id",
            "reference_video_urls",
        )

        /** Tolerant JSON for provider payloads (unknown fields never fail parsing). */
        val JsonTolerant: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        @OptIn(ExperimentalUuidApi::class)
        fun newIdempotencyKey(): String = Uuid.random().toString()
    }
}
