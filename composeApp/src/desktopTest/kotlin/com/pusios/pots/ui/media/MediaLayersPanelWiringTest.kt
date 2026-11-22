package com.pusios.pots.ui.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaHistoryStore
import com.pusios.pots.media.MediaJobRequest
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.MediaJobStatus
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.MediaPriceQuote
import com.pusios.pots.media.MediaProvider
import com.pusios.pots.media.getMediaLayersPanelOpen
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * The layers panel against the real studio view model: the panel owns no
 * state, so every row action must land in the one draw list, and the open /
 * collapsed flag must survive a restart through `MediaSettings`.
 *
 * Offline: no provider calls, layers are placed through `importFromGallery`
 * (bytes = null → no file writes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaLayersPanelWiringTest {

    private val testDispatcher = StandardTestDispatcher()
    private val settings = MapSettings()
    private lateinit var fakeHome: File
    private lateinit var originalHome: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeHome = kotlin.io.path.createTempDirectory("pots-layers-panel").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        System.setProperty("user.home", originalHome)
        fakeHome.deleteRecursively()
    }

    private object EmptyProvider : MediaProvider {
        override suspend fun listModels(key: String): List<MediaModel> = emptyList()
        override suspend fun quotePrice(key: String, request: MediaJobRequest) = MediaPriceQuote(priceRub = 1.0, quoteId = "q1")
        override suspend fun submitJob(key: String, request: MediaJobRequest) = MediaJobState(jobId = "job-1", status = MediaJobStatus.Submitting)
        override suspend fun pollJob(key: String, jobId: String) = MediaJobState(jobId = jobId, status = MediaJobStatus.Processing)
        override suspend fun downloadResult(key: String, url: String) = ByteArray(0)
    }

    private fun TestScope.viewModel(): MediaStudioViewModel {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repository = MediaGenerationRepository(EmptyProvider, scope)
        val history = MediaHistoryStore(repository, scope, persistDispatcher = testDispatcher) { "key" }
        return MediaStudioViewModel(
            appSettings = AppSettings(settings),
            apiKeyProvider = { "key" },
            instanceIdProvider = { "layers-test" },
            repository = repository,
            history = history,
            enhancer = { it },
            videoAspectProber = { null },
        )
    }

    /** Three layers on the canvas, bottom first — the draw order. */
    private fun MediaStudioViewModel.threeLayers(): List<String> {
        importFromGallery("media/imports/1.png", isVideo = false)
        importFromGallery("media/imports/2.png", isVideo = false)
        importFromGallery("media/imports/3.png", isVideo = false)
        return elements.value.map { it.id }
    }

    @Test
    fun draggingTheTopRowToTheBottomReordersTheDrawList() = runTest(testDispatcher) {
        val viewModel = viewModel()
        runCurrent()
        val (first, second, third) = viewModel.threeLayers()
        // Panel row 0 is the top layer; the draw index is the last one.
        assertEquals(third, viewModel.elements.value.last().id)

        viewModel.reorderLayer(fromDrawIndex = 2, toDrawIndex = 0)

        assertEquals(listOf(third, first, second), viewModel.elements.value.map { it.id })
        // Saved `zIndex` is the index, so it stays dense 0…n−1 with no holes.
        assertEquals(listOf(0, 1, 2), viewModel.elements.value.toProjectElements().map { it.zIndex })
    }

    @Test
    fun selectionIsOneStateForTheCanvasAndThePanel() = runTest(testDispatcher) {
        val viewModel = viewModel()
        runCurrent()
        val (first, _, third) = viewModel.threeLayers()

        // Tap on a canvas element and on a panel row both go through `select`.
        viewModel.select(third)
        assertEquals(third, viewModel.selectedId.value)

        viewModel.select(first)
        assertEquals(first, viewModel.selectedId.value)

        // A reorder keeps the selection on the same layer, not on a position.
        viewModel.reorderLayer(fromDrawIndex = 0, toDrawIndex = 2)
        assertEquals(first, viewModel.selectedId.value)

        viewModel.select(null)
        assertEquals(null, viewModel.selectedId.value)
    }

    @Test
    fun theEyeTakesALayerOutOfTheCanvasButNotOutOfTheList() = runTest(testDispatcher) {
        val viewModel = viewModel()
        runCurrent()
        val (first, second, third) = viewModel.threeLayers()

        viewModel.setLayerVisible(second, false)

        assertEquals(listOf(first, third), viewModel.elements.value.visibleLayers().map { it.id })
        assertEquals(3, viewModel.elements.value.size)
        assertTrue(layerSubtitle(viewModel.elements.value[1]).endsWith("скрыт"))

        viewModel.setLayerVisible(second, true)
        assertEquals(3, viewModel.elements.value.visibleLayers().size)
    }

    @Test
    fun opacityFromTheRowLandsOnTheLayer() = runTest(testDispatcher) {
        val viewModel = viewModel()
        runCurrent()
        val (first, _, _) = viewModel.threeLayers()

        viewModel.setLayerOpacity(first, 0.4f)

        assertEquals(0.4f, viewModel.elements.value.first().transform.opacity, 0.0001f)
        // Clamped, so a runaway drag cannot ask for alpha > 1 or < 0.
        viewModel.setLayerOpacity(first, 5f)
        assertEquals(1f, viewModel.elements.value.first().transform.opacity, 0f)
        viewModel.setLayerOpacity(first, -1f)
        assertEquals(0f, viewModel.elements.value.first().transform.opacity, 0f)
    }

    @Test
    fun theOpenStateOfThePanelSurvivesARestart() = runTest(testDispatcher) {
        val viewModel = viewModel()
        runCurrent()
        assertFalse(viewModel.layersPanelOpen.value, "collapsed by default: the canvas keeps its height")

        viewModel.setLayersPanelOpen(true)
        assertTrue(viewModel.layersPanelOpen.value)
        assertTrue(AppSettings(settings).getMediaLayersPanelOpen())

        // A fresh studio (app restart) reads the same setting back.
        val restarted = viewModel()
        runCurrent()
        assertTrue(restarted.layersPanelOpen.value)

        restarted.setLayersPanelOpen(false)
        assertFalse(AppSettings(settings).getMediaLayersPanelOpen())
    }
}
