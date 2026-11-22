package com.pusios.pots.ui.media

import androidx.compose.ui.unit.IntSize
import com.pusios.pots.media.MAX_MULTI_IMAGE_REFERENCES
import com.pusios.pots.media.MediaCapValue
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.MediaParamDef
import com.pusios.pots.media.imageReferenceLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decision «what leaves the device» — before any pixel is rendered.
 *
 * The provider rejects `mask` outright (`400 unsupported_parameter`) and only
 * two image models in the live catalog declare `mask_url`, so the annotation is
 * visible ink in the picture and the zone is either a second picture or one
 * sentence of text. Which of the two is not a preference: it is the model's
 * image-field arity, and a one-picture model must never be handed two pictures.
 */
class CanvasPromptPlanTest {

    private val canvas = IntSize(1280, 720)
    private val zone = IntSize(300, 200)

    /** nano-banana's shape: `mode` with `edit`, `image_url` and `input_urls`. */
    private fun multiPictureModel() = MediaModel(
        id = "nano-banana",
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
    private fun onePictureModel() = MediaModel(
        id = "ideogram-v3",
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
    private fun textOnlyModel() = MediaModel(
        id = "z-image",
        kind = MediaKind.Image,
        params = listOf(MediaParamDef(key = "aspect_ratio", label = "Пропорции")),
        acceptsImageReference = false,
    )

    @Test
    fun theArityIsTheModelOwnImageFieldNotAGuess() {
        assertEquals(
            MAX_MULTI_IMAGE_REFERENCES,
            multiPictureModel().imageReferenceLimit(),
            "input_urls carries several pictures — the provider's multi-file cap",
        )
        assertEquals(1, onePictureModel().imageReferenceLimit(), "image_url carries one")
        assertEquals(0, textOnlyModel().imageReferenceLimit(), "no picture field at all")
    }

    @Test
    fun aMultiPictureModelGetsTheCanvasAndTheFramedZone() {
        val support = canvasPromptSupport(multiPictureModel())
        assertIs<CanvasPromptSupport.Ready>(support)
        assertTrue(support.limit >= 2, "two pictures need room for two: ${support.limit}")

        val images = canvasPromptImages(support.limit, hasZone = true)
        assertTrue(images.canvas)
        assertTrue(images.zone, "the zone travels as its own picture")
        assertFalse(images.zoneDescribedOnly)
    }

    @Test
    fun aOnePictureModelGetsTheCanvasOnlyAndTheZoneIsDescribedInText() {
        val support = canvasPromptSupport(onePictureModel())
        assertIs<CanvasPromptSupport.Ready>(support)

        val images = canvasPromptImages(support.limit, hasZone = true)
        assertTrue(images.canvas)
        assertFalse(images.zone, "a second picture would overwrite the canvas on a single-key model")
        assertTrue(images.zoneDescribedOnly)

        val text = canvasPromptText("убери подпись", canvas, images, zone)
        assertTrue(text.contains("убери подпись"), "the instruction is the head of the request")
        assertTrue(text.contains("1280×720"), "the canvas size is named: $text")
        assertTrue(text.contains("300×200"), "the zone size is named: $text")
        assertTrue(text.contains("обведена на канвасе"), "the zone is on the canvas itself: $text")
        assertFalse(text.contains("отдельным фрагментом"), "no picture was attached: $text")
    }

    @Test
    fun anAttachedZoneIsNamedAsAFragment() {
        val images = canvasPromptImages(2, hasZone = true)
        val text = canvasPromptText("сделай фон светлее", canvas, images, zone)
        assertTrue(text.contains("сделай фон светлее"))
        assertTrue(text.contains("отдельным фрагментом 300×200"), text)
    }

    @Test
    fun noZoneMeansOneSentenceAboutTheCanvas() {
        val images = canvasPromptImages(2, hasZone = false)
        assertFalse(images.zone)
        assertFalse(images.zoneDescribedOnly)
        val text = canvasPromptText("добавь подпись", canvas, images, zone = null)
        assertEquals("добавь подпись\n\nКанвас приложен целиком, 1280×720 px, с разметкой.", text)
    }

    @Test
    fun theInstructionIsTrimmedAndNeverLost() {
        val images = canvasPromptImages(2, hasZone = false)
        val text = canvasPromptText("  перекрась фон  ", canvas, images, zone = null)
        assertTrue(text.startsWith("перекрась фон\n\n"), text)
    }

    @Test
    fun aModelWithoutImageInputIsRefusedWithItsOwnName() {
        val model = textOnlyModel()
        val support = canvasPromptSupport(model)
        assertEquals(CanvasPromptSupport.NoImageInput, support)

        val refusal = canvasPromptRefusal(support, model)
        assertTrue(refusal != null && refusal.contains("z-image"), "the message names the model: $refusal")

        val error = canvasPromptError(support, model)
        assertTrue(error != null && error.title == "Модель не принимает картинку")
    }

    @Test
    fun noModelIsNotTheSameAsAModelThatTakesNoPicture() {
        assertEquals(CanvasPromptSupport.NoModel, canvasPromptSupport(null))
        assertNull(canvasPromptRefusal(CanvasPromptSupport.Ready(2), multiPictureModel()))
        assertNull(canvasPromptError(CanvasPromptSupport.Ready(2), multiPictureModel()))
    }

    @Test
    fun nothingIsSentWhenNoModelCanTakeAPicture() {
        val images = canvasPromptImages(limit = 0, hasZone = true)
        assertFalse(images.canvas)
        assertFalse(images.zone)
        assertEquals("убери подпись", canvasPromptText("убери подпись", canvas, images, zone))
    }
}
