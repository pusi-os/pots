package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.Offset
import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaHistoryStore
import com.pusios.pots.media.MediaJobRequest
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.MediaPriceQuote
import com.pusios.pots.media.MediaProvider
import com.pusios.pots.media.PixelGrid
import com.pusios.pots.media.Stroke
import com.pusios.pots.media.bakeStrokes
import com.pusios.pots.media.decodeImageToGrid
import com.pusios.pots.media.encodeGridToPng
import com.pusios.pots.readAppStorageBytes
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Block 7 (spec §5): the reference that reaches the model is the capture —
 * the node content with the pencil layer baked in, at the SOURCE resolution,
 * nothing from the UI. The chain under test: baked capture → canvas element
 * → «Использовать как референс» → MediaReference with the capture bytes.
 * Fully offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceFormationWiringTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeHome: File
    private lateinit var originalHome: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeHome = kotlin.io.path.createTempDirectory("pots-reference-wiring").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        System.setProperty("user.home", originalHome)
        fakeHome.deleteRecursively()
    }

    private fun newViewModel(): MediaStudioViewModel {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repository = MediaGenerationRepository(OfflineProvider(), scope)
        val history = MediaHistoryStore(repository, scope) { "" }
        return MediaStudioViewModel(
            appSettings = AppSettings(MapSettings()),
            apiKeyProvider = { "" },
            instanceIdProvider = { "reference-test" },
            repository = repository,
            history = history,
            enhancer = { it },
            videoAspectProber = { null },
        )
    }

    @Test
    fun bakedCaptureBecomesReferenceAtSourceResolution() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()

        // The draw sheet's «Применить» output: strokes baked onto the source grid.
        val grid = PixelGrid(40, 30, IntArray(40 * 30) { 0xFFFFFFFF.toInt() })
        val stroke = Stroke(listOf(Offset(5f, 5f), Offset(35f, 25f)), 0xFFE53935.toInt(), 4f)
        val baked = encodeGridToPng(grid.bakeStrokes(listOf(stroke)))

        assertTrue(viewModel.addEditedImageElement(baked))
        runCurrent()
        val element = viewModel.elements.value.single()

        viewModel.applyAction(element.id, CanvasAction.UseAsReference)

        val reference = viewModel.references.value.single()
        assertEquals(element.localPath, reference.localPath)
        val bytes = readAppStorageBytes(reference.localPath!!)
        assertNotNull(bytes, "the capture must exist in app storage")
        val decoded = decodeImageToGrid(bytes)
        assertEquals(40, decoded.width, "the reference keeps the source width")
        assertEquals(30, decoded.height, "the reference keeps the source height")
    }

    /** Every network method refuses: the suite must never reach a provider. */
    private class OfflineProvider : MediaProvider {
        override suspend fun listModels(key: String): List<MediaModel> = emptyList()
        override suspend fun quotePrice(key: String, request: MediaJobRequest): MediaPriceQuote = error("offline test: no provider calls")
        override suspend fun submitJob(key: String, request: MediaJobRequest): MediaJobState = error("offline test: no provider calls")
        override suspend fun pollJob(key: String, jobId: String): MediaJobState = error("offline test: no provider calls")
        override suspend fun downloadResult(key: String, url: String): ByteArray = error("offline test: no provider calls")
    }
}
