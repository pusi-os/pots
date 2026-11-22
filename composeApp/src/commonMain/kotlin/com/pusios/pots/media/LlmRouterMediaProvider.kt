package com.pusios.pots.media

import com.pusios.pots.httpClient
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// LLM Router adapter. Contract: `docs/knowledge/media-providers/llm-router.md`
// (snapshot 2026-11-23, source of truth — llm-router.org/docs, раздел Images).
//
// Images only, and everything is synchronous: two endpoints, both POST
// (`GET` → 405 «Supported methods: POST»), no tasks, no polling:
//  - `POST /v1/images/generations` (JSON: model, prompt, n, size,
//    aspect_ratio, quality, response_format);
//  - `POST /v1/images/edits` (JSON composition `images:[{url}]` for
//    references; the mask lives only in the multipart+`gpt-image-*` branch,
//    which MediaJobRequest cannot express).
//
// The documented answer carries only `data[].b64_json` (no `url` field in the
// example) and no `usage` — the price of the job is unknown, the provider
// bills in USD while the abstraction is in RUB. The bytes are decoded into
// data URIs and wrapped into a local pending job, like the other sync
// adapters; `pollJob` never reaches the network.
class LlmRouterMediaProvider(
    private val baseUrl: String = "https://llm-router.org",
    /** Test seam: a pre-built client (MockEngine) replaces the real one. */
    clientOverride: HttpClient? = null,
) : MediaProvider {

    private val client = clientOverride ?: httpClient {
        install(HttpTimeout) {
            // The documented sync generation has no time limit; 570 s matches
            // AITUNNEL's sync image route (up to 9 minutes provider-side).
            requestTimeoutMillis = 570_000
            socketTimeoutMillis = 570_000
            connectTimeoutMillis = 30_000
        }
    }

    private val json = PlusVibeMediaProvider.JsonTolerant

    /** Results of sync submissions wrapped into a pending job; bounded like Polza's. */
    internal val localResults = mutableMapOf<String, MediaJobState>()

    /** Keeps one sync-wrapped result; evicts the oldest past [LOCAL_RESULT_LIMIT]. */
    private fun rememberLocalResult(jobId: String, state: MediaJobState) {
        localResults[jobId] = state
        while (localResults.size > LOCAL_RESULT_LIMIT) {
            localResults.remove(localResults.keys.first())
        }
    }

    override suspend fun listModels(apiKey: String): List<MediaModel> {
        val response = client.get("$baseUrl/v1/models") { authed(apiKey) }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            MediaLog.e("LLM Router catalog failed: HTTP ${response.status.value} body=${body.take(500)}")
            throw mapMediaHttpError(response.status.value, body)
        }
        val models = parseCatalog(body)
        MediaLog.i("LLM Router catalog: ${models.size} image models (of ${parsedIds(body)} listed)")
        return models
    }

    private fun parsedIds(body: String): Int = runCatching {
        (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray).orEmpty().size
    }.getOrDefault(0)

    /**
     * `{data: [{id}, …]}` — the documented answer lists ids and nothing else:
     * no modality, no prices, no params schema. Image models are kept from the
     * allowlist (the ids the docs name); everything else is a text/other
     * model this adapter cannot drive and stays out of the picker. A future
     * image model needs the allowlist (or `modality` from the undocumented
     * `/v1/pricing`) added here.
     */
    internal fun parseCatalog(body: String): List<MediaModel> {
        val root = json.parseToJsonElement(body).jsonObject
        val ids = (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { el -> (el as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            .toSet()
        return IMAGE_MODEL_IDS
            .filter { it in ids }
            .map { id ->
                MediaModel(
                    id = id,
                    kind = MediaKind.Image,
                    fromRub = null,
                    params = listOf(
                        MediaParamDef(
                            key = "n",
                            label = "Сколько картинок",
                            type = "number",
                            defaultValue = "1",
                        ),
                        MediaParamDef(
                            key = "size",
                            label = "Размер",
                            type = "select",
                            options = listOf("1024x1024", "1024x1536", "1536x1024").map { MediaCapValue(it) },
                        ),
                        MediaParamDef(
                            key = "aspect_ratio",
                            label = "Формат",
                            type = "select",
                            // Works on nano-banana and gpt-image-2.5; the
                            // other models may ignore or reject it.
                            options = listOf(
                                "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9", "auto",
                            ).map { MediaCapValue(it) },
                        ),
                        MediaParamDef(
                            key = "quality",
                            label = "Качество",
                            // The accepted set depends on the model
                            // (standard/hd or low/medium/high) — free input
                            // instead of chips that can 400.
                            type = "text",
                        ),
                    ),
                    // Both documented endpoints accept existing images.
                    acceptsImageReference = true,
                )
            }
    }

    /**
     * No price table: the only candidate route `GET /v1/pricing` exists but is
     * undocumented (its form is known only from our own DTO) — null until it
     * is confirmed with a key.
     */
    override suspend fun priceTable(apiKey: String, modelId: String): MediaPriceTable? = null

    /**
     * Local estimate only: no quote endpoint, and the image prices are not
     * published at all (USD in the cabinet, our abstraction is in RUB) — 0.0
     * is the honest "unknown", matching the Polza fallback.
     */
    override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote {
        MediaLog.d("LLM Router quote: model=${request.model} (no published prices → 0.0)")
        return MediaPriceQuote(priceRub = 0.0, quoteId = null)
    }

    override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
        MediaLog.i(
            "LLM Router submit: model=${request.model} opts=${request.opts.keys} " +
                "refs=${request.references.size} keyPresent=${apiKey.isNotBlank()} " +
                "idempotency=${request.idempotencyKey != null}",
        )
        val hasReferences = request.references.isNotEmpty()
        val response = if (hasReferences) {
            client.post("$baseUrl/v1/images/edits") {
                authed(apiKey)
                idempotency(request.idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody(buildEditsBody(request).toString())
            }
        } else {
            client.post("$baseUrl/v1/images/generations") {
                authed(apiKey)
                idempotency(request.idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody(buildGenerationsBody(request).toString())
            }
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            MediaLog.e("LLM Router submit failed: HTTP ${response.status.value} body=${body.take(500)}")
            throw mapMediaHttpError(response.status.value, body)
        }
        val done = parseImagesResponse(request, body)
        rememberLocalResult(done.jobId, done)
        val state = MediaJobState(
            jobId = done.jobId,
            status = MediaJobStatus.Processing,
            model = request.model,
            kind = MediaKind.Image,
        )
        MediaLog.i("LLM Router submitted: jobId=${state.jobId} status=${state.status} urls=${done.resultUrls.size}")
        return state
    }

    /** Documented `/v1/images/generations` body; the answer is asked as `b64_json` — the only documented shape. */
    internal fun buildGenerationsBody(request: MediaJobRequest): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["model"] = JsonPrimitive(request.model)
        map["prompt"] = JsonPrimitive(request.prompt)
        map["response_format"] = JsonPrimitive("b64_json")
        (request.opts["n"] ?: request.opts["max_images"])?.toIntOrNull()?.let { map["n"] = JsonPrimitive(it) }
        for (key in listOf("size", "aspect_ratio", "quality")) {
            request.opts[key]?.let { map[key] = JsonPrimitive(it) }
        }
        // maxPriceRub has no provider counterpart — silently ignored.
        return JsonObject(map)
    }

    /** JSON composition branch of `/v1/images/edits`: `images: [{url}]`, data URIs allowed. */
    internal fun buildEditsBody(request: MediaJobRequest): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["model"] = JsonPrimitive(request.model)
        map["prompt"] = JsonPrimitive(request.prompt)
        val images = request.references.mapNotNull { ref ->
            val url = ref.url?.takeIf { it.startsWith("https://") }
                ?: resolveMediaReferenceDataUri(ref)
            if (url == null) {
                MediaLog.w("LLM Router reference skipped: no readable url or data")
                return@mapNotNull null
            }
            JsonObject(mapOf("url" to JsonPrimitive(url)))
        }
        map["images"] = JsonArray(images)
        return JsonObject(map)
    }

    /**
     * Sync answer: `data[].url` (undocumented but kept for drift) or
     * `data[].b64_json` → data URI. No `usage` in the answer — the charge is
     * unknown, `priceRub` stays 0.
     */
    internal fun parseImagesResponse(request: MediaJobRequest, body: String): MediaJobState {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"] as? JsonArray
        if (data.isNullOrEmpty()) throw MediaException.Unknown("LLM Router image answer without data[]")
        val urls = data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            o["url"]?.jsonPrimitive?.contentOrNull
                ?: o["b64_json"]?.jsonPrimitive?.contentOrNull?.let { b64 -> "data:image/png;base64,$b64" }
        }
        return MediaJobState(
            jobId = localJobId(),
            status = MediaJobStatus.Success,
            model = request.model,
            kind = MediaKind.Image,
            resultUrls = urls,
            definitive = true,
        )
    }

    override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState {
        localResults[jobId]?.let {
            MediaLog.d("LLM Router poll: jobId=$jobId (local sync result) status=${it.status}")
            return it
        }
        throw MediaException.Unknown("LLM Router is synchronous: job $jobId has no provider-side state")
    }

    /** The bytes are already in the answer (`b64_json`); only a drifted URL answer downloads. */
    override suspend fun downloadResult(apiKey: String, url: String): ByteArray {
        decodeMediaDataUri(url)?.let {
            MediaLog.i("LLM Router download: inline ${it.size} bytes")
            return it
        }
        val bytes = client.get(url).body<ByteArray>()
        MediaLog.i("LLM Router download: ${bytes.size} bytes from ${url.take(80)}")
        return bytes
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authed(apiKey: String) {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }

    /** The docs' retry-safe header: a repeat with the same key and key pair never charges again. */
    private fun io.ktor.client.request.HttpRequestBuilder.idempotency(key: String?) {
        if (!key.isNullOrBlank()) header("Idempotency-Key", key)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun localJobId(): String = "llmrouter-local-" + Uuid.random().toString()

    companion object {
        /** Image ids the docs name; everything else in `/v1/models` is not media this adapter can drive. */
        private val IMAGE_MODEL_IDS = listOf("nano-banana", "gpt-image-1.5", "gpt-image-2.5")

        /** Sync-wrapped results kept for polling before the oldest is evicted. */
        private const val LOCAL_RESULT_LIMIT = 64
    }
}
