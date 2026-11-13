package com.pusios.pots.media

import com.pusios.pots.httpClient
import com.pusios.pots.readAppStorageBytes
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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

    private val client = httpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
            connectTimeoutMillis = 30_000
        }
    }

    private val json = JsonTolerant

    /** Catalog cache for reference mapping: which opts keys each model accepts. */
    private val modelsById = mutableMapOf<String, MediaModel>()

    override suspend fun listModels(apiKey: String): List<MediaModel> {
        val body = client.get("$baseUrl/api/media-catalog") { authed(apiKey) }.bodyAsText()
        val parsed = parseCatalog(body)
        parsed.forEach { modelsById[it.id] = it }
        MediaLog.i(
            "Catalog loaded: ${parsed.size} usable models (" +
                "${parsed.count { it.kind == MediaKind.Image }} image, " +
                "${parsed.count { it.kind == MediaKind.Video }} video, " +
                "${parsed.count { it.kind == MediaKind.Audio }} audio, " +
                "${parsed.count { it.kind == MediaKind.Music }} music)",
        )
        return parsed
    }

    /** Parses the catalog payload and keeps only models usable for generation. */
    internal fun parseCatalog(body: String): List<MediaModel> {
        val root = json.parseToJsonElement(body).jsonObject
        val models = root["models"]?.jsonArray ?: return emptyList()
        return models.mapNotNull { parseModel(it as? JsonObject ?: return@mapNotNull null) }
            .filter { it.id.isNotBlank() && it.generationAvailable && !it.priceUnavailable }
    }

    override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote {
        MediaLog.d("Quote request: model=${request.model} refs=${request.references.size}")
        val body = client.post("$baseUrl/api/media/price") {
            authed(apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildGenerateBody(request).toString())
        }.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        val price = root["priceRub"]?.jsonPrimitive?.doubleOrNull
            ?: root["price"]?.jsonPrimitive?.doubleOrNull
            ?: 0.0
        val quote = MediaPriceQuote(
            priceRub = price,
            quoteId = root["quoteId"]?.jsonPrimitive?.contentOrNull,
            expiresAt = root["expiresAt"]?.jsonPrimitive?.contentOrNull,
        )
        MediaLog.d("Quote result: model=${request.model} priceRub=${quote.priceRub} quoteId=${quote.quoteId ?: "-"}")
        return quote
    }

    override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
        MediaLog.i("Submit: model=${request.model} opts=${request.opts.keys} refs=${request.references.size} keyPresent=${apiKey.isNotBlank()}")
        val response = client.post("$baseUrl/api/media/generate") {
            authed(apiKey)
            contentType(ContentType.Application.Json)
            request.idempotencyKey?.takeIf { it.isNotBlank() }?.let { header("Idempotency-Key", it) }
            setBody(buildGenerateBody(request).toString())
        }
        val body = response.bodyAsText()
        if (response.status.value == 202) {
            val root = json.parseToJsonElement(body).jsonObject
            val jobId = root["jobId"]?.jsonPrimitive?.contentOrNull
            if (jobId.isNullOrBlank()) throw MediaException.Unknown("Empty jobId from provider")
            MediaLog.i("Submitted: jobId=$jobId model=${request.model}")
            return MediaJobState(jobId = jobId, status = MediaJobStatus.Processing, model = request.model)
        }
        MediaLog.e("Submit failed: HTTP ${response.status.value} body=${body.take(500)}")
        throw mapError(response.status.value, body)
    }

    override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState {
        val response = client.get("$baseUrl/api/media/jobs/$jobId") { authed(apiKey) }
        val body = response.bodyAsText()
        if (response.status.value == 404) throw MediaException.ModelNotFound("Job not found")
        if (response.status.value !in 200..299) {
            MediaLog.e("Poll failed: jobId=$jobId HTTP ${response.status.value} body=${body.take(500)}")
            throw mapError(response.status.value, body)
        }
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
        ).also {
            if (it.status != MediaJobStatus.Processing || it.progress != null) {
                MediaLog.d("Poll: jobId=$jobId status=${it.status} priceRub=${it.priceRub} urls=${it.resultUrls.size} fail=${it.failMsg ?: "-"}")
            }
        }
    }

    override suspend fun downloadResult(apiKey: String, url: String): ByteArray {
        // Signed file URLs need no key; public https uploads also ignore it.
        val bytes = client.get(url).body<ByteArray>()
        MediaLog.i("Downloaded result: ${bytes.size} bytes from ${url.take(80)}")
        return bytes
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authed(apiKey: String) {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }

    /** Internal for tests: the exact JSON body sent to `/api/media/generate` and `/api/media/price`. */
    internal fun buildGenerateBody(request: MediaJobRequest): JsonObject {
        val optsMap = mutableMapOf<String, JsonElement>()
        request.opts.forEach { (k, v) -> optsMap[k] = JsonPrimitive(v) }
        appendReferences(optsMap, request)
        val modeKey = modelsById[request.model]?.params?.firstOrNull { it.key == "mode" }
        if (modeKey != null && request.references.isNotEmpty() && "mode" !in request.opts) {
            optsMap["mode"] = JsonPrimitive("image-to-image")
        }
        val map = mutableMapOf<String, JsonElement>()
        map["model"] = JsonPrimitive(request.model)
        map["prompt"] = JsonPrimitive(request.prompt)
        map["opts"] = JsonObject(optsMap)
        request.quoteId?.let { map["quote_id"] = JsonPrimitive(it) }
        request.maxPriceRub?.let { map["max_price_rub"] = JsonPrimitive(it) }
        return JsonObject(map)
    }

    /**
     * Maps user references onto the model's own opts keys (URL fields accept
     * only public https; local bytes go to the paired `*_base64` field as a
     * full `data:<mime>;base64,…` string — see `docs/knowledge/media-providers/plusvibe.md`).
     */
    private fun appendReferences(opts: MutableMap<String, JsonElement>, request: MediaJobRequest) {
        if (request.references.isEmpty()) return
        val modelInfo = modelsById[request.model]
        request.references.forEach { ref ->
            if (ref.isVideo) {
                appendVideoReference(opts, ref, modelInfo)
            } else {
                appendImageReference(opts, ref, modelInfo)
            }
        }
    }

    private fun appendImageReference(opts: MutableMap<String, JsonElement>, ref: MediaReference, modelInfo: MediaModel?) {
        if (modelInfo != null && !modelInfo.acceptsImageReference) {
            throw MediaException.BadRequest("Модель ${modelInfo.id} не принимает референс-изображение")
        }
        val paramKeys = modelInfo?.params?.map { it.key }?.toSet().orEmpty()
        val url = normalizeReferenceUrl(ref)
        if (url != null) {
            val singleKey = listOf("image_url", "reference_image", "first_frame_url").firstOrNull { it in paramKeys }
            val pluralKey = listOf("image_urls", "reference_image_urls", "input_urls").firstOrNull { it in paramKeys }
            when {
                singleKey != null -> opts[singleKey] = JsonPrimitive(url)

                pluralKey != null -> opts.appendToArray(pluralKey, url)

                else -> {
                    MediaLog.w("Model ${modelInfo?.id ?: "?"}: no image-url key in params, falling back to image_url")
                    opts["image_url"] = JsonPrimitive(url)
                }
            }
            return
        }
        val dataUri = resolveDataUri(ref)
            ?: throw MediaException.BadRequest("Референс недоступен: файл не найден или не читается")
        val singleKey = listOf("image_base64", "reference_image_base64", "first_frame_base64").firstOrNull { it in paramKeys }
        val pluralKey = listOf("input_base64").firstOrNull { it in paramKeys }
        when {
            singleKey != null -> opts[singleKey] = JsonPrimitive(dataUri)

            pluralKey != null -> opts.appendToArray(pluralKey, dataUri)

            else -> {
                MediaLog.w("Model ${modelInfo?.id ?: "?"}: no image-base64 key in params, falling back to image_base64")
                opts["image_base64"] = JsonPrimitive(dataUri)
            }
        }
    }

    private fun appendVideoReference(opts: MutableMap<String, JsonElement>, ref: MediaReference, modelInfo: MediaModel?) {
        if (modelInfo != null && !modelInfo.acceptsVideoReference) {
            throw MediaException.BadRequest("Модель ${modelInfo.id} не принимает референс-видео")
        }
        val paramKeys = modelInfo?.params?.map { it.key }?.toSet().orEmpty()
        val url = normalizeReferenceUrl(ref)
        if (url != null) {
            val singleKey = listOf("video_url").firstOrNull { it in paramKeys }
            val pluralKey = listOf("reference_video_urls").firstOrNull { it in paramKeys }
            when {
                singleKey != null -> opts[singleKey] = JsonPrimitive(url)
                pluralKey != null -> opts.appendToArray(pluralKey, url)
                else -> opts["video_url"] = JsonPrimitive(url)
            }
            return
        }
        val dataUri = resolveDataUri(ref)
            ?: throw MediaException.BadRequest("Референс недоступен: файл не найден или не читается")
        opts["video_base64"] = JsonPrimitive(dataUri)
    }

    /** Public https stays a URL; `data:` and anything else goes through the base64 path. */
    private fun normalizeReferenceUrl(ref: MediaReference): String? {
        val url = ref.url ?: return null
        return if (url.startsWith("https://")) url else null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun resolveDataUri(ref: MediaReference): String? {
        ref.base64DataUri?.let { return it }
        if (ref.url.orEmpty().startsWith("data:")) return ref.url
        val path = ref.localPath ?: return null
        val bytes = readAppStorageBytes(path) ?: return null
        if (bytes.size > MAX_INLINE_REFERENCE_BYTES) {
            throw MediaException.BadRequest(
                "Файл референса больше ${MAX_INLINE_REFERENCE_BYTES / 1024 / 1024} МБ — сожми его перед отправкой",
            )
        }
        val mime = when {
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            path.endsWith(".webp", ignoreCase = true) -> "image/webp"
            path.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            path.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            else -> "application/octet-stream"
        }
        return "data:$mime;base64," + Base64.encode(bytes)
    }

    private fun MutableMap<String, JsonElement>.appendToArray(key: String, value: String) {
        val existing = this[key] as? JsonArray
        this[key] = JsonArray(existing?.plus(JsonPrimitive(value)) ?: listOf(JsonPrimitive(value)))
    }

    private fun parseModel(obj: JsonObject): MediaModel? {
        val id = obj["model"]?.jsonPrimitive?.contentOrNull ?: return null
        val kindRaw = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "image"
        val kind = MediaKind.Image.fromProviderId(kindRaw)
        val params = collectParams(obj)
        val inputTypes = obj["inputTypes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val acceptsImage = params.any { it.key in IMAGE_REFERENCE_KEYS } || "image" in inputTypes
        val acceptsVideo = params.any { it.key in VIDEO_REFERENCE_KEYS } || "video" in inputTypes
        val acceptsAudio = params.any { it.key in AUDIO_REFERENCE_KEYS } || "audio" in inputTypes
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
            tiers = parseTiers(obj),
            params = params,
            promptLimit = parsePromptLimit(obj["note"]?.jsonPrimitive?.contentOrNull),
            task = obj["task"]?.jsonPrimitive?.contentOrNull,
            acceptsImageReference = acceptsImage,
            acceptsVideoReference = acceptsVideo,
            acceptsAudioReference = acceptsAudio,
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

    /** Price tiers: `{label, priceRub, when: {optKey: optValue}}`. */
    private fun parseTiers(obj: JsonObject): List<MediaTierPrice> {
        val arr = obj["tiers"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val price = o["priceRub"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val conditions = (o["when"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            MediaTierPrice(
                label = o["label"]?.jsonPrimitive?.contentOrNull,
                priceRub = price,
                whenConditions = conditions,
            )
        }
    }

    /** The documented per-request text limit: the largest «до N символов» mention in the note. */
    private fun parsePromptLimit(note: String?): Int? {
        val limits = PROMPT_LIMIT_REGEX.findAll(note.orEmpty()).mapNotNull { it.groupValues[1].toIntOrNull() }
        return limits.maxOrNull()
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
        private val AUDIO_REFERENCE_KEYS = setOf(
            "audio_url",
            "audio_base64",
            "audio_file_id",
        )

        /** «До 4096 символов за запрос» in catalog notes. */
        private val PROMPT_LIMIT_REGEX = Regex("""[Дд]о\s+(\d+)\s+символов""")

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
