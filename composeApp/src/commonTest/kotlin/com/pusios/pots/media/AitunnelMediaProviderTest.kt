package com.pusios.pots.media

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AITUNNEL adapter. Fixtures are cut from the LIVE public catalogs
 * (`GET https://api.aitunnel.ru/public/aitunnel/models/images|videos`,
 * 2026-11-22) — the grouped endpoint answers a **flat** `{id: card}` map with
 * no wrapper, so these tests fail on any drift back to the wrapped shape.
 * No network, no paid calls.
 */
class AitunnelMediaProviderTest {

    private val provider = AitunnelMediaProvider()

    // Real cards from the live images catalog: a cheap generator, a
    // mid-priced one, and an edit-only model (supports_generation = false).
    private val imagesCatalog = """{"flux.2-klein-4b":{"provider":"black-forest-labs","short_description":"Самая быстрая и экономичная генерация изображений","description":"FLUX.2 [klein] 4B — самая быстрая и экономичная модель семейства FLUX.2. Она оптимизирована для сценариев с высокой нагрузкой и при этом сохраняет отличное качество изображений.","modalities":{"input":["text","image"],"output":["image"]},"created":1768429228,"min_price_per_image":2.7,"max_price_per_image":5.4,"supported_resolutions":[],"supported_aspect_ratios":[],"supported_sizes":[],"custom_size":null,"gif_limits":null,"max_input_image_bytes":26214400,"supports_combined_resolution_and_aspect_ratio":true,"supported_quality":[],"supported_output_formats":["png","jpeg"],"supported_background":[],"supports_seed":true,"max_n":1,"max_input_references":4,"allowed_passthrough_parameters":["steps","guidance","safety_tolerance"],"supports_generation":true,"supports_edit":true},"grok-imagine-image-quality":{"provider":"x-ai","short_description":"Генерация изображений с акцентом на качество","modalities":{"input":["text","image"],"output":["image"]},"created":1779117584,"min_price_per_image":9.000000000000002,"max_price_per_image":18.000000000000004,"supported_resolutions":["1K","2K"],"supported_aspect_ratios":["1:1","3:4","4:3","9:16","16:9","2:3","3:2","9:19.5","19.5:9","9:20","20:9","1:2","2:1","auto"],"supported_sizes":[],"custom_size":null,"gif_limits":null,"max_input_image_bytes":26214400,"supports_combined_resolution_and_aspect_ratio":true,"supported_quality":[],"supported_output_formats":[],"supported_background":[],"supports_seed":false,"max_n":1,"max_input_references":3,"allowed_passthrough_parameters":[],"supports_generation":true,"supports_edit":true},"k3-edit":{"provider":"krea","short_description":"Правка по нескольким изображениям с сохранением объектов и композиции","description":"Krea K3 Edit — модель Krea для редактирования изображений, которая подходит для интерактивных редакторов и сценариев, где из одного исходника нужно получить много вариантов. В быстром режиме, который включён по умолчанию, большинство правок занимает меньше десяти секунд.\n\nМодель принимает от одного до восьми референсных изображений и текстовый промпт и возвращает одно отредактированное изображение. Она выполняет как мелкие локальные правки, так и сложные многошаговые изменения, сохраняя персонажей и композицию референсов. Формат результата повторяет исходный, если не задано другое соотношение сторон.","modalities":{"input":["text","image"],"output":["image"]},"created":1792103728,"min_price_per_image":5.4,"max_price_per_image":64.8,"supported_resolutions":["1K","2K"],"supported_aspect_ratios":["1:1","4:3","3:2","16:9","4:5","2:3","9:16"],"supported_sizes":[],"custom_size":null,"gif_limits":null,"max_input_image_bytes":26214400,"supports_combined_resolution_and_aspect_ratio":true,"supported_quality":[],"supported_output_formats":[],"supported_background":[],"supports_seed":true,"max_n":1,"max_input_references":4,"allowed_passthrough_parameters":["preserve_references","skip_prompt_expansion","thinking_mode"],"supports_generation":false,"supports_edit":true}}"""

