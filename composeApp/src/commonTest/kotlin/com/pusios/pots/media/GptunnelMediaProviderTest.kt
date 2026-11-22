package com.pusios.pots.media

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GPTunnel adapter. Fixtures follow the contract
 * (`docs/knowledge/media-providers/gptunnel.md`, snapshot 2026-11-23): the
 * catalog carries `params`/`inputs` schemas and a rate price, the task object
 * branches on `status`, a failed task answers with root `code: 0` and
 * `refunded`. Every catalog/quote/task route requires the key (live 401
 * without it) — the Authorization header must always be sent. No network, no
 * paid calls.
 */
class GptunnelMediaProviderTest {

    // Contract shapes: per_second video rate, per_generation audio price,
    // an input-billed image model (price = 0, no price_unit) and a deprecated
    // model with an analog.
    private val catalog = """
        {"code":0,"models":[
          {"id":"kling-v3","type":"VIDEO","price":42,"price_unit":"per_second","discount_percent":10,
           "prompt":"required",
           "params":[
             {"key":"resolution","type":"enum","label":"Разрешение","default":"1080p",
              "options":[{"value":"720p","label":"720p"},{"value":"1080p","label":"1080p"}]},
             {"key":"duration","type":"integer","label":"Длительность, с","min":1,"max":10,"step":1,"default":5}],
           "inputs":[{"key":"frame","kind":"image","role":"first_frame","required":false,
                      "count":{"min":0,"max":1},"formats":["jpg","png"]}]},
          {"id":"gpt-image-2","type":"IMAGE","price":0,"prompt":"optional","params":[],"inputs":[]},
          {"id":"suno-v5","type":"AUDIO","price":35,"price_unit":"per_generation","prompt":"required","params":[],"inputs":[]},
          {"id":"old-model","type":"IMAGE","price":9,"price_unit":"per_generation","deprecated":true,
           "deprecated_at":"2026-01-01","deprecation_redirect_to":"gpt-image-2","prompt":"optional","params":[],"inputs":[]}
        ]}
    """.trimIndent()

    private val taskSubmitted = """
        {"code":0,"id":"6a61e021a6e77553a989c41d","model":"kling-v3","status":"queued",
         "price":0,"result":[],"created_at":1784799265}
    """.trimIndent()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Records every request; the lambda picks the answer by path and method. */
    private fun engine(
        requests: MutableList<io.ktor.client.request.HttpRequestData> = mutableListOf(),
        handler: io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ): MockEngine = MockEngine { request ->
        requests += request
        handler(request)
    }

    // `respond` is an extension on the MockEngine responder scope
    // (MockRequestHandleScope, ktor-client-mock 3.6) — the helper rides that
    // scope so the call sites stay terse.
    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(content = content, status = status, headers = jsonHeaders)

    private fun bodyText(request: io.ktor.client.request.HttpRequestData): String =
        (request.body as? TextContent)?.text.orEmpty()

