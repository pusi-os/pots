package com.pusios.pots.media

import com.pusios.pots.httpClient
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// GPTunnel adapter. Contract: `docs/knowledge/media-providers/gptunnel.md`
// (snapshot 2026-11-23, source of truth — docs.gptunnel.ru CreativeLab API v2).
//
// Routes (all require the key, including the catalog — live 401 without it):
//  - catalog `GET /api/v2/media/models?type=IMAGE|VIDEO|AUDIO` — models the key
//    may use, with the `params`/`inputs` schema and the price;
//  - quote `POST /api/v2/media/price` `{model, prompt?, params?, inputs?}` —
//    the exact RUB price for the combination, no charge, no quoteId;
//  - submit `POST /api/v2/media/tasks` `{model, prompt?, params?, inputs?,
//    idempotency_key?, wait?}` — one async task object for every kind;
//  - poll `GET /api/v2/media/tasks/:id` — queued/running/done/failed,
//    `result[].url` (48 h lifetime), `price`, `error`, `refunded`.
//
// `webhook_url` is not sent: there is no inbound endpoint the app could expose.
// `wait` is not sent either: polling covers the same path for image and video
// alike. Top-level body fields are validated strictly (unknown → 400 code 3),
// so only documented fields leave the device.
class GptunnelMediaProvider(
    private val baseUrl: String = "https://gptunnel.ru",
    /** Test seam: a pre-built client (MockEngine) replaces the real one. */
    clientOverride: HttpClient? = null,
) : MediaProvider {

    private val client = clientOverride ?: httpClient {
        install(HttpTimeout) {
            // The provider answers submit/poll quickly; only downloads from the
            // CDN can stall. 60 s matches the other async adapters.
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
        }
    }

    private val json = PlusVibeMediaProvider.JsonTolerant

    /** Catalog cache: kind/reference decisions, the local price table, params types. */
    private val modelsById = mutableMapOf<String, MediaModel>()

    /** Raw catalog cards by id, kept for price/params fields MediaModel cannot hold. */
    private val cardsById = mutableMapOf<String, JsonObject>()

    override suspend fun listModels(apiKey: String): List<MediaModel> {
        var firstError: Pair<Int, String>? = null
        val models = buildList {
            for (type in listOf("IMAGE", "VIDEO", "AUDIO")) {
                val response = client.get("$baseUrl/api/v2/media/models") {
                    parameter("type", type)
                    authed(apiKey)
                }
                val body = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    MediaLog.e("GPTunnel catalog $type failed: HTTP ${response.status.value} body=${body.take(300)}")
                    if (firstError == null) firstError = response.status.value to body
                    continue
                }
                addAll(parseCatalogGroup(body, type))
            }
        }
        // The catalog is key-gated (live 401 without the key): if every group
        // failed and nothing parsed, the empty list would silently hide a bad
        // or expired key — surface the first HTTP error through the shared map.
        if (models.isEmpty()) {
            firstError?.let { (status, body) -> throw mapMediaHttpError(status, body) }
        }
        MediaLog.i(
            "GPTunnel catalog: ${models.size} models (" +
                "${models.count { it.kind == MediaKind.Image }} image, " +
                "${models.count { it.kind == MediaKind.Video }} video, " +
                "${models.count { it.kind == MediaKind.Audio }} audio)",
        )
        return models
    }

    /** `{code: 0, models: [...]}` — one group per `type`; a drifted card drops itself. */
    internal fun parseCatalogGroup(body: String, type: String): List<MediaModel> {
        val root = json.parseToJsonElement(body).jsonObject
        val cards = (root["models"] as? JsonArray) ?: return emptyList()
        return cards
            .mapNotNull { element ->
                val card = element as? JsonObject ?: return@mapNotNull null
                val id = card["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                cardsById[id] = card
                runCatching { parseModel(id, card, type) }
                    .onFailure { MediaLog.w("GPTunnel model $id skipped: ${it.message}") }
                    .getOrNull()
            }
            .filter { it.id.isNotBlank() }
            .also { usable -> usable.forEach { modelsById[it.id] = it } }
    }

    private fun parseModel(id: String, card: JsonObject, type: String): MediaModel {
        val kind = MediaKind.Image.fromProviderId(
            card["type"]?.jsonPrimitive?.contentOrNull ?: type,
        )
        val price = card["price"]?.jsonPrimitive?.doubleOrNull
        val params = parseParams(card)
        return MediaModel(
            id = id,
            kind = kind,
            unit = unitLabel(card["price_unit"]?.jsonPrimitive?.contentOrNull),
            // Input-billed models (counted by prompt/image pixels) answer
            // price = 0 and carry no price_unit — there is no output rate to show.
            fromRub = price?.takeIf { it > 0.0 },
            // The catalog publishes a rate, not the final job price — only
            // `POST /media/price` returns the exact sum.
            priceStatus = if (price != null && price > 0.0) "estimate" else null,
            // `deprecated: true` means the removal date has passed: requests
            // run on the analog or answer 410. A future `deprecated_at` is
            // only an announcement and keeps the model usable.
            generationAvailable = card["deprecated"]?.jsonPrimitive?.booleanOrNull != true,
            params = params,
            acceptsImageReference = inputsKind(card, "image"),
            acceptsVideoReference = inputsKind(card, "video"),
            acceptsAudioReference = inputsKind(card, "audio"),
        )
    }

    /** `per_generation` → job total, the rest are rates the job multiplies. */
    private fun unitLabel(priceUnit: String?): String? = when (priceUnit) {
        "per_second" -> "за секунду"
        "per_megapixel" -> "за мегапиксель"
        "per_1k_tokens" -> "за 1K токенов"
        "per_generation" -> "за генерацию"
        else -> null
    }

    /** Any `inputs[]` entry of that kind makes the reference input accepted. */
    private fun inputsKind(card: JsonObject, kind: String): Boolean = (card["inputs"] as? JsonArray).orEmpty().any { entry ->
        (entry as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull == kind
    }

    /** `params[]` schema → defs; enum → select, number → number, the rest → text. */
    private fun parseParams(card: JsonObject): List<MediaParamDef> = (card["params"] as? JsonArray).orEmpty()
        .mapNotNull { element ->
            val schema = element as? JsonObject ?: return@mapNotNull null
            val key = schema["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val options = (schema["options"] as? JsonArray).orEmpty()
                .mapNotNull { option ->
                    val o = option as? JsonObject ?: return@mapNotNull null
                    val value = o["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    MediaCapValue(value, o["label"]?.jsonPrimitive?.contentOrNull ?: value)
                }
            val type = when (schema["type"]?.jsonPrimitive?.contentOrNull) {
                "enum" -> "select"
                "number", "integer" -> "number"
                "boolean" -> "boolean"
                else -> "text"
            }
            MediaParamDef(
                key = key,
                label = schema["label"]?.jsonPrimitive?.contentOrNull ?: key,
                type = type,
                defaultValue = schema["default"]?.jsonPrimitive?.contentOrNull,
                options = options,
                required = schema["required"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }

    /**
     * Local table from the cached catalog card: one row with the flat rate
     * (`per_generation`) or the unit rate (`per_second`/`per_megapixel`/
     * `per_1k_tokens`). The documented `pricing` object has no published
     * structure, so no per-combination matrix can be built. Models billed by
     * input (`price` = 0, no `price_unit`) have no table.
     */
    internal fun priceTableFromCache(modelId: String): MediaPriceTable? {
        val card = cardsById[modelId] ?: return null
        val price = card["price"]?.jsonPrimitive?.doubleOrNull
        // Input-billed models answer price = 0 and no price_unit — no table.
        if (price == null || price == 0.0 || card["price_unit"] == null) return null
        val unit = card["price_unit"]?.jsonPrimitive?.contentOrNull
        return MediaPriceTable(
            model = modelId,
            currency = "RUB",
            priceAffecting = emptyList(),
            defaults = modelsById[modelId]?.params.orEmpty()
                .mapNotNull { p -> p.defaultValue?.let { p.key to it } }
                .toMap(),
            rows = listOf(
                MediaPriceTableRow(
                    opts = emptyMap(),
                    price = price,
                    priceStatus = "estimate",
                    chargeBasis = if (unit == "per_generation" || unit == null) "job_total" else "unit_rate",
                ),
            ),
        )
    }

    override suspend fun priceTable(apiKey: String, modelId: String): MediaPriceTable? {
        priceTableFromCache(modelId)?.let { return it }
        listModels(apiKey)
        return priceTableFromCache(modelId)
    }

    override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote {
        MediaLog.d("GPTunnel quote: model=${request.model} params=${request.opts.keys} refs=${request.references.size}")
        if (modelsById.isEmpty()) listModels(apiKey)
        val body = buildTaskBody(request)
        val response = client.post("$baseUrl/api/v2/media/price") {
            authed(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            MediaLog.e("GPTunnel quote failed: HTTP ${response.status.value} body=${text.take(500)}")
            throw mapMediaHttpError(response.status.value, text)
        }
        return parseQuote(text)
    }

    /** `{code: 0, model, price, currency: "RUB", params: {applied…}}` — no quoteId. */
    internal fun parseQuote(body: String): MediaPriceQuote {
        val root = json.parseToJsonElement(body).jsonObject
        val price = root["price"]?.jsonPrimitive?.doubleOrNull
            ?: throw MediaException.Unknown("GPTunnel price answer without price")
        return MediaPriceQuote(priceRub = price, quoteId = null)
    }

    override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
        MediaLog.i(
            "GPTunnel submit: model=${request.model} params=${request.opts.keys} " +
                "refs=${request.references.size} keyPresent=${apiKey.isNotBlank()} " +
                "idempotency=${request.idempotencyKey != null}",
        )
        if (modelsById.isEmpty()) listModels(apiKey)
        val body = buildTaskBody(request, request.idempotencyKey)
        val response = client.post("$baseUrl/api/v2/media/tasks") {
            authed(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            MediaLog.e("GPTunnel submit failed: HTTP ${response.status.value} body=${text.take(500)}")
            throw mapMediaHttpError(response.status.value, text)
        }
        val state = parseTaskSubmit(request, text)
        MediaLog.i("GPTunnel submitted: jobId=${state.jobId} status=${state.status}")
        return state
    }

    /** `{model, prompt?, params?, inputs?, idempotency_key?}` — the strict documented body; only documented fields leave the device. */
    internal fun buildTaskBody(request: MediaJobRequest, idempotencyKey: String? = null): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["model"] = JsonPrimitive(request.model)
        if (request.prompt.isNotBlank()) map["prompt"] = JsonPrimitive(request.prompt)
        request.opts.takeIf { it.isNotEmpty() }?.let { map["params"] = paramsBody(request.model, it) }
        inputsBody(request)?.let { map["inputs"] = it }
        idempotencyKey?.let { map["idempotency_key"] = JsonPrimitive(it) }
        return JsonObject(map)
    }

    /**
     * Opts → the `params` object. Numbers from the cached schema go out as
     * JSON numbers — the schema types the value, and a string `5` for a
     * `number` param risks `400 code 27`.
     */
    private fun paramsBody(modelId: String, opts: Map<String, String>): JsonObject {
        val schema = (cardsById[modelId]?.get("params") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associate { it["key"]?.jsonPrimitive?.contentOrNull to (it["type"]?.jsonPrimitive?.contentOrNull) }
        return JsonObject(
            opts.map { (key, value) ->
                val element = when (schema[key]) {
                    "number" -> value.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
                    "integer" -> value.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
                    else -> JsonPrimitive(value)
                }
                key to element
            }.toMap(),
        )
    }

    /**
     * References → `{ "<role>": [url, …] }`; roles come from the cached
     * `inputs[]` schema (fallback: the kind name itself — an unknown role
     * fails the request with code 25, it is never silently dropped). https
     * stays a URL, the rest becomes an inline data URI (≤ 20 MB per file).
     */
    private fun inputsBody(request: MediaJobRequest): JsonObject? {
        if (request.references.isEmpty()) return null
        val schema = (cardsById[request.model]?.get("inputs") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
        fun role(kind: String): String = schema
            .firstOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == kind }
            ?.get("role")?.jsonPrimitive?.contentOrNull ?: kind
        val byRole = linkedMapOf<String, MutableList<String>>()
        for (ref in request.references) {
            val kind = if (ref.isVideo) "video" else "image"
            val url = ref.url?.takeIf { it.startsWith("https://") }
                ?: resolveMediaReferenceDataUri(ref)
            if (url == null) {
                MediaLog.w("GPTunnel reference skipped: no readable url or data")
                continue
            }
            byRole.getOrPut(role(kind)) { mutableListOf() } += url
        }
        if (byRole.isEmpty()) return null
        return JsonObject(byRole.mapValues { (_, urls) -> JsonArray(urls.map { JsonPrimitive(it) }) })
    }

    /** One task object for every kind: `{id, status, price, result…}`. */
    internal fun parseTaskSubmit(request: MediaJobRequest, body: String): MediaJobState {
        val root = json.parseToJsonElement(body).jsonObject
        val id = root["id"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank()) throw MediaException.Unknown("GPTunnel task answer without id")
        val kind = modelsById[request.model]?.kind
            ?: if (request.references.any { it.isVideo }) MediaKind.Video else MediaKind.Image
        // `wait` is never sent, so the answer is queued/running — never a
        // final object; the terminal states arrive through pollJob.
        return MediaJobState(
            jobId = id,
            status = MediaJobStatus.Processing,
            model = request.model,
            kind = kind,
        )
    }

    override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState {
        val response = client.get("$baseUrl/api/v2/media/tasks/$jobId") { authed(apiKey) }
        val body = response.bodyAsText()
        if (response.status.value == 404) throw MediaException.ModelNotFound("Job not found")
        if (response.status.value !in 200..299) {
            MediaLog.e("GPTunnel poll failed: jobId=$jobId HTTP ${response.status.value} body=${body.take(500)}")
            throw mapMediaHttpError(response.status.value, body)
        }
        return parseTaskPoll(jobId, body)
    }

    /**
     * `GET /tasks/:id` → [MediaJobState]. Branches on `status`, never on the
     * root `code` (it stays 0 even for a failed generation). `refunded` says
     * the failed job's money came back; the task object carries no `currency`
     * field — `price` is documented as RUB.
     */
    internal fun parseTaskPoll(jobId: String, body: String): MediaJobState {
        val root = json.parseToJsonElement(body).jsonObject
        val statusRaw = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val model = root["model"]?.jsonPrimitive?.contentOrNull
        val kind = model?.let { modelsById[it]?.kind }
        val state = when (statusRaw) {
            "done" -> MediaJobState(
                jobId = jobId,
                status = MediaJobStatus.Success,
                model = model,
                kind = kind,
                resultUrls = (root["result"] as? JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { it["url"]?.jsonPrimitive?.contentOrNull },
                priceRub = root["price"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                definitive = true,
            )

            "failed" -> MediaJobState(
                jobId = jobId,
                status = MediaJobStatus.Fail,
                model = model,
                kind = kind,
                failMsg = (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull,
                errorCode = (root["error"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: (root["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
                    ?: statusRaw,
                // `price` is documented as "списанная стоимость в рублях" with no
                // status binding — a refunded failure can still carry the sum.
                priceRub = root["price"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                refunded = root["refunded"]?.jsonPrimitive?.booleanOrNull == true,
                definitive = true,
            )

            else -> MediaJobState(jobId = jobId, status = MediaJobStatus.Processing, model = model, kind = kind)
        }
        MediaLog.d(
            "GPTunnel poll: jobId=$jobId status=${state.status} priceRub=${state.priceRub} " +
                "urls=${state.resultUrls.size} refunded=${state.refunded}",
        )
        return state
    }

    /**
     * `result[].url` points at `cdn.gptunnel.com`; the links live 48 h, so
     * callers download immediately. Publicity without the key is not
     * documented — plain GET, no key, like the other CDN downloads. Inline
     * data URIs decode locally.
     */
    override suspend fun downloadResult(apiKey: String, url: String): ByteArray {
        decodeMediaDataUri(url)?.let {
            MediaLog.i("GPTunnel download: inline ${it.size} bytes")
            return it
        }
        val bytes = client.get(url).body<ByteArray>()
        MediaLog.i("GPTunnel download: ${bytes.size} bytes from ${url.take(80)}")
        return bytes
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authed(apiKey: String) {
        // The docs accept the key with or without the Bearer prefix; Bearer
        // matches every other adapter.
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }
}