    // Real cards from the live videos catalog: exact per-size prices and a
    // card without `size_prices` (worst-case reserve).
    private val videosCatalog = """{"gen-4.5":{"provider":"runway","short_description":"Кинематографичная генерация видео по тексту и кадру с точным движением камеры","description":"Runway Gen-4.5 — модель Runway для создания видео по тексту и по изображению, рассчитанная на кинематографичные сцены. Она хорошо передаёт движение, даёт высокое качество картинки и точно следует промпту. Модель справляется со сложными экшен-сценами, детальной работой камеры, точным таймингом событий и сменой атмосферы, поэтому подходит для сюжетных планов, презентаций продуктов и контролируемой анимации изображений.","modalities":{"input":["text","image"],"output":["video"]},"created":1785339483,"max_price_per_second":20.4,"min_price_per_second":20.4,"size_prices":{"1280x720":20.4,"720x1280":20.4},"size_prices_no_audio":{"1280x720":20.4,"720x1280":20.4},"supported_resolutions":["720p"],"supported_aspect_ratios":["16:9","9:16"],"supported_sizes":["1280x720","720x1280"],"supported_durations":[2,3,4,5,6,7,8,9,10],"supported_frame_images":["first_frame"],"generate_audio":false,"supports_seed":true,"supports_input_references":false,"allowed_passthrough_parameters":["contentModeration"],"billing_only_parameters":[],"prompt_optional":false,"requires_input_video":false,"supports_previous_job":false,"upscale_factor":null,"creativity":null,"max_source_duration":null,"max_source_megapixels":null},"wan-3.0":{"provider":"alibaba","short_description":"Видео по тексту, кадрам и референсам (480p–1080p, 2–30 сек)","description":"Wan 3.0 от Alibaba генерирует видео по тексту, по изображению и с опорой на референсы. Доступны разрешения 480p, 720p и 1080p, длительность ролика — от 2 до 30 секунд.","modalities":{"input":["text","image"],"output":["video","audio"]},"created":1787603856,"max_price_per_second":34,"min_price_per_second":8.5,"size_prices":{},"size_prices_no_audio":{},"supported_resolutions":["480p","720p","1080p"],"supported_aspect_ratios":["16:9","4:3","1:1","3:4","9:16"],"supported_sizes":[],"supported_durations":[2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30],"supported_frame_images":["first_frame","last_frame"],"generate_audio":true,"supports_seed":true,"supports_input_references":true,"allowed_passthrough_parameters":[],"billing_only_parameters":[],"prompt_optional":false,"requires_input_video":false,"supports_previous_job":false,"upscale_factor":null,"creativity":null,"max_source_duration":null,"max_source_megapixels":null}}"""

    @Test
    fun imageCatalogParsesTheFlatLiveAnswerAndFiltersEditOnlyModels() {
        val models = provider.parseCatalogGroup(imagesCatalog, "images")
        assertEquals(
            listOf("flux.2-klein-4b", "grok-imagine-image-quality"),
            models.map { it.id },
        )
        val flux = models[0]
        assertEquals(MediaKind.Image, flux.kind)
        assertEquals("за картинку", flux.unit)
        assertEquals(2.7, flux.fromRub)
        assertEquals("estimate", flux.priceStatus)
        assertTrue(flux.acceptsImageReference)
        val grok = models[1]
        assertEquals(listOf("1K", "2K"), grok.params.first { it.key == "resolution" }.options.map { it.value })
    }

    @Test
    fun catalogGroupStillAcceptsTheWrappedForm() {
        val wrapped = """
            {"images":{
              "recraft-v4.1-flash-v2":{"provider":"recraft","short_description":"Быстрая генерация",
                "modalities":{"input":["text"],"output":["image"]},
                "min_price_per_image":1.26,"max_price_per_image":1.26,
                "max_n":6,"max_input_references":0,"supports_generation":true,"supports_edit":false}
            }}
        """.trimIndent()
        val models = provider.parseCatalogGroup(wrapped, "images")
        assertEquals(listOf("recraft-v4.1-flash-v2"), models.map { it.id })
    }

    @Test
    fun videoCatalogParsesSizesPricesAndReferenceCaps() {
        val models = provider.parseCatalogGroup(videosCatalog, "videos")
        val gen = models.first { it.id == "gen-4.5" }
        assertEquals(MediaKind.Video, gen.kind)
        assertEquals("за секунду", gen.unit)
        assertEquals(20.4, gen.fromRub)
        assertEquals(false, gen.acceptsVideoReference)
        assertEquals(listOf("2", "3", "4", "5", "6", "7", "8", "9", "10"), gen.params.first { it.key == "duration" }.options.map { it.value })
        assertEquals("2", gen.params.first { it.key == "duration" }.defaultValue)
        val wan = models.first { it.id == "wan-3.0" }
        assertTrue(wan.acceptsImageReference)
    }

