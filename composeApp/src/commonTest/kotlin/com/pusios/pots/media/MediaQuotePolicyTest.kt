package com.pusios.pots.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Quote-then-submit policy (spec §2, @linus carry-over): metered models
 * («за 1000 символов», «за секунду») and `mirelo-sfx-2` are quoted before
 * submit; fixed prices submit without the extra request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaQuotePolicyTest {

    private class RecordingProvider(private val models: List<MediaModel>) : MediaProvider {
        var quoteCalls = 0
        var submitCalls = 0

        override suspend fun listModels(apiKey: String): List<MediaModel> = models

        override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote {
            quoteCalls++
            return MediaPriceQuote(priceRub = 1.0, quoteId = "mq_test")
        }

        override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
            submitCalls++
            return MediaJobState(jobId = "job_$submitCalls", status = MediaJobStatus.Processing)
        }

        override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState = MediaJobState(jobId = jobId, status = MediaJobStatus.Processing)

        override suspend fun downloadResult(apiKey: String, url: String): ByteArray = ByteArray(0)
    }

    private suspend fun submitThrough(models: List<MediaModel>, request: MediaJobRequest): RecordingProvider {
        val provider = RecordingProvider(models)
        // Polling runs on a dispatcher the test never advances, so no poll fires.
        val dispatcher = StandardTestDispatcher()
        val repository = MediaGenerationRepository(provider, CoroutineScope(SupervisorJob() + dispatcher))
        repository.models("key-1")
        repository.generate("key-1", request)
        return provider
    }

    @Test
    fun meteredModelIsQuotedBeforeSubmit() = runTest {
        val tts = MediaModel(id = "gpt-4o-mini-tts", kind = MediaKind.Audio, task = "tts", unit = "за 1000 символов", fromRub = 0.07)
        val provider = submitThrough(listOf(tts), MediaJobRequest(model = "gpt-4o-mini-tts", prompt = "Привет"))
        assertEquals(1, provider.quoteCalls)
        assertEquals(1, provider.submitCalls)
    }

    @Test
    fun fixedPriceModelSubmitsWithoutQuote() = runTest {
        val suno = MediaModel(id = "suno", kind = MediaKind.Music, unit = "за трек", fromRub = 6.29)
        val provider = submitThrough(listOf(suno), MediaJobRequest(model = "suno", prompt = "Ло-фай"))
        assertEquals(0, provider.quoteCalls)
        assertEquals(1, provider.submitCalls)
    }

    @Test
    fun mireloSfx2IsAlwaysQuoted() = runTest {
        val mirelo = MediaModel(id = "mirelo-sfx-2", kind = MediaKind.Audio, task = "sfx", unit = "за файл", fromRub = 6.77)
        val provider = submitThrough(listOf(mirelo), MediaJobRequest(model = "mirelo-sfx-2", prompt = "Шаги"))
        assertEquals(1, provider.quoteCalls)
        assertEquals(1, provider.submitCalls)
    }

    @Test
    fun carriedOverQuoteIsNeverRequoted() = runTest {
        val tts = MediaModel(id = "gpt-4o-mini-tts", kind = MediaKind.Audio, task = "tts", unit = "за 1000 символов", fromRub = 0.07)
        val provider = submitThrough(
            listOf(tts),
            MediaJobRequest(model = "gpt-4o-mini-tts", prompt = "Привет", quoteId = "mq_saved", maxPriceRub = 0.5),
        )
        assertEquals(0, provider.quoteCalls)
        assertEquals(1, provider.submitCalls)
    }

    // --- spec §19.1: `chargeBasis=quote` models (image quality tables) are quoted too ---

    private class TableProvider(
        private val models: List<MediaModel>,
        private val tables: Map<String, MediaPriceTable?>,
        private var fetchError: Boolean = false,
    ) : MediaProvider {
        var quoteCalls = 0
        var submitCalls = 0
        var priceTableCalls = 0

        override suspend fun listModels(apiKey: String): List<MediaModel> = models

        override suspend fun priceTable(apiKey: String, modelId: String): MediaPriceTable? {
            priceTableCalls++
            if (fetchError) throw IllegalStateException("network down")
            return tables[modelId]
        }

        override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote {
            quoteCalls++
            return MediaPriceQuote(priceRub = 6.77, quoteId = "mq_img")
        }

        override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
            submitCalls++
            return MediaJobState(jobId = "job_$submitCalls", status = MediaJobStatus.Processing)
        }

        override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState = MediaJobState(jobId = jobId, status = MediaJobStatus.Processing)

        override suspend fun downloadResult(apiKey: String, url: String): ByteArray = ByteArray(0)
    }

    private fun quoteTable(basis: String) = MediaPriceTable(
        model = "gpt-image-2-6",
        currency = "RUB",
        priceAffecting = listOf("resolution", "quality", "mode"),
        defaults = mapOf("resolution" to "1K", "quality" to "high", "mode" to "text-to-image"),
        rows = listOf(
            MediaPriceTableRow(opts = mapOf("resolution" to "1K", "quality" to "low", "mode" to "text-to-image"), price = 1.44, priceStatus = "exact", chargeBasis = basis),
        ),
    )

    private val gptImageModel = MediaModel(id = "gpt-image-2-6", kind = MediaKind.Image, unit = "за изображение", fromRub = 1.44)

    @Test
    fun quoteBasisImageModelIsQuotedBeforeSubmit() = runTest {
        val provider = TableProvider(listOf(gptImageModel), mapOf("gpt-image-2-6" to quoteTable("quote")))
        val repository = MediaGenerationRepository(provider, CoroutineScope(SupervisorJob() + StandardTestDispatcher()))
        repository.models("key-1")
        // The quality dropdown already fetched the price table.
        repository.priceTable("key-1", "gpt-image-2-6")
        repository.generate("key-1", MediaJobRequest(model = "gpt-image-2-6", prompt = "Кот", opts = mapOf("quality" to "low")))
        assertEquals(1, provider.quoteCalls)
        assertEquals(1, provider.submitCalls)
    }

    @Test
    fun fixedBasisImageModelSubmitsWithoutQuote() = runTest {
        val provider = TableProvider(listOf(gptImageModel), mapOf("gpt-image-2-6" to quoteTable("supplier_credits")))
        val repository = MediaGenerationRepository(provider, CoroutineScope(SupervisorJob() + StandardTestDispatcher()))
        repository.models("key-1")
        repository.priceTable("key-1", "gpt-image-2-6")
        repository.generate("key-1", MediaJobRequest(model = "gpt-image-2-6", prompt = "Кот", opts = mapOf("quality" to "low")))
        assertEquals(0, provider.quoteCalls)
        assertEquals(1, provider.submitCalls)
    }

    @Test
    fun priceTableIsCachedAndFetchFailureIsTolerated() = runTest {
        val failing = TableProvider(listOf(gptImageModel), emptyMap(), fetchError = true)
        val repository = MediaGenerationRepository(failing, CoroutineScope(SupervisorJob() + StandardTestDispatcher()))
        // A network failure must not break the studio — null means «note mode».
        assertNull(repository.priceTable("key-1", "gpt-image-2-6"))
        // The failure is cached too: no retry storm on every dropdown open.
        assertNull(repository.priceTable("key-1", "gpt-image-2-6"))
        assertEquals(1, failing.priceTableCalls)
        // Submit still works without a table (catalog rules apply).
        repository.models("key-1")
        repository.generate("key-1", MediaJobRequest(model = "gpt-image-2-6", prompt = "Кот"))
        assertEquals(0, failing.quoteCalls)
        assertEquals(1, failing.submitCalls)

        val cached = TableProvider(listOf(gptImageModel), mapOf("gpt-image-2-6" to quoteTable("quote")))
        val repository2 = MediaGenerationRepository(cached, CoroutineScope(SupervisorJob() + StandardTestDispatcher()))
        repository2.priceTable("key-1", "gpt-image-2-6")
        repository2.priceTable("key-1", "gpt-image-2-6")
        assertEquals(1, cached.priceTableCalls)
    }
}
