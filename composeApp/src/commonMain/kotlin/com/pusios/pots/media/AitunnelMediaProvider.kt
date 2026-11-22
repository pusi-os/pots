package com.pusios.pots.media

import com.pusios.pots.httpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * AITUNNEL adapter. Contract: `docs/knowledge/media-providers/aitunnel.md`
 * (snapshot 2026-11-22, source of truth — the public catalogs + aitunnel.ru docs).
 *
 * Routes:
 *  - catalog `GET /public/aitunnel/models/images|videos` — public, no key;
 *  - images `POST /v1/images/generations` — **sync, up to 9 minutes**, answer
 *    carries `data[].b64_json` (no URL field) and `usage.cost_rub`; a provider
 *    timeout at 9 min is a `504` **without a charge**;
 *  - video `POST /v1/videos` → `202 {id, status: "pending"}`, poll
 *    `GET /v1/videos/{id}` (15–30 s), download `GET /v1/videos/{id}/content`
 *    — that one **requires the key**.
 *
 * **No idempotency key** (grep over all docs: 0 matches) — a retry is a new
 * paid job; [MediaJobRequest.idempotencyKey] is ignored. The video reserve is
 * worst-case, so `duration` and `size` must always be sent explicitly.
 */
class AitunnelMediaProvider(
    private val baseUrl: String = "https://api.aitunnel.ru",
) : MediaProvider {

    private val client = httpClient {
        install(HttpTimeout) {
            // Sync images block up to 9 minutes provider-side; 504 after that
            // means no charge, so cutting the client earlier would waste a
            // job the provider would still finish.
            requestTimeoutMillis = 570_000
            socketTimeoutMillis = 570_000
            connectTimeoutMillis = 30_000
        }
    }

    private val json = PlusVibeMediaProvider.JsonTolerant

    /** Catalog cache: kind and reference-capability decisions on submit. */
    private val modelsById = mutableMapOf<String, MediaModel>()

    /** Results of sync image submissions wrapped into a pending job. */
    internal val localResults = mutableMapOf<String, MediaJobState>()

    override suspend fun listModels(apiKey: String): List<MediaModel> {
        val models = buildList {
            for (group in listOf("images", "videos")) {
                val response = client.get("$baseUrl/public/aitunnel/models/$group")
                val body = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    MediaLog.e("AITUNNEL catalog $group failed: HTTP ${response.status.value}")
                    continue
                }
                addAll(parseCatalogGroup(body, group))
            }
        }
        MediaLog.i(
            "AITUNNEL catalog: ${models.size} models (" +
                "${models.count { it.kind == MediaKind.Image }} image, " +
                "${models.count { it.kind == MediaKind.Video }} video)",
        )
        return models
    }

    /**
     * Parses `{images: {id: card}}` / `{videos: {id: card}}`. One drifted card
     * drops itself, never the group.
     */
    internal fun parseCatalogGroup(body: String, group: String): List<MediaModel> {
        val root = json.parseToJsonElement(body).jsonObject
        val cards = root[group] as? JsonObject ?: return emptyList()
        val kind = if (group == "videos") MediaKind.Video else MediaKind.Image
        return cards
            .mapNotNull { (id, value) ->
                val card = value as? JsonObject ?: return@mapNotNull null
                cardsById[id] = card
                runCatching { parseModel(id, card, kind) }
                    .onFailure { MediaLog.w("AITUNNEL model $id skipped: ${it.message}") }
                    .getOrNull()
            }
            .filter { it.id.isNotBlank() && it.generationAvailable && !it.priceUnavailable }
            .also { usable ->
                usable.forEach { model ->
                    modelsById[model.id] = model
                    val card = cardsById[model.id]
                    if (kind == MediaKind.Video && card != null) {
                        val prices = (card["size_prices"] as? JsonObject).orEmpty()
                            .mapNotNull { (size, value) ->
                                (value as? JsonPrimitive)?.doubleOrNull?.let { size to it }
                            }
                            .toMap()
                        if (prices.isNotEmpty()) sizePrices[model.id] = prices
                    }
                }
            }
    }

    private fun parseModel(id: String, card: JsonObject, kind: MediaKind): MediaModel? {
        val isVideo = kind == MediaKind.Video
        // An images model that cannot generate from text (edit-only) offers no
        // submit the studio can drive — keep it out of the picker.
        if (!isVideo && card["supports_generation"]?.jsonPrimitive?.booleanOrNull == false) return null
        val modalitiesInput = ((card["modalities"] as? JsonObject)?.get("input") as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val params = if (isVideo) videoParams(card) else imageParams(card)
        return MediaModel(
            id = id,
            kind = kind,
            unit = if (isVideo) "за секунду" else "за картинку",
            note = card["short_description"]?.jsonPrimitive?.contentOrNull
                ?: card["description"]?.jsonPrimitive?.contentOrNull,
            fromRub = card[if (isVideo) "min_price_per_second" else "min_price_per_image"]?.jsonPrimitive?.doubleOrNull,
            priceStatus = "estimate",
            params = params,
            acceptsImageReference = when {
                isVideo -> (card["supports_input_references"]?.jsonPrimitive?.booleanOrNull == true) ||
                    ((card["supported_frame_images"] as? JsonArray).orEmpty().isNotEmpty())

                else -> (card["max_input_references"]?.jsonPrimitive?.intOrNull ?: 0) > 0 ||
                    card["supports_edit"]?.jsonPrimitive?.booleanOrNull == true
            },
            acceptsVideoReference = "video" in modalitiesInput,
        )
    }

    /** Catalog lists → `MediaParamDef` chips; video defaults = first allowed value. */
    private fun paramDef(key: String, label: String, card: JsonObject, valuesKey: String, defaultToFirst: Boolean): MediaParamDef? {
        val options = (card[valuesKey] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (options.isEmpty()) return null
        return MediaParamDef(
            key = key,
            label = label,
            type = "select",
            defaultValue = if (defaultToFirst) options.first() else null,
            options = options.map { MediaCapValue(it) },
        )
    }

    private fun imageParams(card: JsonObject): List<MediaParamDef> = listOf(
        paramDef("resolution", "Разрешение", card, "supported_resolutions", defaultToFirst = false),
        paramDef("aspect_ratio", "Формат", card, "supported_aspect_ratios", defaultToFirst = false),
        paramDef("quality", "Качество", card, "supported_quality", defaultToFirst = false),
        paramDef("output_format", "Формат файла", card, "supported_output_formats", defaultToFirst = false),
        paramDef("background", "Фон", card, "supported_background", defaultToFirst = false),
    ).filterNotNull() + MediaParamDef(
        key = "n",
        label = "Сколько картинок",
        type = "number",
        defaultValue = "1",
    )

    private fun videoParams(card: JsonObject): List<MediaParamDef> = listOf(
        paramDef("duration", "Длительность, с", card, "supported_durations", defaultToFirst = true),
        paramDef("resolution", "Разрешение", card, "supported_resolutions", defaultToFirst = true),
        paramDef("aspect_ratio", "Формат", card, "supported_aspect_ratios", defaultToFirst = true),
        paramDef("size", "Размер", card, "supported_sizes", defaultToFirst = false),
    ).filterNotNull() + MediaParamDef(
        key = "seed",
        label = "Seed",
        type = "number",
    )

    /**
     * Video table from `size_prices` — exact ₽/с per `WIDTHxHEIGHT`. Images
     * publish only a min…max range, no per-resolution prices → null.
     */
    internal fun priceTableFromCache(modelId: String): MediaPriceTable? {
        val model = modelsById[modelId] ?: return null
        val card = sizePrices[modelId] ?: return null
        if (model.kind != MediaKind.Video || card.isEmpty()) return null
        return MediaPriceTable(
            model = modelId,
            currency = "RUB",
            priceAffecting = listOf("size"),
            defaults = emptyMap(),
            rows = card.entries.map { (size, perSecond) ->
                MediaPriceTableRow(
                    opts = mapOf("size" to size),
                    price = perSecond,
                    priceStatus = "exact",
                    chargeBasis = "unit_rate",
                )
            }.sortedBy { it.opts.values.first() },
        )
    }

    /** Raw catalog cards by id, kept for price fields the MediaModel cannot hold. */
    private val cardsById = mutableMapOf<String, JsonObject>()

    /** `size_prices` of the parsed video cards, kept for the table and the quote. */
    private val sizePrices = mutableMapOf<String, Map<String, Double>>()

    override suspend fun priceTable(apiKey: String, modelId: String): MediaPriceTable? {
        priceTableFromCache(modelId)?.let { return it }
        listModels(apiKey)
        return priceTableFromCache(modelId)
    }

    /** Local estimate; AITUNNEL has no quote endpoint → `quoteId = null`. */
    internal fun quoteLocal(request: MediaJobRequest): MediaPriceQuote {
        val model = modelsById[request.model]
        return when (model?.kind) {
            MediaKind.Video -> {
                val perSecond = sizePrices[request.model]?.get(request.opts["size"])
                    ?: maxPricePerSecond(request.model)
                val duration = request.opts["duration"]?.toDoubleOrNull() ?: 1.0
                MediaPriceQuote(priceRub = perSecond * duration, quoteId = null)
            }

            else -> {
                // Worst case matches AITUNNEL's own reserve: max_price × n.
                val card = cardsById[request.model]
                val worst = card?.get("max_price_per_image")?.jsonPrimitive?.doubleOrNull
                    ?: model?.fromRub
                    ?: 0.0
                val n = (request.opts["n"] ?: request.opts["max_images"])?.toDoubleOrNull() ?: 1.0
                MediaPriceQuote(priceRub = worst * n, quoteId = null)
            }
        }
    }

    private fun maxPricePerSecond(modelId: String): Double {
        val card = cardsById[modelId]
        return card?.get("max_price_per_second")?.jsonPrimitive?.doubleOrNull
            ?: modelsById[modelId]?.fromRub
            ?: 0.0
    }

    override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote {
        if (modelsById.isEmpty()) listModels(apiKey)
        val quote = quoteLocal(request)
        MediaLog.d("AITUNNEL quote: model=${request.model} priceRub=${quote.priceRub} (local)")
        return quote
    }

    override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
        MediaLog.i(
            "AITUNNEL submit: model=${request.model} opts=${request.opts.keys} " +
                "refs=${request.references.size} keyPresent=${apiKey.isNotBlank()}",
        )
        if (modelsById.isEmpty()) listModels(apiKey)
        val isVideo = modelsById[request.model]?.kind == MediaKind.Video ||
            request.references.any { it.isVideo }
        val response = if (isVideo) {
            client.post("$baseUrl/v1/videos") {
                authed(apiKey)
                contentType(ContentType.Application.Json)
                setBody(buildVideosBody(request).toString())
            }
        } else {
            client.post("$baseUrl/v1/images/generations") {
                authed(apiKey)
                contentType(ContentType.Application.Json)
                setBody(buildImagesBody(request).toString())
            }
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            MediaLog.e("AITUNNEL submit failed: HTTP ${response.status.value} body=${body.take(500)}")
            throw mapMediaHttpError(response.status.value, body)
        }
        val state = if (isVideo) parseVideoSubmit(request, body) else parseImagesResponse(request, body)
        MediaLog.i("AITUNNEL submitted: jobId=${state.jobId} status=${state.status}")
        return state
    }

    /**
     * Sync image answer: `data[].b64_json` + `usage.cost_rub`. The bytes are
     * wrapped into a pending local job — the MediaProvider contract requires a
     * non-terminal submit result — and `pollJob` hands them out.
     */
    internal fun parseImagesResponse(request: MediaJobRequest, body: String): MediaJobState {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"] as? JsonArray
        if (data.isNullOrEmpty()) {
            // A pending shape would carry an id, but the documented image route
            // has none — an answer without data[] is an error.
            throw MediaException.Unknown("AITUNNEL image answer without data[]")
        }
        val urls = data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            o["b64_json"]?.jsonPrimitive?.contentOrNull?.let { b64 ->
                val mime = o["media_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                "data:$mime;base64,$b64"
            } ?: o["url"]?.jsonPrimitive?.contentOrNull
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

    /** `202 {id, polling_url, status: "pending"}` — no usage on this step. */
    internal fun parseVideoSubmit(request: MediaJobRequest, body: String): MediaJobState {
        val root = json.parseToJsonElement(body).jsonObject
        val id = root["id"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank()) throw MediaException.Unknown("AITUNNEL video answer without id")
        return MediaJobState(
            jobId = id,
            status = MediaJobStatus.Processing,
            model = request.model,
            kind = MediaKind.Video,
        )
    }

    override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState {
        localResults[jobId]?.let {
            MediaLog.d("AITUNNEL poll: jobId=$jobId (local sync result) status=${it.status}")
            return it
        }
        val response = client.get("$baseUrl/v1/videos/$jobId") { authed(apiKey) }
        val body = response.bodyAsText()
        if (response.status.value == 404) throw MediaException.ModelNotFound("Job not found")
        if (response.status.value !in 200..299) {
            MediaLog.e("AITUNNEL poll failed: jobId=$jobId HTTP ${response.status.value} body=${body.take(500)}")
            throw mapMediaHttpError(response.status.value, body)
        }
        return parseVideoPoll(jobId, body)
    }

    /** `GET /v1/videos/{id}`; `expired`/`cancelled` → `Fail` (`MediaJobStatus` has no such states). */
    internal fun parseVideoPoll(jobId: String, body: String): MediaJobState {
        val root = json.parseToJsonElement(body).jsonObject
        val statusRaw = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val state = when (statusRaw) {
            "completed" -> MediaJobState(
                jobId = jobId,
                status = MediaJobStatus.Success,
                model = root["model"]?.jsonPrimitive?.contentOrNull,
                kind = MediaKind.Video,
                resultUrls = (root["unsigned_urls"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                priceRub = (root["usage"] as? JsonObject)?.get("cost_rub")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                definitive = true,
            )

            "failed", "expired", "cancelled" -> MediaJobState(
                jobId = jobId,
                status = MediaJobStatus.Fail,
                model = root["model"]?.jsonPrimitive?.contentOrNull,
                kind = MediaKind.Video,
                failMsg = root["error"]?.jsonPrimitive?.contentOrNull,
                errorCode = statusRaw,
                definitive = true,
            )

            else -> MediaJobState(
                jobId = jobId,
                status = MediaJobStatus.Processing,
                model = root["model"]?.jsonPrimitive?.contentOrNull,
                kind = MediaKind.Video,
            )
        }
        MediaLog.d("AITUNNEL poll: jobId=$jobId status=${state.status} priceRub=${state.priceRub} urls=${state.resultUrls.size}")
        return state
    }

    /**
     * Image bytes arrive inline (`data:` URIs) and decode locally. Video
     * content URLs (`/v1/videos/{id}/content`) require the key — the only
     * route where Authorization is mandatory.
     */
    override suspend fun downloadResult(apiKey: String, url: String): ByteArray {
        decodeMediaDataUri(url)?.let {
            MediaLog.i("AITUNNEL download: inline ${it.size} bytes")
            return it
        }
        val bytes = client.get(url) { authed(apiKey) }.body<ByteArray>()
        MediaLog.i("AITUNNEL download: ${bytes.size} bytes from ${url.take(80)}")
        return bytes
    }

    /** Documented `/v1/images/generations` body + `input_references` for edits. */
    internal fun buildImagesBody(request: MediaJobRequest): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["model"] = JsonPrimitive(request.model)
        map["prompt"] = JsonPrimitive(request.prompt)
        for ((key, value) in request.opts) {
            when (key) {
                "n" -> value.toIntOrNull()?.let { map["n"] = JsonPrimitive(it) }
                "seed" -> value.toIntOrNull()?.let { map["seed"] = JsonPrimitive(it) }
                in IMAGE_BODY_KEYS -> map[key] = JsonPrimitive(value)
            }
        }
        val references = request.references.filter { !it.isVideo }.mapNotNull { ref -> inputReference(ref) }
        if (references.isNotEmpty()) map["input_references"] = JsonArray(references)
        return JsonObject(map)
    }

    /** Documented `/v1/videos` body; `duration` must be explicit — the reserve is worst-case without it. */
    internal fun buildVideosBody(request: MediaJobRequest): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["model"] = JsonPrimitive(request.model)
        map["prompt"] = JsonPrimitive(request.prompt)
        request.opts["duration"]?.toIntOrNull()?.let { map["duration"] = JsonPrimitive(it) }
        for (key in listOf("resolution", "aspect_ratio", "size")) {
            request.opts[key]?.let { map[key] = JsonPrimitive(it) }
        }
        request.opts["seed"]?.toIntOrNull()?.let { map["seed"] = JsonPrimitive(it) }
        when (request.opts["generate_audio"]?.lowercase()) {
            "true" -> map["generate_audio"] = JsonPrimitive(true)
            "false" -> map["generate_audio"] = JsonPrimitive(false)
        }
        val references = request.references.mapNotNull { ref -> inputReference(ref) }
        if (references.isNotEmpty()) map["input_references"] = JsonArray(references)
        return JsonObject(map)
    }

    /** `[{type: "image_url", image_url: {url}}]`; https stays a URL, the rest becomes a data URI. */
    private fun inputReference(ref: MediaReference): JsonObject? {
        val httpsUrl = ref.url?.takeIf { it.startsWith("https://") }
        val data = httpsUrl ?: resolveMediaReferenceDataUri(ref)
            ?: run {
                MediaLog.w("AITUNNEL reference skipped: no readable url or data")
                return null
            }
        val type = if (ref.isVideo) "video_url" else "image_url"
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive(type),
                "image_url" to JsonObject(mapOf("url" to JsonPrimitive(data))),
            ),
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authed(apiKey: String) {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun localJobId(): String = "aitunnel-local-" + Uuid.random().toString()

    companion object {
        /** String image-body fields the docs list; numeric ones are handled separately. */
        private val IMAGE_BODY_KEYS = setOf("resolution", "aspect_ratio", "size", "quality", "output_format", "background")
    }
}