    @Test
    fun listModelsSendsTheAuthorizationHeaderOnEveryCatalogCall() = runTest {
        // Both catalogs are key-gated (live 401 without it, contract §Base URLs)
        // — the header must be present on each of the three type queries.
        val paths = mutableListOf<String>()
        val authHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            authHeaders += request.headers[HttpHeaders.Authorization]
            respondJson(catalog)
        }
        val models = GptunnelMediaProvider(clientOverride = HttpClient(engine)).listModels("sk-test")
        assertEquals(3, paths.size)
        assertTrue(paths.all { it == "/api/v2/media/models" })
        assertEquals(listOf("Bearer sk-test", "Bearer sk-test", "Bearer sk-test"), authHeaders)
        assertEquals(listOf("kling-v3", "gpt-image-2", "suno-v5", "old-model"), models.map { it.id })
    }

    @Test
    fun catalogParsesSchemasPricesUnitsAndDeprecation() {
        val provider = GptunnelMediaProvider()
        val models = provider.parseCatalogGroup(catalog, "VIDEO") + provider.parseCatalogGroup(catalog, "AUDIO")
        val kling = models.first { it.id == "kling-v3" }
        assertEquals(MediaKind.Video, kling.kind)
        assertEquals("за секунду", kling.unit)
        assertEquals(42.0, kling.fromRub)
        assertEquals("estimate", kling.priceStatus)
        assertEquals(
            listOf("720p", "1080p"),
            kling.params.first { it.key == "resolution" }.options.map { it.value },
        )
        assertEquals("1080p", kling.params.first { it.key == "resolution" }.defaultValue)
        assertEquals("number", kling.params.first { it.key == "duration" }.type)
        assertTrue(kling.acceptsImageReference)
        val suno = models.first { it.id == "suno-v5" }
        assertEquals(MediaKind.Audio, suno.kind)
        assertEquals("за генерацию", suno.unit)
        val old = models.first { it.id == "old-model" }
        // deprecated: true means the removal date has passed — out of the picker.
        assertEquals(false, old.generationAvailable)
    }

    @Test
    fun priceTableFromTheCachedCatalogHitsNoNetwork() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respondJson(catalog)
        }
        val cached = GptunnelMediaProvider(clientOverride = HttpClient(engine))
        cached.parseCatalogGroup(catalog, "VIDEO")
        val table = cached.priceTable("sk-test", "kling-v3")
        assertTrue(table != null)
        assertEquals(0, calls)
        // The single rate row: unit_rate for per_second, estimate status.
        val row = table.rows.single()
        assertEquals(42.0, row.price)
        assertEquals("estimate", row.priceStatus)
        assertEquals("unit_rate", row.chargeBasis)
        // The input-billed model (price = 0, no price_unit) has no table.
        cached.parseCatalogGroup(catalog, "IMAGE")
        assertNull(cached.priceTableFromCache("gpt-image-2"))
    }

    @Test
    fun quotePriceUsesThePriceEndpointAndReturnsTheRubSum() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = engine(requests) { request ->
            when {
                request.method.value == "GET" -> respondJson(catalog)
                request.url.encodedPath == "/api/v2/media/price" -> respondJson(
                    """{"code":0,"model":"kling-v3","price":210,"currency":"RUB",
                        "params":{"resolution":"1080p","duration":5,"aspect_ratio":"16:9"}}""",
                )
                else -> respondJson("""{"code":99,"message":"API method not found"}""", HttpStatusCode.NotFound)
            }
        }
        val quote = GptunnelMediaProvider(clientOverride = HttpClient(engine)).quotePrice(
            "sk-test",
            MediaJobRequest(model = "kling-v3", prompt = "лиса", opts = mapOf("duration" to "5", "resolution" to "1080p")),
        )
        assertEquals(210.0, quote.priceRub)
        assertNull(quote.quoteId)
        val post = requests.last()
        assertEquals("/api/v2/media/price", post.url.encodedPath)
        val body = bodyText(post)
        // Schema-typed params go out as JSON numbers, strings stay strings.
        assertTrue(body.contains("\"duration\":5"), "body was $body")
        assertTrue(body.contains("\"resolution\":\"1080p\""), "body was $body")
    }

    @Test
    fun submitJobSendsIdempotencyKeyAndParsesTheTaskAnswer() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = engine(requests) { request ->
            when {
                request.method.value == "GET" -> respondJson(catalog)
                request.url.encodedPath == "/api/v2/media/tasks" -> respondJson(taskSubmitted)
                else -> respondJson("""{"code":99,"message":"unexpected"}""", HttpStatusCode.NotFound)
            }
        }
        val state = GptunnelMediaProvider(clientOverride = HttpClient(engine)).submitJob(
            "sk-test",
            MediaJobRequest(
                model = "kling-v3",
                prompt = "лиса",
                idempotencyKey = "local-abc",
                references = listOf(MediaReference(url = "https://example.com/frame.png")),
            ),
        )
        assertEquals(MediaJobStatus.Processing, state.status)
        assertEquals("6a61e021a6e77553a989c41d", state.jobId)
        assertEquals(MediaKind.Video, state.kind)
        val post = requests.last()
        assertEquals("/api/v2/media/tasks", post.url.encodedPath)
        val body = bodyText(post)
        assertTrue(body.contains("\"idempotency_key\":\"local-abc\""), "body was $body")
        // webhook_url and wait are never sent: no inbound endpoint, polling covers it.
        assertTrue(!body.contains("webhook_url"), "body was $body")
        assertTrue(!body.contains("wait"), "body was $body")
        // The reference goes into `inputs` under the role from the model schema.
        assertTrue(body.contains("\"inputs\":{\"first_frame\":[\"https://example.com/frame.png\"]}"), "body was $body")
    }

    @Test
    fun pollJobMapsRunningDoneAndFailed() = runTest {
        val provider = GptunnelMediaProvider()
        val queued = provider.parseTaskPoll(
            "j1",
            """{"code":0,"id":"j1","model":"kling-v3","status":"running","price":0,"result":[]}""",
        )
        assertEquals(MediaJobStatus.Processing, queued.status)
        val done = provider.parseTaskPoll(
            "j1",
            """{"code":0,"id":"j1","model":"kling-v3","status":"done","price":210,
                "result":[{"url":"https://cdn.gptunnel.com/a.mp4"},{"id":"m1","url":"https://cdn.gptunnel.com/b.mp4"}]}""",
        )
        assertEquals(MediaJobStatus.Success, done.status)
        assertEquals(
            listOf("https://cdn.gptunnel.com/a.mp4", "https://cdn.gptunnel.com/b.mp4"),
            done.resultUrls,
        )
        assertEquals(210.0, done.priceRub)
        assertTrue(done.isTerminal)
        // Root code stays 0 for a failed task — branch on status/error, not code.
        // `price` has no status binding (contract §Получение задачи): a refunded
        // failure carries the charged sum, which the refund line needs.
        val failed = provider.parseTaskPoll(
            "j1",
            """{"code":0,"id":"j1","model":"kling-v3","status":"failed","price":210,
                "error":{"code":1001,"title":"SENSITIVE_CONTENT","message":"Sensitive content has been detected"},
                "refunded":true}""",
        )
        assertEquals(MediaJobStatus.Fail, failed.status)
        assertEquals("SENSITIVE_CONTENT", failed.errorCode)
        assertEquals("Sensitive content has been detected", failed.failMsg)
        assertEquals(210.0, failed.priceRub)
        assertTrue(failed.refunded)
        assertTrue(failed.isTerminal)
        // The documented failed example carries no price at all — no invented sum.
        val failedNoPrice = provider.parseTaskPoll(
            "j2",
            """{"code":0,"id":"j2","model":"kling-v3","status":"failed",
                "error":{"code":1001,"title":"SENSITIVE_CONTENT","message":"Sensitive content has been detected"},
                "refunded":true}""",
        )
        assertEquals(0.0, failedNoPrice.priceRub)
        assertTrue(failedNoPrice.refunded)
    }

    @Test
    fun listModelsSurfacesAuthErrorWhenEveryGroupFails() = runTest {
        // The catalog is key-gated: a bad/expired key answers 401 on all three
        // type queries — the empty-list path must not swallow it (contract §Base URLs).
        val engine = MockEngine { _ ->
            respondJson("""{"error":{"message":"Unauthorized"}}""", HttpStatusCode.Unauthorized)
        }
        val error = runCatching {
            GptunnelMediaProvider(clientOverride = HttpClient(engine)).listModels("bad-key")
        }.exceptionOrNull()
        assertIs<MediaException.InvalidKey>(error)
    }

    @Test
    fun submitWith402ThrowsInsufficientBalance() = runTest {
        val engine = MockEngine { request ->
            when {
                request.method.value == "GET" -> respondJson(catalog)
                else -> respondJson(
                    """{"code":5,"message":"Insufficient balance, check account balance."}""",
                    HttpStatusCode.PaymentRequired,
                )
            }
        }
        val error = runCatching {
            GptunnelMediaProvider(clientOverride = HttpClient(engine)).submitJob(
                "sk-test",
                MediaJobRequest(model = "kling-v3", prompt = "лиса"),
            )
        }.exceptionOrNull()
        assertIs<MediaException.InsufficientBalance>(error)
    }

    @Test
    fun poll404ThrowsModelNotFound() = runTest {
        val engine = MockEngine { _ ->
            respondJson("""{"code":22,"message":"task not found"}""", HttpStatusCode.NotFound)
        }
        val error = runCatching {
            GptunnelMediaProvider(clientOverride = HttpClient(engine)).pollJob("sk-test", "nope")
        }.exceptionOrNull()
        assertIs<MediaException.ModelNotFound>(error)
    }

    @Test
    fun downloadResultDecodesInlineDataAndFetchesCdnUrls() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respond("MP4BYTES", HttpStatusCode.OK)
        }
        val provider = GptunnelMediaProvider(clientOverride = HttpClient(engine))
        // Data URIs decode locally — no network call.
        assertEquals("hello", provider.downloadResult("", "data:video/mp4;base64,aGVsbG8=").decodeToString())
        assertEquals("MP4BYTES", provider.downloadResult("", "https://cdn.gptunnel.com/a.mp4").decodeToString())
        assertEquals(listOf("/a.mp4"), paths)
    }

    @Test
    fun referencesWithoutSchemaFallBackToTheKindAsRole() {
        val provider = GptunnelMediaProvider()
        val body = provider.buildTaskBody(
            MediaJobRequest(
                model = "unknown-model",
                prompt = "лиса",
                references = listOf(
                    MediaReference(url = "https://example.com/a.png"),
                    MediaReference(base64DataUri = "data:image/png;base64,aGVsbG8=", isVideo = true),
                ),
            ),
        )
        val inputs = body["inputs"]?.toString().orEmpty()
        assertTrue(inputs.contains("\"image\":[\"https://example.com/a.png\"]"), "inputs was $inputs")
        assertTrue(inputs.contains("\"video\":[\"data:image/png;base64,aGVsbG8=\"]"), "inputs was $inputs")
    }
}
