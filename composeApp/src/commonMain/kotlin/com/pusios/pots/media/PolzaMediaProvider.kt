package com.pusios.pots.media

import com.pusios.pots.httpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Polza.ai adapter. Contract: `docs/knowledge/media-providers/polza.md`
 * (snapshot 2026-11-22, source of truth — Polza's own OpenAPI + docs).
 *
 * Routes:
 *  - catalog `GET /v1/models?type=image|video` — public, no key;
 *  - plain images `POST /v2/images/generations` — sync, up to 120 s;
 *  - images with references and all video `POST /v1/media` `{model, input, async}`
 *    with `async: true` — returns `{id, status: "pending"}` immediately;
 *  - poll `GET /v1/media/{id}` — the only poll route (`/v2/media/{id}` is 404).
 *
 * **No idempotency key**: per the docs «Ключа идемпотентности нет: каждый
 * повтор — отдельный запрос». [MediaJobRequest.idempotencyKey] is ignored and
 * a retry after a timeout is a second paid request — the UI must say so.
 */
class PolzaMediaProvider(
    private val baseUrl: String = "https://polza.ai/api",
) : MediaProvider {

    private val client = httpClient {
        install(HttpTimeout) {
            // The sync image path blocks up to 120 s provider-side; 180 s matches
            // PlusVibeMediaProvider and leaves room for slow CDNs.
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
            connectTimeoutMillis = 30_000
        }
    }

    private val json = PlusVibeMediaProvider.JsonTolerant

    /** Catalog cache: reference/kind decisions and the local price table read it. */
    private val modelsById = mutableMapOf<String, MediaModel>()

    /**
     * Results of sync submissions wrapped into a pending job (the contract
     * requires submitJob to return a non-terminal state with a jobId even when
     * the provider answered with the finished result).
     */
    internal val localResults = mutableMapOf<String, MediaJobState>()

    override suspend fun listModels(apiKey: String): List<MediaModel> {
        val models = buildList {
            for (type in listOf("image", "video")) {
                val response = client.get("$baseUrl/v1/models") { parameter("type", type) }
                val body = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    MediaLog.e("Polza catalog $type failed: HTTP ${response.status.value}")
                    continue
                }
                addAll(parseCatalog(body))
            }
        }
        MediaLog.i(
            "Polza catalog: ${models.size} models (" +
                "${models.count { it.kind == MediaKind.Image }} image, " +
                "${models.count { it.kind == MediaKind.Video }} video)",
        )
        return models
    }

    /**
     * Parses one `/v1/models` response. Every model carries pricing and the
     * parameter schema in the same card, so the result also fills [modelsById].
     */
    internal fun parseCatalog(body: String): List<MediaModel> {
        val root = json.parseToJsonElement(body).jsonObject
        val models = root["data"]?.jsonArray ?: return emptyList()
        return models
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                runCatching { parseModel(obj) }
                    .onFailure { MediaLog.w("Polza model ${obj["id"]} skipped: ${it.message}") }
                    .getOrNull()
            }
            .filter { it.id.isNotBlank() && !it.priceUnavailable }
            .also { usable -> usable.forEach { modelsById[it.id] = it } }
    }

    private fun parseModel(obj: JsonObject): MediaModel? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val kindRaw = obj["type"]?.jsonPrimitive?.contentOrNull ?: "image"
        val kind = MediaKind.Image.fromProviderId(kindRaw)
        if (kind != MediaKind.Image && kind != MediaKind.Video) return null
        val parameters = obj["parameters"] as? JsonObject ?: JsonObject(emptyMap())
        val params = parseParams(parameters)
        val pricing = (obj["top_provider"] as? JsonObject)?.get("pricing") as? JsonObject
        val tiers = parseTiers(pricing)
        val unitParam = pricing?.get("unitParam")?.jsonPrimitive?.contentOrNull
        val inputModalities = ((obj["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        // Token-priced models (no tiers) keep an honest estimate badge but no
        // `from` price — the catalog publishes no per-image RUB for them.
        val tokenPriced = pricing != null && tiers.isEmpty() &&
            (pricing["prompt_per_million"] != null || pricing["image_output_per_million"] != null)
        val fromRub = tiers.mapNotNull { it.priceRub }.minOrNull()
        val promptLimit = ((parameters["prompt"] as? JsonObject)?.get("max_length") as? JsonPrimitive)?.intOrNull
        return MediaModel(
            id = id,
            kind = kind,
            unit = when (unitParam) {
                "duration" -> "за секунду"
                "max_images" -> "за картинку"
                else -> null
            },
            note = obj["short_description"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull,
            fromRub = fromRub,
            priceStatus = when {
                tiers.isNotEmpty() -> "exact"
                tokenPriced -> "estimate"
                else -> null
            },
            priceUnavailable = tiers.isEmpty() && !tokenPriced,
            tiers = tiers,
            params = params,
            promptLimit = promptLimit,
            acceptsImageReference = "image" in inputModalities || parameters.containsKey("images"),
            acceptsVideoReference = "video" in inputModalities || parameters.containsKey("videos"),
        )
    }

    /** `parameters: { "<key>": {type, required, values, default, max_length} }` → defs; `prompt` becomes [MediaModel.promptLimit]. */
    private fun parseParams(parameters: JsonObject): List<MediaParamDef> = parameters
        .mapNotNull { (key, value) ->
            if (key == "prompt") return@mapNotNull null
            val schema = value as? JsonObject ?: return@mapNotNull null
            val options = (schema["values"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .map { MediaCapValue(it) }
            val type = when {
                options.isNotEmpty() -> "select"
                schema.containsKey("min") || schema.containsKey("max") -> "number"
                (schema["type"]?.jsonPrimitive?.contentOrNull) == "boolean" -> "boolean"
                else -> "text"
            }
            MediaParamDef(
                key = key,
                label = key,
                type = type,
                defaultValue = schema["default"]?.jsonPrimitive?.contentOrNull,
                options = options,
                required = schema["required"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }

    /** `tiers: [{conditions: ["resolution=720p"], cost_rub: "21.375"}]` → [MediaTierPrice]. */
    private fun parseTiers(pricing: JsonObject?): List<MediaTierPrice> {
        val arr = (pricing?.get("tiers") as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val price = o["cost_rub"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val conditions = (o["conditions"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .mapNotNull { condition ->
                    val key = condition.substringBefore('=', "").trim()
                    val value = condition.substringAfter('=', "").trim()
                    if (key.isBlank()) null else key to value
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.toSet() }
            MediaTierPrice(label = null, priceRub = price, whenConditions = conditions)
        }
    }

    /**
     * Local table from the cached catalog card — Polza has no price-table
     * endpoint, and the catalog is already in memory, so no network call
     * happens here at all. Null when the model was never listed (the caller
     * then falls back to the network path, which for Polza means load catalog).
     */
    internal fun priceTableFromCache(modelId: String): MediaPriceTable? {
        val model = modelsById[modelId] ?: return null
        if (model.tiers.isEmpty()) return null
        return MediaPriceTable(
            model = modelId,
            currency = "RUB",
            priceAffecting = model.tiers.flatMap { it.whenConditions.keys }.distinct(),
            defaults = model.params.mapNotNull { p -> p.defaultValue?.let { p.key to it } }.toMap(),
            rows = model.tiers.map { tier ->
                MediaPriceTableRow(
                    opts = tier.whenConditions.mapValues { (_, values) -> values.firstOrNull().orEmpty() },
                    price = tier.priceRub,
                    priceStatus = "exact",
                    chargeBasis = if (model.unit != null) "unit_rate" else "job_total",
                )
            },
        )
    }

    override suspend fun priceTable(apiKey: String, modelId: String): MediaPriceTable? {
        priceTableFromCache(modelId)?.let { return it }
        listModels(apiKey)
        return priceTableFromCache(modelId)
    }

    /** Local tier-based estimate; Polza has no quote endpoint → `quoteId = null`. */
    internal fun quoteLocal(request: MediaJobRequest): MediaPriceQuote {
        val model = modelsById[request.model]
        val tiers = model?.tiers.orEmpty()
        val tier = if (tiers.isEmpty()) {
            null
        } else {
            tiers.firstOrNull { t -> t.whenConditions.all { (key, values) -> request.opts[key].orEmpty() in values } }
                ?: tiers.firstOrNull { it.whenConditions.isEmpty() }
        }
        var price = tier?.priceRub
        if (price != null && model?.unit != null) {
            // unitParam multiplier: `duration` from opts, `max_images` = n.
            val units = request.opts["duration"]?.toDoubleOrNull()
                ?: request.opts["max_images"]?.toDoubleOrNull()
                ?: request.opts["n"]?.toDoubleOrNull()
                ?: 1.0
            price = price * units
        }
        val estimate = price ?: model?.fromRub ?: 0.0
        return MediaPriceQuote(priceRub = estimate, quoteId = null)
    }

    override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote {
        if (modelsById.isEmpty()) listModels(apiKey)
        val quote = quoteLocal(request)
        MediaLog.d("Polza quote: model=${request.model} priceRub=${quote.priceRub} (local)")
        return quote
    }

    override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
        MediaLog.i(
            "Polza submit: model=${request.model} opts=${request.opts.keys} " +
                "refs=${request.references.size} keyPresent=${apiKey.isNotBlank()}",
        )
        if (modelsById.isEmpty()) listModels(apiKey)
        val kind = modelsById[request.model]?.kind
        val hasVideoRef = request.references.any { it.isVideo }
        val hasImageRef = request.references.any { !it.isVideo }
        val state = when {
            kind == MediaKind.Video || hasVideoRef || hasImageRef ||
                request.opts.keys.any { it !in V2_IMAGE_KEYS } -> {
                val body = buildMediaBody(request)
                val response = client.post("$baseUrl/v1/media") {
                    authed(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                val text = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    MediaLog.e("Polza media submit failed: HTTP ${response.status.value} body=${text.take(500)}")
                    throw mapMediaHttpError(response.status.value, text)
                }
                parseMediaSubmit(request, text)
            }

            else -> {
                val body = buildImagesBody(request)
                val response = client.post("$baseUrl/v2/images/generations") {
                    authed(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                val text = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    MediaLog.e("Polza images submit failed: HTTP ${response.status.value} body=${text.take(500)}")
                    throw mapMediaHttpError(response.status.value, text)
                }
                parseSyncImageResponse(request, text)
            }
        }
        MediaLog.i("Polza submitted: jobId=${state.jobId} status=${state.status}")
        return state
    }

    /**
     * `/v2/images/generations` answers synchronously. Both documented outcomes
     * must work: a finished `data[]` is wrapped into a pending local job (the
     * `MediaProvider` contract forbids terminal submit results), and a
     * `pending` + id answer passes through as a normal pollable job.
     */
    internal fun parseSyncImageResponse(request: MediaJobRequest, body: String): MediaJobState {
        val root = json.parseToJsonElement(body).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        val data = root["data"] as? JsonArray
        if (status == null && !data.isNullOrEmpty()) {
            val urls = data.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                o["url"]?.jsonPrimitive?.contentOrNull
                    ?: o["b64_json"]?.jsonPrimitive?.contentOrNull?.let { b64 ->
                        val mime = o["media_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                        "data:$mime;base64,$b64"
                    }
            }
            val price = (root["usage"] as? JsonObject)?.get("cost_rub")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val done = MediaJobState(
                jobId = localJobId(),
                status = MediaJobStatus.Success,
                model = request.model,
                kind = MediaKind.Image,
                resultUrls = urls,
                priceRub = price,
                definitive = true,
            )
            localResults[done.jobId] = done
            return MediaJobState(
                jobId = done.jobId,
                status = MediaJobStatus.Processing,
                model = request.model,
                kind = MediaKind.Image,
            )
        }
        val id = root["id"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank()) throw MediaException.Unknown("Polza sync answer without data[] and without id")
        return MediaJobState(
            jobId = id,
            status = MediaJobStatus.Processing,
            model = request.model,
            kind = MediaKind.Image,
        )
    }

    /** `POST /v1/media` answer: `{id, status}` (we always send `async: true`). */
    internal fun parseMediaSubmit(request: MediaJobRequest, body: String): MediaJobState {
        val root = json.parseToJsonElement(body).jsonObject
        val id = root["id"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank()) throw MediaException.Unknown("Polza media answer without id")
        val kind = modelsById[request.model]?.kind
            ?: if (request.references.any { it.isVideo }) MediaKind.Video else MediaKind.Image
        return MediaJobState(
            jobId = id,
            status = MediaJobStatus.Processing,
            model = request.model,
            kind = kind,
        )
    }

    override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState {
        localResults[jobId]?.let {
            MediaLog.d("Polza poll: jobId=$jobId (local sync result) status=${it.status}")
            return it
        }
        val response = client.get("$baseUrl/v1/media/$jobId") { authed(apiKey) }
        val body = response.bodyAsText()
        if (response.status.value == 404) throw MediaException.ModelNotFound("Job not found")
        if (response.status.value !in 200..299) {
            MediaLog.e("Polza poll failed: jobId=$jobId HTTP ${response.status.value} body=${body.take(500)}")
            throw mapMediaHttpError(response.status.value, body)
        }
        return parseMediaStatus(jobId, body)
    }

    /** `GET /v1/media/{id}` → [MediaJobState]; `cancelled` maps to `Fail` (`MediaJobStatus` has no Cancelled). */
    internal fun parseMediaStatus(jobId: String, body: String): MediaJobState {
        val root = json.parseToJsonElement(body).jsonObject
        val statusRaw = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val data = root["data"] as? JsonObject
        val usage = root["usage"] as? JsonObject
        val error = root["error"] as? JsonObject
        val state = when (statusRaw) {
            "completed" -> MediaJobState(
                jobId = jobId,
                status = MediaJobStatus.Success,
                model = root["model"]?.jsonPrimitive?.contentOrNull,
                resultUrls = listOfNotNull(
                    data?.get("url")?.jsonPrimitive?.contentOrNull
                        ?: data?.get("b64_json")?.jsonPrimitive?.contentOrNull?.let { b64 ->
                            val mime = data?.get("media_type")?.jsonPrimitive?.contentOrNull ?: "image/png"
                            "data:$mime;base64,$b64"
                        },
                ),
                priceRub = usage?.get("cost_rub")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                definitive = true,
            )

            "failed", "cancelled" -> MediaJobState(
                jobId = jobId,
                status = MediaJobStatus.Fail,
                model = root["model"]?.jsonPrimitive?.contentOrNull,
                failMsg = error?.get("message")?.jsonPrimitive?.contentOrNull,
                errorCode = error?.get("code")?.jsonPrimitive?.contentOrNull ?: statusRaw,
                definitive = true,
            )

            else -> MediaJobState(jobId = jobId, status = MediaJobStatus.Processing, model = root["model"]?.jsonPrimitive?.contentOrNull)
        }
        MediaLog.d("Polza poll: jobId=$jobId status=${state.status} priceRub=${state.priceRub} urls=${state.resultUrls.size}")
        return state
    }

    /**
     * `data.url` points at the Polza CDN (7-day lifetime — callers download
     * immediately). Plain `GET`, no key per the docs; inline `data:` URIs
     * (from `b64_json` answers) decode locally.
     */
    override suspend fun downloadResult(apiKey: String, url: String): ByteArray {
        decodeMediaDataUri(url)?.let {
            MediaLog.i("Polza download: inline ${it.size} bytes")
            return it
        }
        val bytes = client.get(url).body<ByteArray>()
        MediaLog.i("Polza download: ${bytes.size} bytes from ${url.take(80)}")
        return bytes
    }

    /** `{model, prompt, n, size, …}` — the documented `/v2` body, `response_format: "url"`. */
    internal fun buildImagesBody(request: MediaJobRequest): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["model"] = JsonPrimitive(request.model)
        map["prompt"] = JsonPrimitive(request.prompt)
        map["response_format"] = JsonPrimitive("url")
        (request.opts["n"] ?: request.opts["max_images"])?.toIntOrNull()?.let { map["n"] = JsonPrimitive(it) }
        for (key in listOf("size", "quality", "style", "output_format", "background", "output_compression")) {
            request.opts[key]?.let { map[key] = JsonPrimitive(it) }
        }
        request.maxPriceRub?.let { max ->
            map["provider"] = JsonObject(mapOf("max_price" to JsonObject(mapOf("image" to JsonPrimitive(max)))))
        }
        return JsonObject(map)
    }

    /** `{model, input: {prompt, …opts, images[], videos[]}, async: true}` — the universal media path. */
    internal fun buildMediaBody(request: MediaJobRequest): JsonObject {
        val input = mutableMapOf<String, JsonElement>()
        input["prompt"] = JsonPrimitive(request.prompt)
        for ((key, value) in request.opts) {
            when (key) {
                in NUMERIC_INPUT_KEYS -> value.toIntOrNull()?.let { input[key] = JsonPrimitive(it) }
                else -> input[key] = JsonPrimitive(value)
            }
        }
        val images = request.references.filter { !it.isVideo }.mapNotNull { ref -> mediaInputEntry(ref) }
        if (images.isNotEmpty()) input["images"] = JsonArray(images)
        val videos = request.references.filter { it.isVideo }.mapNotNull { ref -> mediaInputEntry(ref) }
        if (videos.isNotEmpty()) input["videos"] = JsonArray(videos)
        val map = mutableMapOf<String, JsonElement>()
        map["model"] = JsonPrimitive(request.model)
        map["input"] = JsonObject(input)
        map["async"] = JsonPrimitive(true)
        request.maxPriceRub?.let { max ->
            val duration = request.opts["duration"]?.toDoubleOrNull()
            if (duration != null && duration > 0) {
                map["provider"] = JsonObject(
                    mapOf("max_price" to JsonObject(mapOf("video_per_second" to JsonPrimitive(max / duration)))),
                )
            }
        }
        return JsonObject(map)
    }

    /** `{type: "url"|"base64", data: …}` for one reference (https stays a URL, the rest becomes base64). */
    private fun mediaInputEntry(ref: MediaReference): JsonObject? {
        val httpsUrl = ref.url?.takeIf { it.startsWith("https://") }
        val data = httpsUrl ?: resolveMediaReferenceDataUri(ref)
            ?: run {
                MediaLog.w("Polza reference skipped: no readable url or data")
                return null
            }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive(if (httpsUrl != null) "url" else "base64"),
                "data" to JsonPrimitive(data),
            ),
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authed(apiKey: String) {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun localJobId(): String = "polza-local-" + Uuid.random().toString()

    companion object {
        /** Polza `/v1/media` input keys that must be numbers (the rest stay strings). */
        private val NUMERIC_INPUT_KEYS = setOf("max_images", "seed", "n")

        /**
         * Opts that the documented `/v2/images/generations` body accepts.
         * Anything else (`aspect_ratio`, `image_resolution`, `mask_url`, …)
         * routes the request to `/v1/media`, whose `input` supports it.
         */
        private val V2_IMAGE_KEYS = setOf(
            "n", "max_images", "size", "quality", "style", "output_format", "background", "output_compression",
        )
    }
}