    @Test
    fun videoPriceTableIsExactPerSize() {
        provider.parseCatalogGroup(videosCatalog, "videos")
        val table = provider.priceTableFromCache("gen-4.5")
        assertTrue(table != null)
        assertEquals(listOf("size"), table.priceAffecting)
        assertEquals(2, table.rows.size)
        val row = table.rows.first { it.opts["size"] == "1280x720" }
        assertEquals(20.4, row.price)
        assertEquals("exact", row.priceStatus)
        assertEquals("unit_rate", row.chargeBasis)
    }

    @Test
    fun imageModelsHaveNoExactTable() {
        provider.parseCatalogGroup(imagesCatalog, "images")
        // The catalog publishes only a min…max range for images — no rows to build.
        assertNull(provider.priceTableFromCache("flux.2-klein-4b"))
        assertNull(provider.priceTableFromCache("never-listed"))
    }

    @Test
    fun imageQuoteIsTheWorstCaseReserve() {
        provider.parseCatalogGroup(imagesCatalog, "images")
        val six = provider.quoteLocal(
            MediaJobRequest(model = "flux.2-klein-4b", prompt = "p", opts = mapOf("n" to "6")),
        )
        assertEquals(5.4 * 6, six.priceRub)
        assertNull(six.quoteId)
        val one = provider.quoteLocal(MediaJobRequest(model = "grok-imagine-image-quality", prompt = "p"))
        assertEquals(18.000000000000004, one.priceRub)
    }

    @Test
    fun videoQuoteIsPerSecondTimesDuration() {
        provider.parseCatalogGroup(videosCatalog, "videos")
        val known = provider.quoteLocal(
            MediaJobRequest(model = "gen-4.5", prompt = "p", opts = mapOf("size" to "1280x720", "duration" to "3")),
        )
        assertEquals(20.4 * 3, known.priceRub)
        // Unknown size falls back to the worst-case per-second price.
        val worst = provider.quoteLocal(
            MediaJobRequest(model = "wan-3.0", prompt = "p", opts = mapOf("duration" to "5")),
        )
        assertEquals(34.0 * 5, worst.priceRub)
    }

    @Test
    fun priceTableFromTheCachedCatalogHitsNoNetwork() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respond(
                content = videosCatalog,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val cached = AitunnelMediaProvider(clientOverride = HttpClient(engine))
        cached.parseCatalogGroup(videosCatalog, "videos")
        val table = cached.priceTable("sk-test", "gen-4.5")
        assertTrue(table != null)
        assertEquals(0, calls)
    }

    @Test
    fun listModelsSendsNoAuthorizationHeader() = runTest {
        val paths = mutableListOf<String>()
        val authHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            authHeaders += request.headers[HttpHeaders.Authorization]
            respond(
                content = if (request.url.encodedPath.endsWith("/videos")) videosCatalog else imagesCatalog,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
        }
        val models = AitunnelMediaProvider(clientOverride = HttpClient(engine)).listModels("sk-test")
        assertEquals(
            listOf("/public/aitunnel/models/images", "/public/aitunnel/models/videos"),
            paths,
        )
        // The catalogs are public: the key must not be sent at all.
        assertEquals(listOf<String?>(null, null), authHeaders)
        assertEquals(
            listOf("flux.2-klein-4b", "grok-imagine-image-quality", "gen-4.5", "wan-3.0"),
            models.map { it.id },
        )
    }

    @Test
    fun imagesBodyMapsOptsAndReferences() {
        val body = provider.buildImagesBody(
            MediaJobRequest(
                model = "grok-imagine-image-quality",
                prompt = "кот",
                opts = mapOf("resolution" to "2K", "aspect_ratio" to "1:1", "n" to "1", "seed" to "7"),
                references = listOf(MediaReference(base64DataUri = "data:image/png;base64,aGVsbG8=")),
            ),
        )
        assertEquals("grok-imagine-image-quality", body["model"]?.toString()?.trim('"'))
        assertEquals("2K", body["resolution"]?.toString()?.trim('"'))
        assertEquals(1, body["n"]?.toString()?.toInt())
        assertEquals(7, body["seed"]?.toString()?.toInt())
        val refs = body["input_references"] as? kotlinx.serialization.json.JsonArray
        assertEquals(1, refs?.size)
        assertTrue((refs?.first()?.toString() ?: "").contains("image_url"))
        assertTrue((refs?.first()?.toString() ?: "").contains("data:image/png;base64,aGVsbG8="))
    }

