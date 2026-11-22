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
 * Polza adapter. Focused unit tests run on verbatim contract shapes; the live
 * fixtures are cut from the real public catalog
 * (`GET https://polza.ai/api/v1/models?type=image|video`, 2026-11-22) — not
 * redrawn by hand, so a catalog shape drift fails here instead of the release.
 * No network, no paid calls.
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

    // Real cards from the live catalog, one per price shape:
    // tiers (empty default tier first), per_request, video_per_second,
    // and a videos input capped at max = 0.
    private val qwenCard = """{"id":"qwen/image","object":"model","owned_by":"polza-ai","name":"Qwen Image","type":"image","short_description":"Создаёт изображения по текстовому описанию, в том числе с надписями и сложной композицией; подходит для иллюстраций, постеров и визуальных концепций.","icon_provider_slug":"qwen","is_fz152_compliant":false,"stores_data_in_russia":false,"created":1771851591,"architecture":{"modality":"image","input_modalities":[],"output_modalities":["image"],"tokenizer":null,"instruct_type":null},"context_length":null,"max_completion_tokens":null,"top_provider":{"name":"mie","icon_provider_slug":"qwen","is_fz152_compliant":false,"stores_data_in_russia":false,"is_hosted_on_polza_infrastructure":false,"is_unofficial":false,"is_moderated":false,"context_length":null,"max_completion_tokens":null,"pricing":{"tiers":[{"conditions":[],"cost_rub":"3.00000000"},{"conditions":["aspect_ratio=1:1"],"cost_rub":"3.00000000"},{"conditions":["aspect_ratio=3:4"],"cost_rub":"2.50000000"},{"conditions":["aspect_ratio=4:3"],"cost_rub":"2.50000000"},{"conditions":["aspect_ratio=9:16"],"cost_rub":"2.25000000"},{"conditions":["aspect_ratio=16:9"],"cost_rub":"2.25000000"}],"currency":"RUB"},"supported_parameters":null,"default_parameters":null,"parameters":{"prompt":{"required":true,"max_length":5000},"aspect_ratio":{"values":["1:1","3:4","4:3","9:16","16:9"]},"output_format":{"values":["png","jpeg"]},"images":{"min":0,"max":1},"strength":{"description":"Сила трансформации (только image-to-image)","min":0,"max":1,"default":0.8},"guidance_scale":{"min":0,"max":20,"default":2.5}}},"endpoints":["/api/v1/media","/api/v1/images/generations"],"parameters":{"prompt":{"required":true,"max_length":5000},"aspect_ratio":{"values":["1:1","3:4","4:3","9:16","16:9"]},"output_format":{"values":["png","jpeg"]},"images":{"min":0,"max":1},"strength":{"description":"Сила трансформации (только image-to-image)","min":0,"max":1,"default":0.8},"guidance_scale":{"min":0,"max":20,"default":2.5}}}"""

    private val zImageCard = """{"id":"tongyi-mai/z-image","object":"model","owned_by":"polza-ai","name":"Z-Image","type":"image","short_description":"Создаёт изображения по текстовым запросам; подходит для проработки композиций, фотореалистичных сцен, цифрового арта и иллюстраций с negative prompt.","icon_provider_slug":"tongyi-mai","is_fz152_compliant":false,"stores_data_in_russia":false,"created":1771623981,"architecture":{"modality":"image","input_modalities":[],"output_modalities":["image"],"tokenizer":null,"instruct_type":null},"context_length":null,"max_completion_tokens":null,"top_provider":{"name":"mie","icon_provider_slug":"z-image","is_fz152_compliant":false,"stores_data_in_russia":false,"is_hosted_on_polza_infrastructure":false,"is_unofficial":false,"is_moderated":false,"context_length":null,"max_completion_tokens":null,"pricing":{"per_request":"1.40","currency":"RUB"},"supported_parameters":null,"default_parameters":null,"parameters":{"prompt":{"required":true,"max_length":1000},"aspect_ratio":{"required":true,"default":"1:1","values":["1:1","4:3","3:4","16:9","9:16"]},"images":{"min":0,"max":0}}},"endpoints":["/api/v1/media","/api/v1/images/generations"],"parameters":{"prompt":{"required":true,"max_length":1000},"aspect_ratio":{"required":true,"default":"1:1","values":["1:1","4:3","3:4","16:9","9:16"]},"images":{"min":0,"max":0}}}"""

    private val wanCard = """{"id":"alibaba/wan-3.0","object":"model","owned_by":"polza-ai","name":"Alibaba: Wan 3.0","type":"video","short_description":"Создаёт видео по текстовому описанию или исходному изображению. Подходит для визуализации сцен, творческих идей и рекламных концепций.","icon_provider_slug":"wan","is_fz152_compliant":false,"stores_data_in_russia":false,"created":1787697033,"architecture":{"modality":"video","input_modalities":["text","image"],"output_modalities":["video"],"tokenizer":null,"instruct_type":null},"context_length":null,"max_completion_tokens":null,"top_provider":{"name":"Alibaba","icon_provider_slug":"alibaba","is_fz152_compliant":false,"stores_data_in_russia":false,"is_hosted_on_polza_infrastructure":false,"is_unofficial":false,"is_moderated":false,"context_length":null,"max_completion_tokens":null,"pricing":{"video_per_second":"23.44032000","currency":"RUB"},"supported_parameters":null,"default_parameters":null,"parameters":{"prompt":{"required":true,"description":"Текстовое описание видео"},"aspect_ratio":{"description":"Соотношение сторон","default":"16:9","values":["16:9","4:3","1:1","3:4","9:16","21:9"]},"resolution":{"description":"Разрешение видео","default":"480p","values":["480p","720p","1080p"]},"duration":{"description":"Длительность видео (секунды)","default":"2","values":["2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30"]},"images":{"description":"Опорные кадры для image-to-video (first/last frame)","max":2},"seed":{"description":"Seed для воспроизводимости"},"generate_audio":{"description":"Генерировать аудиодорожку","default":"false","values":["true","false"]}}},"endpoints":["/api/v1/media","/api/v1/media/:id/operations"],"parameters":{"prompt":{"required":true,"description":"Текстовое описание видео"},"aspect_ratio":{"description":"Соотношение сторон","default":"16:9","values":["16:9","4:3","1:1","3:4","9:16","21:9"]},"resolution":{"description":"Разрешение видео","default":"480p","values":["480p","720p","1080p"]},"duration":{"description":"Длительность видео (секунды)","default":"2","values":["2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30"]},"images":{"description":"Опорные кадры для image-to-video (first/last frame)","max":2},"seed":{"description":"Seed для воспроизводимости"},"generate_audio":{"description":"Генерировать аудиодорожку","default":"false","values":["true","false"]}}}"""

    private val klingCard = """{"id":"kling-4.0/video","object":"model","owned_by":"polza-ai","name":"Kling 4.0","type":"video","short_description":null,"icon_provider_slug":"kling","is_fz152_compliant":false,"stores_data_in_russia":false,"created":1795025938,"architecture":{"modality":"video","input_modalities":["text","image"],"output_modalities":["video"],"tokenizer":null,"instruct_type":null},"context_length":null,"max_completion_tokens":null,"top_provider":{"name":"mie","icon_provider_slug":"kling","is_fz152_compliant":false,"stores_data_in_russia":false,"is_hosted_on_polza_infrastructure":false,"is_unofficial":false,"is_moderated":false,"context_length":null,"max_completion_tokens":null,"pricing":{"tiers":[{"conditions":[],"cost_rub":"31.87500000"},{"conditions":["resolution=720p"],"cost_rub":"21.37500000"},{"conditions":["resolution=1080p"],"cost_rub":"31.87500000"}],"unitParam":"duration","currency":"RUB"},"supported_parameters":null,"default_parameters":null,"parameters":{"prompt":{"required":true,"max_length":8000},"aspect_ratio":{"default":"16:9","values":["auto","16:9","9:16","1:1","21:9"]},"resolution":{"default":"720p","values":["720p","1080p"]},"duration":{"step":1,"min":3,"max":30,"default":"5"},"images":{"description":"Начальный и конечный кадры. В промпте их можно указать как @first_frame и @last_frame.","min":0,"max":2},"videos":{"min":0,"max":0},"reference_images":{"description":"Изображения-референсы. В промпте используйте @image_1, @image_2 и далее.","min":0,"max":10}}},"endpoints":["/api/v1/media","/api/v1/media/:id/operations"],"parameters":{"prompt":{"required":true,"max_length":8000},"aspect_ratio":{"default":"16:9","values":["auto","16:9","9:16","1:1","21:9"]},"resolution":{"default":"720p","values":["720p","1080p"]},"duration":{"step":1,"min":3,"max":30,"default":"5"},"images":{"description":"Начальный и конечный кадры. В промпте их можно указать как @first_frame и @last_frame.","min":0,"max":2},"videos":{"min":0,"max":0},"reference_images":{"description":"Изображения-референсы. В промпте используйте @image_1, @image_2 и далее.","min":0,"max":10}}}"""

    private val liveCatalog = """{"object":"list","data":[$qwenCard,$zImageCard,$wanCard,$klingCard]}"""

    private val liveImagesCatalog = """{"object":"list","data":[$qwenCard,$zImageCard]}"""

    private val liveVideosCatalog = """{"object":"list","data":[$wanCard,$klingCard]}"""

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
    fun liveCatalogKeepsEveryModelWithAPublishedPrice() {
        val models = provider.parseCatalog(liveCatalog)
        assertEquals(
            listOf("qwen/image", "tongyi-mai/z-image", "alibaba/wan-3.0", "kling-4.0/video"),
            models.map { it.id },
        )
        // per_request pricing: an exact "from" price for the whole job, no unit.
        val zImage = models.first { it.id == "tongyi-mai/z-image" }
        assertEquals(1.40, zImage.fromRub)
        assertEquals("exact", zImage.priceStatus)
        assertEquals(false, zImage.priceUnavailable)
        assertNull(zImage.unit)
        // video_per_second pricing: an exact ₽/с rate.
        val wan = models.first { it.id == "alibaba/wan-3.0" }
        assertEquals(23.44032, wan.fromRub)
        assertEquals("за секунду", wan.unit)
        assertEquals("exact", wan.priceStatus)
        // per_request models price the whole request — no per-image multiply.
        assertEquals(
            1.40,
            provider.quoteLocal(
                MediaJobRequest(model = "tongyi-mai/z-image", prompt = "p", opts = mapOf("aspect_ratio" to "16:9")),
            ).priceRub,
        )
    }

    @Test
    fun liveQuotePicksTheSpecificTierEvenWhenTheDefaultTierComesFirst() {
        provider.parseCatalog(liveCatalog)
        // Live qwen/image: the conditions-less tier (3.00) precedes the 16:9
        // one (2.25) — specificity, not position, must decide.
        val wide = provider.quoteLocal(
            MediaJobRequest(model = "qwen/image", prompt = "p", opts = mapOf("aspect_ratio" to "16:9")),
        )
        assertEquals(2.25, wide.priceRub)
        // Live kling-4.0/video: the default tier is 31.875/с and first,
        // 720p costs 21.375/с × 5 s.
        val kling = provider.quoteLocal(
            MediaJobRequest(
                model = "kling-4.0/video",
                prompt = "p",
                opts = mapOf("resolution" to "720p", "duration" to "5"),
            ),
        )
        assertEquals(21.375 * 5, kling.priceRub)
        // Without any matching opt the default tier is still reachable.
        assertEquals(31.875, provider.quoteLocal(MediaJobRequest(model = "kling-4.0/video", prompt = "p")).priceRub)
    }

    @Test
    fun liveReferenceCapsFollowTheDocumentedMax() {
        val models = provider.parseCatalog(liveCatalog)
        // z-image: parameters.images.max = 0, no image modality → no reference.
        assertEquals(false, models.first { it.id == "tongyi-mai/z-image" }.acceptsImageReference)
        // kling-4.0/video: videos.max = 0 → no video reference even though the
        // key exists; image input stays available (modality + images.max = 2).
        val kling = models.first { it.id == "kling-4.0/video" }
        assertEquals(false, kling.acceptsVideoReference)
        assertTrue(kling.acceptsImageReference)
        assertEquals(8000, kling.promptLimit)
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
    fun priceTableFromTheCachedCatalogHitsNoNetwork() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respond(
                content = liveImagesCatalog,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val cached = PolzaMediaProvider(clientOverride = HttpClient(engine))
        cached.parseCatalog(liveImagesCatalog)
        val table = cached.priceTable("sk-test", "qwen/image")
        assertTrue(table != null)
        assertEquals(0, calls)
    }

    @Test
    fun listModelsSendsNoAuthorizationHeader() = runTest {
        val types = mutableListOf<String?>()
        val authHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            types += request.url.parameters["type"]
            authHeaders += request.headers[HttpHeaders.Authorization]
            respond(
                content = if (request.url.parameters["type"] == "video") liveVideosCatalog else liveImagesCatalog,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
        }
        val models = PolzaMediaProvider(clientOverride = HttpClient(engine)).listModels("sk-test")
        assertEquals(listOf<String?>("image", "video"), types)
        // The catalog is public: the key must not be sent at all.
        assertEquals(listOf<String?>(null, null), authHeaders)
        assertEquals(
            listOf("qwen/image", "tongyi-mai/z-image", "alibaba/wan-3.0", "kling-4.0/video"),
            models.map { it.id },
        )
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
    fun localResultsAreBounded() {
        repeat(80) { i ->
            provider.parseSyncImageResponse(
                MediaJobRequest(model = "qwen/image", prompt = "p"),
                """{"data":[{"url":"https://s3.polza.ai/f/$i.png"}]}""",
            )
        }
        assertTrue(provider.localResults.size <= 64)
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

        // `max_images > 1` answers carry `data` as an array — every item
        // becomes a result, not an empty success.
        val multi = provider.parseMediaStatus(
            "m2",
            """{"id":"m2","status":"completed","data":[{"url":"https://s3.polza.ai/f/a.png"},{"b64_json":"aGVsbG8=","media_type":"image/jpeg"}],"usage":{"cost_rub":4.0}}""",
        )
        assertEquals(MediaJobStatus.Success, multi.status)
        assertEquals(
            listOf("https://s3.polza.ai/f/a.png", "data:image/jpeg;base64,aGVsbG8="),
            multi.resultUrls,
        )
        assertEquals(4.0, multi.priceRub)

        val cancelled = provider.parseMediaStatus("m3", """{"id":"m3","status":"cancelled"}""")
        assertEquals(MediaJobStatus.Fail, cancelled.status)
        assertEquals("cancelled", cancelled.errorCode)
        assertTrue(cancelled.definitive)

        val failed = provider.parseMediaStatus(
            "m4",
            """{"id":"m4","status":"failed","error":{"code":"MODERATION","message":"blocked"}}""",
        )
        assertEquals(MediaJobStatus.Fail, failed.status)
        assertEquals("MODERATION", failed.errorCode)
        assertEquals("blocked", failed.failMsg)

        val processing = provider.parseMediaStatus("m5", """{"id":"m5","status":"processing"}""")
        assertEquals(MediaJobStatus.Processing, processing.status)
    }

    @Test
    fun httpErrorsMapToTypedMediaExceptions() {
        assertIs<MediaException.InsufficientBalance>(mapMediaHttpError(402, """{"error":{"message":"нет средств"}}"""))
        assertIs<MediaException.RateLimited>(mapMediaHttpError(429, """{"error":{"message":"slow down"}}"""))
        assertIs<MediaException.InvalidKey>(mapMediaHttpError(401, """{"error":{"code":"UNAUTHORIZED"}}"""))
        // 403 is access/moderation, not a key problem.
        assertIs<MediaException.Unavailable>(mapMediaHttpError(403, """{"error":{"message":"модерация"}}"""))
        assertIs<MediaException.ModelNotFound>(mapMediaHttpError(404, "{}"))
    }
}
