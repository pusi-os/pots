package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.MAX_INLINE_REFERENCE_BYTES
import com.pusios.pots.media.MediaCapValue
import com.pusios.pots.media.MediaGenerationRepository
import com.pusios.pots.media.MediaHistoryStore
import com.pusios.pots.media.MediaJobRequest
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.MediaJobStatus
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.MediaParamDef
import com.pusios.pots.media.MediaPriceQuote
import com.pusios.pots.media.MediaProvider
import com.pusios.pots.media.PixelGrid
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
import org.jetbrains.compose.resources.decodeToImageBitmap
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Спросить модель по канвасу» end to end, offline: the screenshot that reaches
 * the request, the text that rides with it, what a one-picture model gets
 * instead, and what the canvas looks like afterwards.
 *
 * The provider here is a recorder, never a network client: the assertion is on
 * the [MediaJobRequest] the studio would have submitted, which is the exact
 * object `PlusVibeMediaProvider` turns into the JSON body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaCanvasPromptWiringTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeHome: File
    private lateinit var originalHome: String
    private val provider = RecordingProvider()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeHome = kotlin.io.path.createTempDirectory("pots-canvas-prompt").toFile()
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
        val repository = MediaGenerationRepository(provider, scope)
        val history = MediaHistoryStore(repository, scope, persistDispatcher = testDispatcher) { "" }
        return MediaStudioViewModel(
            appSettings = AppSettings(MapSettings()),
            apiKeyProvider = { "test-key" },
            instanceIdProvider = { "canvas-prompt-test" },
            repository = repository,
            history = history,
            enhancer = { it },
            videoAspectProber = { null },
            pixelDispatcher = testDispatcher,
        )
    }

    /** The canvas the user sees: one picture card, 232 units wide. */
    private suspend fun canvasBitmap(): ImageBitmap {
        val layer = ImageLayer(id = "card", transform = LayerTransform(x = 0f, y = 0f), aspectRatio = 1f)
        val frames = mapOf<String, Painter>("card" to BitmapPainter(solid(232, 232, Color.Red)))
        return assertNotNull(
            renderCanvas(
                layers = listOf(layer),
                scope = ExportScope.Whole,
                includeAnnotation = true,
                background = EXPORT_DARK_BACKGROUND,
                frames = frames,
                density = 1f,
            ),
            "the annotated canvas must render",
        )
    }

    @Test
    fun theScreenAndTheSendNameTheSameImageModelWhateverTheModeIs() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(MULTI_PICTURE_MODEL)
        // The mode switch says «video», and the video model takes a picture too —
        // so the old screen named it while the request went to the image model.
        viewModel.selectMode(MediaMode.Video)
        viewModel.setParam("duration", "5")
        viewModel.setParam("resolution", "720p")

        assertEquals(
            MULTI_PICTURE_MODEL,
            viewModel.canvasPromptModel()?.id,
            "the screen and the send read one resolver: the image model, not the video one",
        )

        assertTrue(viewModel.askModelAboutCanvas(CanvasPromptRequest("убери подпись", canvasBitmap())))
        runCurrent()

        val request = assertNotNull(provider.submitted.single())
        assertEquals(MULTI_PICTURE_MODEL, request.model)
        assertTrue(
            "duration" !in request.opts && "resolution" !in request.opts,
            "the video mode's chips must not ride an image request: ${request.opts}",
        )
        assertNull(viewModel.error.value)
    }

    @Test
    fun theAudioModeIsNotARefusalAndItsChipsStayBehind() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(MULTI_PICTURE_MODEL)
        // No audio model in the catalog takes a picture, but the send never asks
        // one — it asks the image model. The screen used to show the audio model
        // and refuse the whole feature in this mode.
        viewModel.selectMode(MediaMode.Audio)
        viewModel.setParam("speed", "1.5")

        val model = assertNotNull(viewModel.canvasPromptModel(), "the image model is still there in audio mode")
        assertEquals(MULTI_PICTURE_MODEL, model.id)
        assertNull(canvasPromptRefusal(canvasPromptSupport(model), model), "audio mode is not a refusal")

        assertTrue(viewModel.askModelAboutCanvas(CanvasPromptRequest("убери подпись", canvasBitmap())))
        runCurrent()

        val request = assertNotNull(provider.submitted.single())
        assertEquals(MULTI_PICTURE_MODEL, request.model)
        assertTrue("speed" !in request.opts, "the audio chip stays behind: ${request.opts}")
    }

    @Test
    fun theScreenshotGoesIntoTheReferencesAndTheInstructionIntoThePrompt() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(MULTI_PICTURE_MODEL)
        val canvas = canvasBitmap()

        assertTrue(viewModel.askModelAboutCanvas(CanvasPromptRequest("убери подпись", canvas)))
        runCurrent()

        // One reference — the canvas itself, written to app storage as PNG.
        val reference = viewModel.references.value.single()
        assertNotNull(reference.localPath)
        val bytes = assertNotNull(readAppStorageBytes(reference.localPath!!), "the screenshot must exist on disk")
        val grid = decodeImageToGrid(bytes)
        assertEquals(canvas.width, grid.width)
        assertEquals(canvas.height, grid.height)

        // …and it is opaque: the model path never hands over transparency.
        assertEquals(255, grid.argb[0] ushr 24, "the corner pixel must be fully opaque")
        assertEquals(255, grid.argb[grid.argb.size - 1] ushr 24, "the opposite corner too")

        val request = assertNotNull(provider.submitted.single(), "the request must reach the provider")
        assertEquals(MULTI_PICTURE_MODEL, request.model)
        assertTrue(request.prompt.contains("убери подпись"), "the instruction is in the prompt: ${request.prompt}")
        assertTrue(request.prompt.contains("с разметкой"), "the prompt says the annotation is in the picture: ${request.prompt}")
        assertTrue(
            request.prompt.contains(canvas.width.toString() + "×" + canvas.height),
            "the prompt names the canvas size: ${request.prompt}",
        )
        assertEquals(1, request.references.size, "one picture, not a gallery")
        assertEquals(reference.localPath, request.references.single().localPath)
        assertNull(viewModel.error.value, "nothing failed")
    }

    @Test
    fun theFramedZoneTravelsWithTheCanvasOnAMultiPictureModel() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(MULTI_PICTURE_MODEL)
        val canvas = canvasBitmap()
        val zone = assertNotNull(
            renderCanvas(
                layers = listOf(ImageLayer(id = "card", transform = LayerTransform(), aspectRatio = 1f)),
                scope = ExportScope.Region(left = 24f, top = 24f, right = 124f, bottom = 84f),
                includeAnnotation = true,
                background = EXPORT_DARK_BACKGROUND,
                frames = mapOf("card" to BitmapPainter(solid(232, 232, Color.Red))),
                density = 1f,
            ),
        )

        assertTrue(
            viewModel.askModelAboutCanvas(
                CanvasPromptRequest(
                    instruction = "убери подпись",
                    canvas = canvas,
                    zone = zone,
                ),
            ),
        )
        runCurrent()

        assertEquals(2, viewModel.references.value.size, "canvas + zone")
        val request = assertNotNull(provider.submitted.single())
        assertEquals(2, request.references.size)
        assertTrue(request.prompt.contains("отдельным фрагментом " + zone.width + "×" + zone.height), request.prompt)

        // The canvas is the first picture and the zone the second: the provider
        // maps them in this order into input_urls / input_base64[].
        val canvasGrid = decodeImageToGrid(assertNotNull(readAppStorageBytes(request.references[0].localPath!!)))
        val zoneGrid = decodeImageToGrid(assertNotNull(readAppStorageBytes(request.references[1].localPath!!)))
        assertEquals(canvas.width, canvasGrid.width)
        assertEquals(zone.width, zoneGrid.width)
        assertEquals(zone.height, zoneGrid.height)
    }

    @Test
    fun aOnePictureModelGetsTheZoneAloneWhenOneIsDragged() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(ONE_PICTURE_MODEL)
        val canvas = canvasBitmap()
        // A smaller zone, so the single reference can be told apart from the
        // canvas by its size alone.
        val zone = assertNotNull(
            renderCanvas(
                layers = listOf(ImageLayer(id = "card", transform = LayerTransform(), aspectRatio = 1f)),
                scope = ExportScope.Region(left = 24f, top = 24f, right = 124f, bottom = 84f),
                includeAnnotation = true,
                background = EXPORT_DARK_BACKGROUND,
                frames = mapOf("card" to BitmapPainter(solid(232, 232, Color.Red))),
                density = 1f,
            ),
        )

        assertTrue(
            viewModel.askModelAboutCanvas(
                CanvasPromptRequest("перекрась зону", canvas = canvas, zone = zone),
            ),
        )
        runCurrent()

        // One picture, and it is the zone: a second one would overwrite the
        // first on a single-key model, and the canvas is not what was asked to
        // change.
        val reference = viewModel.references.value.single()
        val grid = decodeImageToGrid(assertNotNull(readAppStorageBytes(assertNotNull(reference.localPath))))
        assertEquals(zone.width, grid.width, "the zone's width, not the canvas's")
        assertEquals(zone.height, grid.height)

        val request = assertNotNull(provider.submitted.single())
        assertEquals(1, request.references.size)
        assertTrue(request.prompt.contains("фрагмент канваса ${zone.width}×${zone.height}"), request.prompt)
        assertTrue(!request.prompt.contains(canvas.width.toString() + "×" + canvas.height), "the canvas is not promised: ${request.prompt}")
    }

    @Test
    fun aOnePictureModelWithoutAZoneGetsTheCanvas() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(ONE_PICTURE_MODEL)
        val canvas = canvasBitmap()

        assertTrue(viewModel.askModelAboutCanvas(CanvasPromptRequest("убери подпись", canvas = canvas)))
        runCurrent()

        val grid = decodeImageToGrid(assertNotNull(readAppStorageBytes(assertNotNull(viewModel.references.value.single().localPath))))
        assertEquals(canvas.width, grid.width)
        val request = assertNotNull(provider.submitted.single())
        assertTrue(request.prompt.contains("Канвас приложен целиком"), request.prompt)
    }

    @Test
    fun aModelThatTakesNoPictureIsRefusedInsteadOfGeneratingFromScratch() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(TEXT_ONLY_MODEL)
        val canvas = canvasBitmap()

        assertTrue(!viewModel.askModelAboutCanvas(CanvasPromptRequest("сделай светлее", canvas)))
        runCurrent()

        val error = assertNotNull(viewModel.error.value, "the user must be told")
        assertEquals("Модель не принимает картинку", error.title)
        assertTrue(error.detail.contains(TEXT_ONLY_MODEL), "the message names the model: ${error.detail}")
        assertTrue(provider.submitted.isEmpty(), "nothing was submitted — no silent generation from scratch")
        assertTrue(viewModel.references.value.isEmpty(), "no picture was parked for a later request either")
    }

    @Test
    fun theRequestCarriesExactlyThePicturesTheScreenPromised() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(MULTI_PICTURE_MODEL)
        // An older reference from the file picker must not ride along: the
        // screen said «Уйдёт: канвас …», so that is what goes.
        viewModel.addReference(com.pusios.pots.media.MediaReference(url = "https://example.test/other.png"))
        assertEquals(1, viewModel.references.value.size)

        assertTrue(viewModel.askModelAboutCanvas(CanvasPromptRequest("убери подпись", canvasBitmap())))
        runCurrent()

        assertEquals(1, viewModel.references.value.size)
        assertNull(viewModel.references.value.single().url, "the canvas replaced the older reference")
        assertEquals(1, provider.submitted.single().references.size)
    }

    @Test
    fun theAnswerLandsAsANewLayerAndTheCanvasIsUntouched() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(MULTI_PICTURE_MODEL)
        // A layer the user already had on the canvas.
        viewModel.addEditedImageElement(encodeGridToPng(PixelGrid(8, 8, IntArray(64) { 0xFF00FF00.toInt() })))
        runCurrent()
        val before = viewModel.elements.value
        assertEquals(1, before.size)

        assertTrue(viewModel.askModelAboutCanvas(CanvasPromptRequest("убери подпись", canvasBitmap())))
        runCurrent()
        assertEquals(before, viewModel.elements.value, "asking never touches the canvas")

        // The answer arrives through the journal's own auto-place: a new layer on top.
        viewModel.addElement(
            MediaJobState(
                jobId = "job-answer",
                status = MediaJobStatus.Success,
                resultUrls = listOf("https://example.test/answer.png"),
                kind = MediaKind.Image,
            ),
        )
        val after = viewModel.elements.value
        assertEquals(2, after.size)
        assertEquals(before.single(), after.first(), "the original layer is unchanged and still below")
        assertTrue(after.last() is ImageLayer)
        assertEquals("job-answer", (after.last() as ImageLayer).jobId)
    }

    @Test
    fun anOversizedScreenshotIsOfferedForCompressionAndThenGoes() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.selectImageModel(MULTI_PICTURE_MODEL)
        // 2048² of noise: the screenshot a dense canvas really produces, and
        // the only way a PNG crosses the inline limit.
        val noise = IntArray(2048 * 2048) { Random(it).nextInt() }
        val png = encodeGridToPng(PixelGrid(2048, 2048, noise))
        assertTrue(png.size > MAX_INLINE_REFERENCE_BYTES, "the fixture must be oversized: ${png.size} bytes")

        assertTrue(viewModel.askModelAboutCanvas(CanvasPromptRequest("убери шум", png.decodeToImageBitmap())))
        runCurrent()

        val error = assertNotNull(viewModel.error.value)
        assertEquals("Референс слишком большой", error.title)
        assertEquals(0, error.oversizedReferenceIndex, "the snackbar offers «Сжать» for this reference")
        assertTrue(provider.submitted.isEmpty(), "an oversized reference never reaches the provider")

        // The existing ladder does the work; the retry resubmits by itself.
        viewModel.compressReference(0)
        runCurrent()

        val compressedPath = assertNotNull(viewModel.references.value.single().localPath)
        assertTrue(compressedPath.endsWith(".jpg"), "the compressed copy is a JPEG: $compressedPath")
        val compressed = assertNotNull(readAppStorageBytes(compressedPath))
        assertTrue(compressed.size <= MAX_INLINE_REFERENCE_BYTES, "under the limit: ${compressed.size} bytes")
        assertEquals(1, provider.submitted.size, "the generation was repeated with the compressed reference")
        assertEquals("убери шум", provider.submitted.single().prompt.substringBefore('\n'))
    }

    private fun solid(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density = Density(1f, 1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(color)
        }
        return bitmap
    }

    /** Records what would have been submitted; never touches the network. */
    private class RecordingProvider : MediaProvider {
        val submitted = mutableListOf<MediaJobRequest>()

        override suspend fun listModels(apiKey: String): List<MediaModel> = listOf(
            canvasPromptMultiPictureModel(),
            canvasPromptOnePictureModel(),
            canvasPromptTextOnlyModel(),
            canvasPromptVideoModel(),
        )

        override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote = MediaPriceQuote(priceRub = 0.0, quoteId = "quote-test")

        override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
            submitted += request
            return MediaJobState(jobId = "job-" + submitted.size, status = MediaJobStatus.Processing, kind = MediaKind.Image)
        }

        override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState = // The studio's poller loops for as long as a job is not terminal, and the
            // test scheduler runs that loop in virtual time — a fake that keeps saying
            // «processing» spins `runTest` forever. These tests assert the submitted
            // request, not the job lifecycle, so the recorder ends the job at once.
            MediaJobState(jobId = jobId, status = MediaJobStatus.Fail, kind = MediaKind.Image)

        override suspend fun downloadResult(apiKey: String, url: String): ByteArray = ByteArray(0)
    }
}