    @Test
    fun imagesBodyDropsTheResolutionPairWhenSizeIsGiven() {
        val body = provider.buildImagesBody(
            MediaJobRequest(
                model = "grok-imagine-image-quality",
                prompt = "p",
                opts = mapOf("size" to "2048x1152", "resolution" to "2K", "aspect_ratio" to "16:9"),
            ),
        )
        // Exact pixels are an alternative to the resolution+aspect pair —
        // combining them is documented as invalid.
        assertEquals("2048x1152", body["size"]?.toString()?.trim('"'))
        assertNull(body["resolution"])
        assertNull(body["aspect_ratio"])
    }

    @Test
    fun videosBodySendsExplicitDurationAndSize() {
        val body = provider.buildVideosBody(
            MediaJobRequest(
                model = "gen-4.5",
                prompt = "волны",
                opts = mapOf("duration" to "3", "size" to "1280x720", "generate_audio" to "true"),
            ),
        )
        assertEquals(3, body["duration"]?.toString()?.toInt())
        assertEquals("1280x720", body["size"]?.toString()?.trim('"'))
        assertNull(body["resolution"])
        assertNull(body["aspect_ratio"])
        assertEquals("true", body["generate_audio"]?.toString())
        assertNull(body["input_references"])
    }

    @Test
    fun videosBodyPrefersSizeOverTheResolutionPair() {
        val body = provider.buildVideosBody(
            MediaJobRequest(
                model = "gen-4.5",
                prompt = "p",
                opts = mapOf("resolution" to "720p", "aspect_ratio" to "16:9", "size" to "1280x720"),
            ),
        )
        assertEquals("1280x720", body["size"]?.toString()?.trim('"'))
        assertNull(body["resolution"])
        assertNull(body["aspect_ratio"])
        // Without a size the pair is sent as usual.
        val paired = provider.buildVideosBody(
            MediaJobRequest(
                model = "gen-4.5",
                prompt = "p",
                opts = mapOf("resolution" to "720p", "aspect_ratio" to "16:9"),
            ),
        )
        assertNull(paired["size"])
        assertEquals("720p", paired["resolution"]?.toString()?.trim('"'))
        assertEquals("16:9", paired["aspect_ratio"]?.toString()?.trim('"'))
    }

    @Test
    fun httpsReferenceStaysAUrl() {
        val body = provider.buildImagesBody(
            MediaJobRequest(
                model = "grok-imagine-image-quality",
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
             "model":"flux.2-klein-4b","usage":{"cost_rub":2.7,"balance":100.0}}
        """.trimIndent()
        val state = provider.parseImagesResponse(
            MediaJobRequest(model = "flux.2-klein-4b", prompt = "p"),
            body,
        )
        assertEquals(MediaJobStatus.Processing, state.status)
        assertTrue(state.jobId.startsWith("aitunnel-local-"))
        val stored = provider.localResults[state.jobId]
        assertTrue(stored != null)
        assertEquals(MediaJobStatus.Success, stored.status)
        assertEquals(listOf("data:image/png;base64,aGVsbG8="), stored.resultUrls)
        assertEquals(2.7, stored.priceRub)
        assertTrue(stored.definitive)
    }

    @Test
    fun localResultsAreBounded() {
        repeat(80) { i ->
            provider.parseImagesResponse(
                MediaJobRequest(model = "flux.2-klein-4b", prompt = "p"),
                """{"data":[{"url":"https://example.com/$i.png"}]}""",
            )
        }
        assertTrue(provider.localResults.size <= 64)
    }

    @Test
    fun videoSubmitCarriesThePollableId() {
        val state = provider.parseVideoSubmit(
            MediaJobRequest(model = "gen-4.5", prompt = "p"),
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
            """{"id":"vid_1","status":"completed","model":"gen-4.5",
                "unsigned_urls":["https://api.aitunnel.ru/v1/videos/vid_1/content?index=0"],
                "usage":{"cost_rub":20.4}}""",
        )
        assertEquals(MediaJobStatus.Success, done.status)
        assertEquals(listOf("https://api.aitunnel.ru/v1/videos/vid_1/content?index=0"), done.resultUrls)
        assertEquals(20.4, done.priceRub)

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
        // 403 is access/moderation, not a key problem.
        assertIs<MediaException.Unavailable>(mapMediaHttpError(403, """{"error":{"message":"permission_denied"}}"""))
    }

    @Test
    fun dataUriDecodeHelperRoundTrips() {
        val bytes = decodeMediaDataUri("data:image/png;base64,aGVsbG8=")
        assertTrue(bytes != null)
        assertEquals(5, bytes.size)
        assertNull(decodeMediaDataUri("https://example.com/x.png"))
    }
}
