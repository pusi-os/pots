package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Polza adapter on fixtures cut from `docs/knowledge/media-providers/polza.md`
 * (snapshot 2026-11-22). No network, no paid calls: parsing, local pricing,
 * body building, both sync-submit outcomes and the status mapping.
 */
class PolzaMediaProviderTest {

    private val provider = PolzaMediaProvider()

    // Verbatim shapes from the contract: tiers with `conditions`/`cost_rub`,
    // `unitParam`, `parameters` schemas, `architecture.input_modalities`.
    private val catalog = """
        {"object":"list","data":[
          {"id":"qwen/image","object":"model","name":"Qwen Image","type":"image",
           "short_description":"Text to image",
           "architecture":{"modality":"text->image","input_modalities":["text","image"],"output_modalities":["image"]},
           "top_provider":{"pricing":{"currency":"RUB","unitParam":null,
             "tiers":[{"conditions":["aspect_ratio=16:9"],"cost_rub":"2.25"},
                      {"conditions":["aspect_ratio=1:1"],"cost_rub":"3.00"},
                      {"conditions":[],"cost_rub":"2.50"}]}},
           "parameters":{"prompt":{"type":"string","required":true,"max_length":4000},
             "aspect_ratio":{"type":"string","values":["1:1","16:9","3:4"],"default":"1:1"},
             "max_images":{"type":"integer","min":1,"max":4},
             "images":{"type":"array","max":1}}},
          {"id":"grok-imagine-video-1-5","object":"model","name":"Grok Imagine","type":"video",
           "architecture":{"input_modalities":["text","image"]},
           "top_provider":{"pricing":{"currency":"RUB","unitParam":"duration",
             "tiers":[{"conditions":["resolution=480p"],"cost_rub":"1.62"},
                      {"conditions":["resolution=720p"],"cost_rub":"3.0375"}]}},
           "parameters":{"prompt":{"type":"string","required":true,"max_length":1000},
             "resolution":{"type":"string","values":["480p","720p"],"default":"480p"},
             "duration":{"type":"string","min":"3","max":"10","default":"5"}}}
        ]}
    """.trimIndent()

    @Test
    fun catalogParsesIntoModels() {
        val models = provider.parseCatalog(catalog)
        assertEquals(listOf("qwen/image", "grok-imagine-video-1-5"), models.map { it.id })
        val image = models[0]
        assertEquals(MediaKind.Image, image.kind)
        assertNull(image.unit)
        assertEquals(2.25, image.fromRub)
        assertEquals("exact", image.priceStatus)
        assertEquals(4000, image.promptLimit)
        assertTrue(image.acceptsImageReference)
        assertEquals(listOf("1:1", "16:9", "3:4"), image.params.first { it.key == "aspect_ratio" }.options.map { it.value })
        assertEquals("1:1", image.params.first { it.key == "aspect_ratio" }.defaultValue)
    }

    @Test
    fun videoModelCarriesPerSecondUnitAndTiers() {
        val video = provider.parseCatalog(catalog)[1]
        assertEquals(MediaKind.Video, video.kind)
        assertEquals("за секунду", video.unit)
        assertEquals(1.62, video.fromRub)
        assertEquals(
            mapOf("resolution" to setOf("480p")),
            video.tiers.first().whenConditions,
        )
    }

    @Test
    fun priceTableIsBuiltLocallyFromTheCachedCatalog() {
        provider.parseCatalog(catalog)
        val video = provider.priceTableFromCache("grok-imagine-video-1-5")
        assertTrue(video != null)
        assertEquals(listOf("resolution"), video.priceAffecting)
        assertEquals("unit_rate", video.rows.first().chargeBasis)
        assertEquals(mapOf("resolution" to "480p"), video.rows.first().opts)
        assertEquals(1.62, video.rows.first().price)
        assertEquals("exact", video.rows.first().priceStatus)
        // Flat tiers without unitParam price the whole job.
        assertEquals("job_total", provider.priceTableFromCache("qwen/image")?.rows?.first()?.chargeBasis)
    }

    @Test
    fun priceTableForUnknownModelStaysNullWithoutANetworkCall() {
        assertNull(provider.priceTableFromCache("never-listed"))
    }

    @Test
    fun quoteSelectsTheMatchingTier() {
        provider.parseCatalog(catalog)
        val video = provider.quoteLocal(
            MediaJobRequest(model = "grok-imagine-video-1-5", prompt = "p", opts = mapOf("resolution" to "720p", "duration" to "5")),
        )
        assertEquals(3.0375 * 5, video.priceRub)
        assertNull(video.quoteId)
        val image = provider.quoteLocal(
            MediaJobRequest(model = "qwen/image", prompt = "p", opts = mapOf("aspect_ratio" to "1:1")),
        )
        assertEquals(3.00, image.priceRub)
    }

    @Test
    fun quoteFallsBackToTheDefaultTierAndFromPrice() {
        provider.parseCatalog(catalog)
        // No matching condition → the empty-conditions default tier.
        assertEquals(2.50, provider.quoteLocal(MediaJobRequest(model = "qwen/image", prompt = "p")).priceRub)
        // Unknown model → 0 (the caller shows no number rather than a guess).
        assertEquals(0.0, provider.quoteLocal(MediaJobRequest(model = "ghost", prompt = "p")).priceRub)
    }

