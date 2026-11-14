package com.pusios.pots.ui.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.AudioFlow
import com.pusios.pots.media.MEDIA_PROJECT_SCHEMA_VERSION
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaHistoryStore
import com.pusios.pots.media.MediaJobRequest
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.MediaPriceQuote
import com.pusios.pots.media.MediaProjectFile
import com.pusios.pots.media.MediaProjectSettings
import com.pusios.pots.media.MediaProvider
import com.pusios.pots.media.decodeMediaProject
import com.pusios.pots.media.encodeMediaProject
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * The §12 wiring, end to end: the studio's mode and parameter chips live in
 * the view model flows — the same flows the screen collects — so the project
 * snapshot writes the ACTUALLY selected values (never a hardcoded Image/∅),
 * and open/import really switch the mode and chips the UI observes. The
 * «Сохранено» mark is set by a save and cleared only by the UI acknowledge.
 *
 * Lives in desktopTest: the real [com.pusios.pots.media.MediaProjectStore]
 * writes into app storage, which is redirected into a throwaway home here
 * (the established ffmpeg-test pattern). Fully offline — the fake provider
 * refuses every network method.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaStudioProjectWiringTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeHome: File
    private lateinit var originalHome: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeHome = kotlin.io.path.createTempDirectory("pots-project-wiring").toFile()
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
        val repository = MediaGenerationRepository(OfflineMediaProvider(), scope)
        val history = MediaHistoryStore(repository, scope) { "" }
        return MediaStudioViewModel(
            appSettings = AppSettings(MapSettings()),
            apiKeyProvider = { "" },
            instanceIdProvider = { "wiring-test" },
            repository = repository,
            history = history,
            enhancer = { it },
        )
    }

    /** The snapshot carries the mode and chips the user actually selected. */
    @Test
    fun snapshotCarriesActuallySelectedModeAndParams() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectMode(MediaMode.Video)
        viewModel.setParam("aspect_ratio", "16:9")

        val (_, bytes) = viewModel.exportActiveProject()
        val project = decodeMediaProject(bytes.decodeToString())
        assertEquals("Video", project.settings.mode)
        assertEquals(mapOf("aspect_ratio" to "16:9"), project.settings.params)
    }

    /** Autosave writes the actual mode/params into the active snapshot file. */
    @Test
    fun autosaveWritesActualModeAndParamsToActiveSnapshot() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectMode(MediaMode.Audio)
        viewModel.setParam("duration", "10")

        // Autosave fires ~2 s after the last change.
        advanceTimeBy(2500)
        runCurrent()

        val snapshot = File(fakeHome, ".pots/media/projects/active.potsproj")
        assertTrue(snapshot.isFile, "active snapshot missing on disk")
        val project = decodeMediaProject(snapshot.readText())
        assertEquals("Audio", project.settings.mode)
        assertEquals(mapOf("duration" to "10"), project.settings.params)
    }

    /** Import switches the mode, flow and chips the screen collects. */
    @Test
    fun importProjectSwitchesModeAndChipsInView() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        val incoming = MediaProjectFile(
            schemaVersion = MEDIA_PROJECT_SCHEMA_VERSION,
            name = "Импорт",
            createdAtMs = 1,
            updatedAtMs = 2,
            settings = MediaProjectSettings(
                mode = "Audio",
                audioFlow = "music",
                params = mapOf("duration" to "30"),
            ),
        )

        val ok = viewModel.importProject(encodeMediaProject(incoming).encodeToByteArray())

        assertTrue(ok)
        assertEquals(MediaMode.Audio, viewModel.mode.value)
        assertEquals(mapOf("duration" to "30"), viewModel.params.value)
        assertEquals(AudioFlow.Music, viewModel.audioFlow.value)
    }

    /** Opening a saved project brings the flows back to the saved values. */
    @Test
    fun openProjectRestoresModeAndChips() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectMode(MediaMode.Video)
        viewModel.setParam("aspect_ratio", "9:16")
        assertTrue(viewModel.saveProjectNow())
        val saved = viewModel.projects.value.firstOrNull() ?: error("saved project not listed")

        // Drift away from the saved state, then reopen.
        viewModel.selectMode(MediaMode.Image)
        viewModel.resetParams()
        viewModel.openProject(saved.id)

        assertEquals(MediaMode.Video, viewModel.mode.value)
        assertEquals(mapOf("aspect_ratio" to "9:16"), viewModel.params.value)
    }

    /** The «Сохранено» mark: set by a save, cleared by the UI acknowledge. */
    @Test
    fun savedMarkSetByManualSaveAndClearedByAcknowledge() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        assertFalse(viewModel.projectSaved.value)

        assertTrue(viewModel.saveProjectNow())
        assertTrue(viewModel.projectSaved.value)

        viewModel.acknowledgeProjectSaved()
        assertFalse(viewModel.projectSaved.value)
    }

    /** Every network method refuses: the suite must never reach a provider. */
    private class OfflineMediaProvider : MediaProvider {
        override suspend fun listModels(key: String): List<MediaModel> = emptyList()
        override suspend fun quotePrice(key: String, request: MediaJobRequest): MediaPriceQuote = error("offline test: no provider calls")

        override suspend fun submitJob(key: String, request: MediaJobRequest): MediaJobState = error("offline test: no provider calls")

        override suspend fun pollJob(key: String, jobId: String): MediaJobState = error("offline test: no provider calls")

        override suspend fun downloadResult(key: String, url: String): ByteArray = error("offline test: no provider calls")
    }
}
