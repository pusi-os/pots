package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AITUNNEL adapter on fixtures cut from
 * `docs/knowledge/media-providers/aitunnel.md` (snapshot 2026-11-22).
 * No network, no paid calls: catalog parsing, the local price table, quotes,
 * body building, the sync image wrap and the video poll mapping.
 */
class AitunnelMediaProviderTest {

    private val provider = AitunnelMediaProvider()

    // Verbatim field shapes from the contract (public catalog groups).
    private val imagesCatalog = """
        {"images":{
          "recraft-v4.1-flash-v2":{"provider":"recraft","short_description":"Быстрая генерация",
            "modalities":{"input":["text"],"output":["image"]},
            "min_price_per_image":1.26,"max_price_per_image":1.26,
            "supported_resolutions":["1K","2K"],"supported_aspect_ratios":["1:1","16:9"],
            "supported_quality":[],"supported_output_formats":["png"],"supported_background":[],
            "max_n":6,"max_input_references":0,"supports_seed":true,
            "supports_generation":true,"supports_edit":false},
          "mai-image-2.7-flash":{"provider":"mai","short_description":"Правка по референсу",
            "modalities":{"input":["text","image"],"output":["image"]},
            "min_price_per_image":0.54,"max_price_per_image":11.70,
            "supported_resolutions":[],"supported_aspect_ratios":[],
            "max_n":1,"max_input_references":5,
            "supports_generation":true,"supports_edit":true},
          "edit-only-model":{"provider":"x","modalities":{"input":["text","image"],"output":["image"]},
            "min_price_per_image":1.0,"max_price_per_image":2.0,
            "max_input_references":3,"supports_generation":false,"supports_edit":true}
        }}
    """.trimIndent()

    private val videosCatalog = """
        {"videos":{
          "mischief-1":{"provider":"hunyuan","short_description":"Дешёвое видео",
            "modalities":{"input":["text"],"output":["video"]},
            "min_price_per_second":0.85,"max_price_per_second":1.70,
            "supported_durations":[3,5],"supported_resolutions":[],
            "supported_sizes":["854x480","1280x720"],
            "size_prices":{"854x480":0.85,"1280x720":1.70},
            "size_prices_no_audio":{"854x480":0.85,"1280x720":1.70},
            "supports_input_references":false,"supported_frame_images":[],"generate_audio":null},
          "grok-imagine-video-1.5-lite":{"provider":"grok","short_description":"Референсы и кадры",
            "modalities":{"input":["text","image"],"output":["video"]},
            "min_price_per_second":3.40,"max_price_per_second":23.80,
            "supported_durations":[1,5,10],
            "size_prices":{"480p":3.40,"720p":5.10},
            "supported_frame_images":["first_frame"],"supports_input_references":true}
        }}
    """.trimIndent()

    @Test
    fun imageCatalogParsesAndFiltersEditOnlyModels() {
        val models = provider.parseCatalogGroup(imagesCatalog, "images")
        assertEquals(listOf("recraft-v4.1-flash-v2", "mai-image-2.7-flash"), models.map { it.id })
        val recraft = models[0]
        assertEquals(MediaKind.Image, recraft.kind)
        assertEquals("за картинку", recraft.unit)
        assertEquals(1.26, recraft.fromRub)
        assertEquals("estimate", recraft.priceStatus)
        assertEquals(false, recraft.acceptsImageReference)
        assertEquals(listOf("1K", "2K"), recraft.params.first { it.key == "resolution" }.options.map { it.value })
        assertTrue(models[1].acceptsImageReference)
    }

    @Test
    fun videoCatalogParsesSizesPricesAndReferenceCaps() {
        val models = provider.parseCatalogGroup(videosCatalog, "videos")
        val mischief = models.first { it.id == "mischief-1" }
        assertEquals(MediaKind.Video, mischief.kind)
        assertEquals("за секунду", mischief.unit)
        assertEquals(0.85, mischief.fromRub)
        assertEquals(false, mischief.acceptsVideoReference)
        assertEquals(listOf("3", "5"), mischief.params.first { it.key == "duration" }.options.map { it.value })
        assertEquals("3", mischief.params.first { it.key == "duration" }.defaultValue)
        val grok = models.first { it.id == "grok-imagine-video-1.5-lite" }
        assertTrue(grok.acceptsImageReference)
    }

