package com.pusios.pots.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The provider's poll responses omit the media kind. The repository must carry
 * the kind it was submitted with through the whole job lifecycle (submit →
 * poll → download → resume), so an audio result is never typed or cached as
 * an image (the «music saved as .png» bug).
 */
class MediaJobKindLifecycleTest {

    private val dispatcher = StandardTestDispatcher()

    /** Poll answers never carry a kind — exactly what the real API returns. */
    private class KindlessProvider(
        private val submitted: MediaJobState,
        private val polled: ArrayDeque<MediaJobState>,
    ) : MediaProvider {
        override suspend fun listModels(key: String) = emptyList<MediaModel>()
        override suspend fun quotePrice(key: String, request: MediaJobRequest) = MediaPriceQuote(priceRub = 1.0, quoteId = "q1")
        override suspend fun submitJob(key: String, request: MediaJobRequest) = submitted
        override suspend fun pollJob(key: String, jobId: String): MediaJobState = polled.removeFirst()
        override suspend fun downloadResult(key: String, url: String): ByteArray = ByteArray(4)
    }

    private val request = MediaJobRequest(model = "lyria-3", prompt = "бит")

    @Test
    fun generateCarriesSubmittedKindThroughPolling() = runTest(dispatcher) {
        val provider = KindlessProvider(
            submitted = MediaJobState(jobId = "j1", status = MediaJobStatus.Submitting),
            polled = ArrayDeque(
                listOf(
                    MediaJobState(jobId = "j1", status = MediaJobStatus.Processing),
                    // localPath preset: the cache write already happened — keep the test hermetic.
                    MediaJobState(
                        jobId = "j1",
                        status = MediaJobStatus.Success,
                        resultUrls = listOf("https://api/file/abc/0?sig=x"),
                        localPath = "media/jobs/j1/0.mp3",
                    ),
                ),
            ),
        )
        val repository = MediaGenerationRepository(provider, CoroutineScope(SupervisorJob() + dispatcher))
        val seen = mutableListOf<MediaJobState>()

        val created = repository.generate("key", request, kind = MediaKind.Music, onUpdate = { seen += it })
        assertEquals(MediaKind.Music, created.kind)

        runCurrent()
        advanceTimeBy(3_000) // first poll
        runCurrent()
        advanceTimeBy(6_000) // second poll → Success
        runCurrent()

        assertEquals(listOf(MediaKind.Music, MediaKind.Music, MediaKind.Music), seen.map { it.kind })
        assertEquals(MediaKind.Music, repository.jobs.value.getValue("j1").kind)
    }

    @Test
    fun resumeWithJournalKindEnrichesStatelessPoll() = runTest(dispatcher) {
        val provider = KindlessProvider(
            submitted = MediaJobState(jobId = "j9", status = MediaJobStatus.Submitting),
            polled = ArrayDeque(
                listOf(
                    MediaJobState(
                        jobId = "j9",
                        status = MediaJobStatus.Success,
                        resultUrls = listOf("https://api/file/abc/0?sig=x"),
                        localPath = "media/jobs/j9/0.mp3",
                    ),
                ),
            ),
        )
        val repository = MediaGenerationRepository(provider, CoroutineScope(SupervisorJob() + dispatcher))
        val seen = mutableListOf<MediaJobState>()

        repository.resume("key", "j9", onUpdate = { seen += it }, kind = MediaKind.Audio)

        assertEquals(MediaKind.Audio, seen.single().kind)
        assertEquals(MediaKind.Audio, repository.jobs.value.getValue("j9").kind)
    }

    @Test
    fun providerKindWinsWhenPresent() = runTest(dispatcher) {
        val provider = KindlessProvider(
            submitted = MediaJobState(jobId = "j1", status = MediaJobStatus.Submitting),
            polled = ArrayDeque(
                listOf(
                    MediaJobState(jobId = "j1", status = MediaJobStatus.Success, kind = MediaKind.Image, localPath = "media/jobs/j1/0.png"),
                ),
            ),
        )
        val repository = MediaGenerationRepository(provider, CoroutineScope(SupervisorJob() + dispatcher))
        val seen = mutableListOf<MediaJobState>()

        repository.generate("key", request, kind = MediaKind.Music, onUpdate = { seen += it })
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(MediaKind.Image, repository.jobs.value.getValue("j1").kind)
    }
}