private const val MULTI_PICTURE_MODEL = "nano-banana"
private const val ONE_PICTURE_MODEL = "ideogram-v3"
private const val TEXT_ONLY_MODEL = "z-image"
private const val VIDEO_MODEL = "seedance-2.0"

/** nano-banana's shape: `mode` with `edit`, `image_url` and `input_urls`. */
private fun canvasPromptMultiPictureModel() = MediaModel(
    id = MULTI_PICTURE_MODEL,
    kind = MediaKind.Image,
    params = listOf(
        MediaParamDef(
            key = "mode",
            label = "Режим",
            options = listOf(MediaCapValue("text-to-image"), MediaCapValue("edit")),
        ),
        MediaParamDef(key = "image_url", label = "Картинка"),
        MediaParamDef(key = "input_urls", label = "Картинки"),
    ),
    acceptsImageReference = true,
    imageInputModes = setOf("edit"),
)

/** ideogram-v3's shape: a single-key image field only. */
private fun canvasPromptOnePictureModel() = MediaModel(
    id = ONE_PICTURE_MODEL,
    kind = MediaKind.Image,
    params = listOf(
        MediaParamDef(
            key = "mode",
            label = "Режим",
            options = listOf(MediaCapValue("text-to-image"), MediaCapValue("edit")),
        ),
        MediaParamDef(key = "image_url", label = "Картинка"),
    ),
    acceptsImageReference = true,
    imageInputModes = setOf("edit"),
)

/** z-image's shape: an image model with no picture input at all. */
private fun canvasPromptTextOnlyModel() = MediaModel(
    id = TEXT_ONLY_MODEL,
    kind = MediaKind.Image,
    params = listOf(MediaParamDef(key = "aspect_ratio", label = "Пропорции")),
    acceptsImageReference = false,
)

/**
 * A video model that takes a picture (72 of them do in the live catalog): in
 * Video mode it is the mode's model, and the canvas prompt must still name and
 * use the image one.
 */
private fun canvasPromptVideoModel() = MediaModel(
    id = VIDEO_MODEL,
    kind = MediaKind.Video,
    params = listOf(
        MediaParamDef(key = "image_url", label = "Кадр"),
        MediaParamDef(key = "duration", label = "Длительность"),
        MediaParamDef(key = "resolution", label = "Разрешение"),
    ),
    acceptsImageReference = true,
)
