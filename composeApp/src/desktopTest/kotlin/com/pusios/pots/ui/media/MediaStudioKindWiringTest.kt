package com.pusios.pots.ui.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.AudioFlow
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaHistoryStore
import com.pusios.pots.media.MediaJobRequest
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.MediaJobStatus
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.MediaPriceQuote
import com.pusios.pots.media.MediaProvider
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The owner-reported bug: a music generation lands on the canvas as an image
 * and its cache file gets the `.png` extension, because the provider's poll
 * answers carry no `kind`. The kind submitted with the job (and the journal's
 * `isAudio`/`isVideo` on resume) must decide the element type and the cache
 * extension. Fully offline — the fake provider answers from canned states.
 *
 * Lives in desktopTest: app storage (result cache, journal) is redirected into
 * a throwaway home (the established pattern).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaStudioKindWiringTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeHome: File
    private lateinit var originalHome: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeHome = kotlin.io.path.createTempDirectory("pots-kind-wiring").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        System.setProperty("user.home", originalHome)
        fakeHome.deleteRecursively()
    }

    /** Canned provider: poll answers never carry a kind, like the real API. */
    private class ScriptedProvider(
        private val catalog: List<MediaModel>,
        private val polls: ArrayDeque<MediaJobState>,
    ) : MediaProvider {
        override suspend fun listModels(key: String): List<MediaModel> = catalog
        override suspend fun quotePrice(key: String, request: MediaJobRequest) = MediaPriceQuote(priceRub = 1.0, quoteId = "q1")
        override suspend fun submitJob(key: String, request: MediaJobRequest) = MediaJobState(jobId = "job-1", status = MediaJobStatus.Submitting)

        override suspend fun pollJob(key: String, jobId: String): MediaJobState = polls.removeFirst()
        override suspend fun downloadResult(key: String, url: String): ByteArray = ByteArray(16) { it.toByte() }
    }

    private fun success(jobId: String) = MediaJobState(
        jobId = jobId,
        status = MediaJobStatus.Success,
        // Signed proxy URL without an extension — only the kind can tell audio from image.
        resultUrls = listOf("https://plusvibeapi.ru/api/media/file/abc/0?sig=xyz"),
    )

    private fun TestScope.viewModel(provider: MediaProvider): MediaStudioViewModel {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repository = MediaGenerationRepository(provider, scope)
        val history = MediaHistoryStore(repository, scope, persistDispatcher = testDispatcher) { "key" }
        return MediaStudioViewModel(
            appSettings = AppSettings(MapSettings()),
            apiKeyProvider = { "key" },
            instanceIdProvider = { "kind-test" },
            repository = repository,
            history = history,
            enhancer = { it },
            // No ffprobe spawns during the suite: aspect probing is covered
            // by MediaStudioAspectWiringTest with a fake prober.
            videoAspectProber = { null },
        )
    }

    @Test
    fun musicResultIsAudioElementCachedAsMp3() = runTest(testDispatcher) {
        val provider = ScriptedProvider(
            catalog = listOf(MediaModel(id = "lyria-3", kind = MediaKind.Music)),
            polls = ArrayDeque(
                listOf(
                    MediaJobState(jobId = "job-1", status = MediaJobStatus.Processing),
                    success("job-1"),
                ),
            ),
        )
        val viewModel = viewModel(provider)
        runCurrent() // loadModels
        viewModel.selectMode(MediaMode.Audio)
        viewModel.selectAudioFlow(AudioFlow.Music)

        viewModel.generateAudio("лоуфай бит")
        runCurrent() // submit
        advanceTimeBy(3_000)
        runCurrent() // poll → Processing
        advanceTimeBy(6_000)
        runCurrent() // poll → Success + download & cache

        val element = viewModel.elements.value.single()
        assertTrue(element.isAudio, "music result must be an audio element, not an image")
        assertFalse(element.isVideo)
        assertEquals("media/jobs/job-1/0.mp3", element.localPath)
        assertTrue(File(fakeHome, ".pots/media/jobs/job-1/0.mp3").exists())
        assertTrue(viewModel.historyEntries.value.single().isAudio)
    }

    @Test
    fun imageResultStillCachesAsImage() = runTest(testDispatcher) {
        val provider = ScriptedProvider(
            catalog = listOf(MediaModel(id = "gpt-image-2", kind = MediaKind.Image)),
            polls = ArrayDeque(listOf(success("job-1"))),
        )
        val viewModel = viewModel(provider)
        runCurrent()

        viewModel.generateImage("кот")
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        val element = viewModel.elements.value.single()
        assertFalse(element.isAudio)
        assertFalse(element.isVideo)
        assertEquals("media/jobs/job-1/0.png", element.localPath)
        assertTrue(File(fakeHome, ".pots/media/jobs/job-1/0.png").exists())
    }

    /** App restart mid-job: the journal's isAudio flag must survive the resume. */
    @Test
    fun journalResumeKeepsAudioKind() = runTest(testDispatcher) {
        val journal = File(fakeHome, ".pots/media/history.json")
        journal.parentFile!!.mkdirs()
        journal.writeText(
            """[{"localId":"L1","createdAtMs":1,"isVideo":false,"isAudio":true,"model":"lyria-3",
               "prompt":"бит","status":"Processing","jobId":"job-9"}]
            """.trimIndent(),
        )
        val provider = ScriptedProvider(
            catalog = listOf(MediaModel(id = "lyria-3", kind = MediaKind.Music)),
            polls = ArrayDeque(listOf(success("job-9"))),
        )
        val viewModel = viewModel(provider)
        runCurrent() // history.start() → resume(job-9) → Success + download
        advanceTimeBy(1_000)
        runCurrent()

        val element = viewModel.elements.value.single()
        assertTrue(element.isAudio, "resumed music job must land as an audio element")
        assertEquals("media/jobs/job-9/0.mp3", element.localPath)
    }

    /** Block 4: every flow types its element and caches with the right extension. */
    @Test
    fun videoResultIsVideoElementCachedAsMp4() = runTest(testDispatcher) {
        val provider = ScriptedProvider(
            catalog = listOf(MediaModel(id = "veo-3.1", kind = MediaKind.Video)),
            polls = ArrayDeque(listOf(success("job-1"))),
        )
        val viewModel = viewModel(provider)
        runCurrent()

        viewModel.generateVideo("дрон над городом")
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        val element = viewModel.elements.value.single()
        assertTrue(element.isVideo, "video result must be a video element")
        assertFalse(element.isAudio)
        assertEquals("media/jobs/job-1/0.mp4", element.localPath)
        assertTrue(File(fakeHome, ".pots/media/jobs/job-1/0.mp4").exists())
        assertTrue(viewModel.historyEntries.value.single().isVideo)
    }

    /** Block 4: the TTS flow («Озвучить текст») lands as an audio element, not an image. */
    @Test
    fun ttsFlowResultIsAudioElementCachedAsMp3() = runTest(testDispatcher) {
        val provider = ScriptedProvider(
            catalog = listOf(MediaModel(id = "kokoro-tts", kind = MediaKind.Audio, task = "tts")),
            polls = ArrayDeque(listOf(success("job-1"))),
        )
        val viewModel = viewModel(provider)
        runCurrent()
        viewModel.selectMode(MediaMode.Audio)
        viewModel.selectAudioFlow(AudioFlow.Tts)

        viewModel.generateAudio("Привет, мир")
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        val element = viewModel.elements.value.single()
        assertTrue(element.isAudio, "TTS result must be an audio element")
        assertFalse(element.isVideo)
        assertEquals("media/jobs/job-1/0.mp3", element.localPath)
        assertTrue(File(fakeHome, ".pots/media/jobs/job-1/0.mp3").exists())
    }
}
