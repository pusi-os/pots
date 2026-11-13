package com.pusios.pots.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
