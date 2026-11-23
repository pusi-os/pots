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
 * LLM Router adapter. Fixtures follow the contract
 * (`docs/knowledge/media-providers/llm-router.md`, snapshot 2026-11-23):
 * `{data:[{id}]}` catalog behind the key, two sync POST image endpoints, an
 * answer of `data[].b64_json` (url kept for drift) with no `usage`. No
 * network, no paid calls.
 */
class LlmRouterMediaProviderTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun bodyText(request: io.ktor.client.request.HttpRequestData): String = (request.body as? TextContent)?.text.orEmpty()

    @Test
    fun listModelsSendsTheAuthorizationHeaderAndKeepsOnlyImageModels() = runTest {
        val authHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            authHeaders += request.headers[HttpHeaders.Authorization]
            respondJson(
                """{"data":[{"id":"nano-banana"},{"id":"gpt-image-1.5"},{"id":"gpt-image-2.5"},
                    {"id":"gpt-5"},{"id":"deepseek-v4"},{"id":"nano-banana-pro"}]}""",
            )
        }
        val models = LlmRouterMediaProvider(clientOverride = HttpClient(engine)).listModels("llmr-test")
        // The catalog is key-gated (live 401 without it) — the header is sent.
        assertEquals("Bearer llmr-test", authHeaders.single())
        // Only the documented image ids survive; nano-banana-pro is not in
        // the contract's image list and stays out until confirmed.
        assertEquals(
            listOf("nano-banana", "gpt-image-1.5", "gpt-image-2.5"),
            models.map { it.id },
        )
        val nano = models.first()
        assertEquals(MediaKind.Image, nano.kind)
        assertNull(nano.fromRub)
        assertTrue(nano.acceptsImageReference)
        assertEquals("number", nano.params.first { it.key == "n" }.type)
        assertEquals(
            listOf("1024x1024", "1024x1536", "1536x1024"),
            nano.params.first { it.key == "size" }.options.map { it.value },
        )
    }

    @Test
    fun listModelsMaps401ToInvalidKey() = runTest {
        val engine = MockEngine { _ ->
            respondJson(
                """{"type":"error","error":{"type":"authentication_error","message":"Invalid API key."}}""",
                HttpStatusCode.Unauthorized,
            )
        }
        val error = runCatching {
            LlmRouterMediaProvider(clientOverride = HttpClient(engine)).listModels("bad")
        }.exceptionOrNull()
        assertIs<MediaException.InvalidKey>(error)
    }

    @Test
    fun quotePriceIsLocalAndTouchesNoNetwork() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respondJson("{}")
        }
        val quote = LlmRouterMediaProvider(clientOverride = HttpClient(engine)).quotePrice(
            "llmr-test",
            MediaJobRequest(model = "nano-banana", prompt = "лиса", opts = mapOf("n" to "4")),
        )
        // No quote endpoint and no published prices: honest 0.0, no network.
        assertEquals(0.0, quote.priceRub)
        assertNull(quote.quoteId)
        assertEquals(0, calls)
    }

    @Test
    fun priceTableIsNullUntilPricingIsConfirmed() = runTest {
        val engine = MockEngine { _ ->
            respondJson("""{"data":[]}""")
        }
        val provider = LlmRouterMediaProvider(clientOverride = HttpClient(engine))
        // /v1/pricing is undocumented — the adapter returns null, never guesses.
        assertNull(provider.priceTable("llmr-test", "nano-banana"))
    }

    @Test
    fun submitJobGenerationsPostsB64JsonAndWrapsIntoALocalJob() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respondJson("""{"created":1720000000,"data":[{"b64_json":"aGVsbG8="}]}""")
        }
        val provider = LlmRouterMediaProvider(clientOverride = HttpClient(engine))
        val state = provider.submitJob(
            "llmr-test",
            MediaJobRequest(
                model = "nano-banana",
                prompt = "лиса",
                opts = mapOf("n" to "2", "size" to "1024x1024", "aspect_ratio" to "16:9", "quality" to "high"),
                idempotencyKey = "local-abc",
            ),
        )
        // The sync answer is wrapped: submit returns a pending local job,
        // the terminal state lives in localResults.
        assertEquals(MediaJobStatus.Processing, state.status)
        assertTrue(state.jobId.startsWith("llmrouter-local-"))
        val done = provider.localResults.getValue(state.jobId)
        assertEquals(MediaJobStatus.Success, done.status)
        assertEquals(listOf("data:image/png;base64,aGVsbG8="), done.resultUrls)
        assertTrue(done.isTerminal)
        val post = requests.single()
        assertEquals("/v1/images/generations", post.url.encodedPath)
        assertEquals("Bearer llmr-test", post.headers[HttpHeaders.Authorization])
        assertEquals("local-abc", post.headers["Idempotency-Key"])
        val body = bodyText(post)
        assertTrue(body.contains("\"response_format\":\"b64_json\""), "body was $body")
        assertTrue(body.contains("\"n\":2"), "body was $body")
        assertTrue(body.contains("\"size\":\"1024x1024\""), "body was $body")
        assertTrue(body.contains("\"aspect_ratio\":\"16:9\""), "body was $body")
        assertTrue(body.contains("\"quality\":\"high\""), "body was $body")
    }

    @Test
    fun submitJobWithReferencesUsesTheEditsJsonComposition() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respondJson("""{"created":1720000000,"data":[{"url":"https://cdn.example/x.png"}]}""")
        }
        val provider = LlmRouterMediaProvider(clientOverride = HttpClient(engine))
        val state = provider.submitJob(
            "llmr-test",
            MediaJobRequest(
                model = "nano-banana",
                prompt = "сложи",
                references = listOf(
                    MediaReference(url = "https://example.com/a.png"),
                    MediaReference(base64DataUri = "data:image/jpeg;base64,aGVsbG8="),
                ),
            ),
        )
        val post = requests.single()
        assertEquals("/v1/images/edits", post.url.encodedPath)
        val body = bodyText(post)
        // https stays a URL; the rest goes out as an inline data URI.
        assertTrue(body.contains("\"images\":[{\"url\":\"https://example.com/a.png\"},{\"url\":\"data:image/jpeg;base64,aGVsbG8=\"}]"), "body was $body")
        val done = provider.localResults.getValue(state.jobId)
        // url items pass through as URLs (the documented example is b64_json
        // only — the url shape is kept for drift, contract §Ответ).
        assertEquals(listOf("https://cdn.example/x.png"), done.resultUrls)
    }

    @Test
    fun pollJobReturnsTheLocalResultAndRejectsUnknownJobs() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respondJson("{}")
        }
        val provider = LlmRouterMediaProvider(clientOverride = HttpClient(engine))
        val done = MediaJobState(
            jobId = "llmrouter-local-x",
            status = MediaJobStatus.Success,
            model = "nano-banana",
            kind = MediaKind.Image,
            resultUrls = listOf("data:image/png;base64,aGVsbG8="),
            definitive = true,
        )
        provider.localResults["llmrouter-local-x"] = done
        val polled = provider.pollJob("llmr-test", "llmrouter-local-x")
        assertEquals(MediaJobStatus.Success, polled.status)
        // No provider-side state exists: GET on the image routes is 405.
        val error = runCatching { provider.pollJob("llmr-test", "never-submitted") }.exceptionOrNull()
        assertIs<MediaException.Unknown>(error)
        assertEquals(0, calls)
    }

    @Test
    fun downloadResultDecodesInlineData() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respond("BYTES", HttpStatusCode.OK)
        }
        val provider = LlmRouterMediaProvider(clientOverride = HttpClient(engine))
        // The b64_json answer is already local — no network for the real path.
        assertEquals("hello", provider.downloadResult("llmr-test", "data:image/png;base64,aGVsbG8=").decodeToString())
        assertEquals(0, calls)
    }

    @Test
    fun submitWith402ThrowsInsufficientBalance() = runTest {
        val engine = MockEngine { _ ->
            respondJson("""{"error":{"message":"Insufficient funds"}}""", HttpStatusCode.PaymentRequired)
        }
        val error = runCatching {
            LlmRouterMediaProvider(clientOverride = HttpClient(engine)).submitJob(
                "llmr-test",
                MediaJobRequest(model = "nano-banana", prompt = "лиса"),
            )
        }.exceptionOrNull()
        assertIs<MediaException.InsufficientBalance>(error)
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(content = content, status = status, headers = jsonHeaders)
}
