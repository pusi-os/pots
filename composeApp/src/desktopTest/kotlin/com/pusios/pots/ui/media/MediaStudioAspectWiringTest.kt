package com.pusios.pots.ui.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaHistoryStore
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.MediaJobStatus
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaProvider
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Block 4: a video element's box is sized from the frame aspect (@joni's
 * contract — «у видео от кадра»). The view model probes new video elements
 * exactly once through the injected prober and stores the result on the
 * element; images and audio never probe. Fully offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaStudioAspectWiringTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeHome: File
    private lateinit var originalHome: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeHome = kotlin.io.path.createTempDirectory("pots-aspect-wiring").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        System.setProperty("user.home", originalHome)
        fakeHome.deleteRecursively()
    }

    private val probed = mutableListOf<String>()

    private fun newViewModel(prober: suspend (String) -> Float?): MediaStudioViewModel {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repository = MediaGenerationRepository(OfflineProvider(), scope)
        val history = MediaHistoryStore(repository, scope, persistDispatcher = testDispatcher) { "" }
        return MediaStudioViewModel(
            appSettings = AppSettings(MapSettings()),
            apiKeyProvider = { "" },
            instanceIdProvider = { "aspect-test" },
            repository = repository,
            history = history,
            enhancer = { it },
            videoAspectProber = { source ->
                probed += source
                prober(source)
            },
        )
    }

    @Test
    fun videoImportProbesAndStoresAspect() = runTest(testDispatcher) {
        val viewModel = newViewModel { 1280f / 720f }
        viewModel.importFromGallery("media/imports/clip.mp4", isVideo = true)
        runCurrent()

        assertEquals(listOf("media/imports/clip.mp4"), probed)
        assertEquals(1280f / 720f, assertIs<VideoLayer>(viewModel.elements.value.single()).aspectRatio!!, 0.0001f)
    }

    @Test
    fun generatedVideoElementProbesByLocalPath() = runTest(testDispatcher) {
        val viewModel = newViewModel { 9f / 16f }
        viewModel.addElement(
            MediaJobState(
                jobId = "j1",
                status = MediaJobStatus.Success,
                kind = MediaKind.Video,
                resultUrls = listOf("https://plusvibeapi.ru/api/media/file/abc/0?sig=x"),
                localPath = "media/jobs/j1/0.mp4",
            ),
        )
        runCurrent()

        val element = assertIs<VideoLayer>(viewModel.elements.value.single())
        // The cached local copy is probed, not the signed URL.
        assertEquals(listOf("media/jobs/j1/0.mp4"), probed)
        assertEquals(9f / 16f, element.aspectRatio!!, 0.0001f)
    }

    @Test
    fun imageAndAudioImportsNeverProbe() = runTest(testDispatcher) {
        val viewModel = newViewModel { error("images and audio must not probe video metadata") }
        viewModel.importFromGallery("media/imports/pic.png", isVideo = false)
        viewModel.importFromGallery("media/imports/track.mp3", isVideo = false, isAudio = true)
        runCurrent()

        assertTrue(probed.isEmpty())
        assertTrue(viewModel.elements.value.none { it is VideoLayer && it.aspectRatio != null })
    }

    @Test
    fun failedProbeLeavesElementUntouched() = runTest(testDispatcher) {
        val viewModel = newViewModel { null }
        viewModel.importFromGallery("media/imports/clip.mp4", isVideo = true)
        runCurrent()

        assertEquals(1, probed.size)
        assertNull(assertIs<VideoLayer>(viewModel.elements.value.single()).aspectRatio)
    }

    /** Every network method refuses: the suite must never reach a provider. */
    private class OfflineProvider : MediaProvider {
        override suspend fun listModels(key: String) = emptyList<com.pusios.pots.media.MediaModel>()
        override suspend fun quotePrice(key: String, request: com.pusios.pots.media.MediaJobRequest) = error("offline test: no provider calls")
        override suspend fun submitJob(key: String, request: com.pusios.pots.media.MediaJobRequest) = error("offline test: no provider calls")
        override suspend fun pollJob(key: String, jobId: String) = error("offline test: no provider calls")
        override suspend fun downloadResult(key: String, url: String) = error("offline test: no provider calls")
    }
}