    @Test
    fun imagesBodyIsTheDocumentedV2Shape() {
        val body = provider.buildImagesBody(
            MediaJobRequest(
                model = "qwen/image",
                prompt = "кот",
                opts = mapOf("max_images" to "2", "size" to "1024x1024", "aspect_ratio" to "1:1"),
            ),
        )
        assertEquals("qwen/image", body["model"]?.toString()?.trim('"'))
        assertEquals("кот", body["prompt"]?.toString()?.trim('"'))
        assertEquals("url", body["response_format"]?.toString()?.trim('"'))
        assertEquals(2, body["n"]?.toString()?.toInt())
        assertEquals("1024x1024", body["size"]?.toString()?.trim('"'))
        assertNull(body["aspect_ratio"])
    }

    @Test
    fun mediaBodyCarriesOptsReferencesAndAsync() {
        val body = provider.buildMediaBody(
            MediaJobRequest(
                model = "grok-imagine-video-1-5",
                prompt = "закат",
                opts = mapOf("resolution" to "480p", "duration" to "5"),
                references = listOf(MediaReference(base64DataUri = "data:image/png;base64,aGVsbG8=")),
            ),
        )
        val input = body["input"] as? kotlinx.serialization.json.JsonObject
        assertTrue(input != null)
        assertEquals("закат", input["prompt"]?.toString()?.trim('"'))
        assertEquals("480p", input["resolution"]?.toString()?.trim('"'))
        // The contract: `duration` is a string in Polza's `input`.
        assertEquals("5", input["duration"]?.toString()?.trim('"'))
        val images = input["images"] as? kotlinx.serialization.json.JsonArray
        assertEquals(1, images?.size)
        assertTrue((images?.first()?.toString() ?: "").contains("\"base64\""))
        assertEquals("true", body["async"]?.toString())
        assertNull(body["provider"])
    }

    @Test
    fun mediaBodyScalesTheVideoPriceCapByDuration() {
        val body = provider.buildMediaBody(
            MediaJobRequest(model = "grok-imagine-video-1-5", prompt = "p", opts = mapOf("duration" to "5"), maxPriceRub = 15.0),
        )
        val routing = body["provider"] as? kotlinx.serialization.json.JsonObject
        val maxPrice = routing?.get("max_price") as? kotlinx.serialization.json.JsonObject
        assertEquals(3.0, maxPrice?.get("video_per_second")?.toString()?.toDouble())
    }

    @Test
    fun syncAnswerWithDataBecomesAPendingLocalJob() {
        val body = """
            {"created":1,"data":[{"url":"https://s3.polza.ai/f/x.png"}],"usage":{"cost_rub":2.5}}
        """.trimIndent()
        val state = provider.parseSyncImageResponse(
            MediaJobRequest(model = "qwen/image", prompt = "p"),
            body,
        )
        // The MediaProvider contract: submit returns a non-terminal state.
        assertEquals(MediaJobStatus.Processing, state.status)
        assertTrue(state.jobId.startsWith("polza-local-"))
        val stored = provider.localResults[state.jobId]
        assertTrue(stored != null)
        assertEquals(MediaJobStatus.Success, stored.status)
        assertEquals(listOf("https://s3.polza.ai/f/x.png"), stored.resultUrls)
        assertEquals(2.5, stored.priceRub)
        assertTrue(stored.definitive)
    }

    @Test
    fun syncAnswerPendingPassesThroughAsAPollableJob() {
        val body = """{"id":"gen_123","status":"pending","model":"qwen/image","created":1}"""
        val state = provider.parseSyncImageResponse(
            MediaJobRequest(model = "qwen/image", prompt = "p"),
            body,
        )
        assertEquals(MediaJobStatus.Processing, state.status)
        assertEquals("gen_123", state.jobId)
        assertNull(provider.localResults[state.jobId])
    }

    @Test
    fun pollMapsStatusesPricesAndErrors() {
        val completed = provider.parseMediaStatus(
            "m1",
            """{"id":"m1","status":"completed","model":"grok-imagine-video-1-5","data":{"url":"https://s3.polza.ai/f/v.mp4","duration":5},"usage":{"cost_rub":8.1}}""",
        )
        assertEquals(MediaJobStatus.Success, completed.status)
        assertEquals(listOf("https://s3.polza.ai/f/v.mp4"), completed.resultUrls)
        assertEquals(8.1, completed.priceRub)

        val cancelled = provider.parseMediaStatus("m2", """{"id":"m2","status":"cancelled"}""")
        assertEquals(MediaJobStatus.Fail, cancelled.status)
        assertEquals("cancelled", cancelled.errorCode)
        assertTrue(cancelled.definitive)

        val failed = provider.parseMediaStatus(
            "m3",
            """{"id":"m3","status":"failed","error":{"code":"MODERATION","message":"blocked"}}""",
        )
        assertEquals(MediaJobStatus.Fail, failed.status)
        assertEquals("MODERATION", failed.errorCode)
        assertEquals("blocked", failed.failMsg)

        val processing = provider.parseMediaStatus("m4", """{"id":"m4","status":"processing"}""")
        assertEquals(MediaJobStatus.Processing, processing.status)
    }

    @Test
    fun httpErrorsMapToTypedMediaExceptions() {
        assertIs<MediaException.InsufficientBalance>(mapMediaHttpError(402, """{"error":{"message":"нет средств"}}"""))
        assertIs<MediaException.RateLimited>(mapMediaHttpError(429, """{"error":{"message":"slow down"}}"""))
        assertIs<MediaException.InvalidKey>(mapMediaHttpError(401, """{"error":{"code":"UNAUTHORIZED"}}"""))
        assertIs<MediaException.ModelNotFound>(mapMediaHttpError(404, "{}"))
    }
}