    @Test
    fun videoPriceTableIsExactPerSize() {
        provider.parseCatalogGroup(videosCatalog, "videos")
        val table = provider.priceTableFromCache("mischief-1")
        assertTrue(table != null)
        assertEquals(listOf("size"), table.priceAffecting)
        assertEquals(2, table.rows.size)
        val first = table.rows.first { it.opts["size"] == "1280x720" }
        assertEquals(1.70, first.price)
        assertEquals("exact", first.priceStatus)
        assertEquals("unit_rate", first.chargeBasis)
    }

    @Test
    fun imageModelsHaveNoExactTable() {
        provider.parseCatalogGroup(imagesCatalog, "images")
        // The catalog publishes only a min…max range for images — no rows to build.
        assertNull(provider.priceTableFromCache("recraft-v4.1-flash-v2"))
        assertNull(provider.priceTableFromCache("never-listed"))
    }

    @Test
    fun imageQuoteIsTheWorstCaseReserve() {
        provider.parseCatalogGroup(imagesCatalog, "images")
        val six = provider.quoteLocal(
            MediaJobRequest(model = "recraft-v4.1-flash-v2", prompt = "p", opts = mapOf("n" to "6")),
        )
        assertEquals(1.26 * 6, six.priceRub)
        assertNull(six.quoteId)
        val one = provider.quoteLocal(MediaJobRequest(model = "mai-image-2.7-flash", prompt = "p"))
        assertEquals(11.70, one.priceRub)
    }

    @Test
    fun videoQuoteIsPerSecondTimesDuration() {
        provider.parseCatalogGroup(videosCatalog, "videos")
        val known = provider.quoteLocal(
            MediaJobRequest(model = "mischief-1", prompt = "p", opts = mapOf("size" to "854x480", "duration" to "3")),
        )
        assertEquals(0.85 * 3, known.priceRub)
        // Unknown size falls back to the worst-case per-second price.
        val worst = provider.quoteLocal(
            MediaJobRequest(model = "grok-imagine-video-1.5-lite", prompt = "p", opts = mapOf("duration" to "5")),
        )
        assertEquals(23.80 * 5, worst.priceRub)
    }

    @Test
    fun imagesBodyMapsOptsAndReferences() {
        val body = provider.buildImagesBody(
            MediaJobRequest(
                model = "mai-image-2.7-flash",
                prompt = "кот",
                opts = mapOf("resolution" to "2K", "aspect_ratio" to "1:1", "n" to "1", "seed" to "7"),
                references = listOf(MediaReference(base64DataUri = "data:image/png;base64,aGVsbG8=")),
            ),
        )
        assertEquals("mai-image-2.7-flash", body["model"]?.toString()?.trim('"'))
        assertEquals("2K", body["resolution"]?.toString()?.trim('"'))
        assertEquals(1, body["n"]?.toString()?.toInt())
        assertEquals(7, body["seed"]?.toString()?.toInt())
        val refs = body["input_references"] as? kotlinx.serialization.json.JsonArray
        assertEquals(1, refs?.size)
        assertTrue((refs?.first()?.toString() ?: "").contains("image_url"))
        assertTrue((refs?.first()?.toString() ?: "").contains("data:image/png;base64,aGVsbG8="))
    }

    @Test
    fun videosBodySendsExplicitDurationAndSize() {
        val body = provider.buildVideosBody(
            MediaJobRequest(
                model = "mischief-1",
                prompt = "волны",
                opts = mapOf("duration" to "3", "size" to "854x480", "generate_audio" to "true"),
            ),
        )
        assertEquals(3, body["duration"]?.toString()?.toInt())
        assertEquals("854x480", body["size"]?.toString()?.trim('"'))
        assertEquals("true", body["generate_audio"]?.toString())
        assertNull(body["input_references"])
    }

