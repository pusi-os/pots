package com.pusios.pots.ui.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.AudioFlow
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaHistoryStore
import com.pusios.pots.media.MediaProvider
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
import kotlin.test.assertTrue

/**
 * The dock's «✨» button must read the toolbar mode (and the remembered audio
 * flow) at click time and enhance the prompt for THAT content type — image,
 * video, music or voice-over — instead of always enhancing as an image.
 *
 * Lives in desktopTest: the view model wires the real project store, which is
 * redirected into a throwaway home (the established pattern). Fully offline —
 * the fake provider refuses every network method.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaStudioEnhanceWiringTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeHome: File
    private lateinit var originalHome: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeHome = kotlin.io.path.createTempDirectory("pots-enhance-wiring").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        System.setProperty("user.home", originalHome)
        fakeHome.deleteRecursively()
    }

    private fun newViewModel(capture: (String) -> Unit): MediaStudioViewModel {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val repository = MediaGenerationRepository(OfflineProvider(), scope)
        val history = MediaHistoryStore(repository, scope, persistDispatcher = testDispatcher) { "" }
        return MediaStudioViewModel(
            appSettings = AppSettings(MapSettings()),
            apiKeyProvider = { "" },
            instanceIdProvider = { "enhance-test" },
            repository = repository,
            history = history,
            enhancer = { text ->
                capture(text)
                "улучшенный текст"
            },
        )
    }

    /** Enhances [prompt] in [mode]/[flow] and returns the template the enhancer saw. */
    private fun TestScope.enhanceCaptured(mode: MediaMode, flow: AudioFlow?, prompt: String): String {
        var captured = ""
        var result = ""
        val viewModel = newViewModel { captured = it }
        runCurrent()
        viewModel.selectMode(mode)
        if (flow != null) viewModel.selectAudioFlow(flow)
        viewModel.enhancePrompt(prompt) { result = it }
        runCurrent()
        assertEquals("улучшенный текст", result)
        assertTrue(captured.contains("Запрос: $prompt"), captured)
        return captured
    }

    @Test
    fun imageModeEnhancesAsImage() = runTest(testDispatcher) {
        assertTrue(enhanceCaptured(MediaMode.Image, null, "кот").contains("изображения"))
    }

    @Test
    fun videoModeEnhancesAsVideo() = runTest(testDispatcher) {
        val captured = enhanceCaptured(MediaMode.Video, null, "дрон над городом")
        assertTrue(captured.contains("видео"), captured)
        assertTrue(captured.contains("движение"), captured)
    }

    @Test
    fun audioTtsFlowEnhancesForVoiceover() = runTest(testDispatcher) {
        val captured = enhanceCaptured(MediaMode.Audio, AudioFlow.Tts, "Привет, запятая, мир")
        assertTrue(captured.contains("озвуч"), captured)
        assertTrue(captured.contains("ремарк"), captured)
    }

    @Test
    fun audioMusicFlowEnhancesAsMusic() = runTest(testDispatcher) {
        assertTrue(enhanceCaptured(MediaMode.Audio, AudioFlow.Music, "лоуфай бит").contains("музык"))
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