    @Test
    fun httpsReferenceStaysAUrl() {
        val body = provider.buildImagesBody(
            MediaJobRequest(
                model = "mai-image-2.7-flash",
                prompt = "p",
                references = listOf(MediaReference(url = "https://example.com/ref.png")),
            ),
        )
        val refs = body["input_references"] as? kotlinx.serialization.json.JsonArray
        assertTrue((refs?.first()?.toString() ?: "").contains("https://example.com/ref.png"))
    }

    @Test
    fun syncImageAnswerBecomesAPendingLocalJobWithInlineBytes() {
        val body = """
            {"created":1,"data":[{"b64_json":"aGVsbG8=","media_type":"image/png"}],
             "model":"recraft-v4.1-flash-v2","usage":{"cost_rub":1.26,"balance":100.0}}
        """.trimIndent()
        val state = provider.parseImagesResponse(
            MediaJobRequest(model = "recraft-v4.1-flash-v2", prompt = "p"),
            body,
        )
        assertEquals(MediaJobStatus.Processing, state.status)
        assertTrue(state.jobId.startsWith("aitunnel-local-"))
        val stored = provider.localResults[state.jobId]
        assertTrue(stored != null)
        assertEquals(MediaJobStatus.Success, stored.status)
        assertEquals(listOf("data:image/png;base64,aGVsbG8="), stored.resultUrls)
        assertEquals(1.26, stored.priceRub)
        assertTrue(stored.definitive)
    }

    @Test
    fun videoSubmitCarriesThePollableId() {
        val state = provider.parseVideoSubmit(
            MediaJobRequest(model = "mischief-1", prompt = "p"),
            """{"id":"vid_1","polling_url":"https://api.aitunnel.ru/v1/videos/vid_1","status":"pending"}""",
        )
        assertEquals(MediaJobStatus.Processing, state.status)
        assertEquals("vid_1", state.jobId)
        assertEquals(MediaKind.Video, state.kind)
    }

    @Test
    fun videoPollMapsStatusesPricesAndErrors() {
        val done = provider.parseVideoPoll(
            "vid_1",
            """{"id":"vid_1","status":"completed","model":"mischief-1",
                "unsigned_urls":["https://api.aitunnel.ru/v1/videos/vid_1/content?index=0"],
                "usage":{"cost_rub":2.55}}""",
        )
        assertEquals(MediaJobStatus.Success, done.status)
        assertEquals(listOf("https://api.aitunnel.ru/v1/videos/vid_1/content?index=0"), done.resultUrls)
        assertEquals(2.55, done.priceRub)

        val expired = provider.parseVideoPoll("vid_2", """{"id":"vid_2","status":"expired"}""")
        assertEquals(MediaJobStatus.Fail, expired.status)
        assertEquals("expired", expired.errorCode)
        assertTrue(expired.definitive)

        val failed = provider.parseVideoPoll("vid_3", """{"id":"vid_3","status":"failed","error":"модерация"}""")
        assertEquals(MediaJobStatus.Fail, failed.status)
        assertEquals("модерация", failed.failMsg)

        val processing = provider.parseVideoPoll("vid_4", """{"id":"vid_4","status":"in_progress"}""")
        assertEquals(MediaJobStatus.Processing, processing.status)
    }

    @Test
    fun httpErrorsMapToTypedMediaExceptions() {
        assertIs<MediaException.InsufficientBalance>(mapMediaHttpError(402, """{"error":{"message":"нет денег"}}"""))
        assertIs<MediaException.RateLimited>(mapMediaHttpError(429, """{"error":{"message":"rate_limited"}}"""))
        assertIs<MediaException.InvalidKey>(mapMediaHttpError(401, """{"error":{"message":"authentication_failed"}}"""))
    }

    @Test
    fun dataUriDecodeHelperRoundTrips() {
        val bytes = decodeMediaDataUri("data:image/png;base64,aGVsbG8=")
        assertTrue(bytes != null)
        assertEquals(5, bytes.size)
        assertNull(decodeMediaDataUri("https://example.com/x.png"))
    }
}
