package com.pusios.pots.ui.media

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.pusios.pots.media.MediaErrorCategory
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.imageReferenceLimit

// «Спросить модель по канвасу»: what actually leaves the device, decided once
// and shown to the user before they press anything.
//
// A mask is not an option on this provider: `POST /v1/images/edits` rejects the
// `mask` parameter with 400 `unsupported_parameter`, and only two image models in
// the live catalog declare `mask_url` at all (`flux-fill`, `qwen-image-2-1` — a
// separate card). So the annotation is *visible ink in the picture* — arrows and
// strokes the user drew land in the PNG — plus a text sentence naming the zone.
//
// The one hard limit is arity: a model with a single-key image field cannot take
// two pictures, and sending both would silently replace the canvas with the zone
// (`appendImageReference` wrote one key per reference). Therefore the plan says
// up front what the model can carry, and the screen states it: with one picture
// the zone is still drawn on the canvas and described in the text, it just does
// not travel as its own image.

/** Which pictures the request carries, and what happened to the framed zone. */
@Immutable
internal data class CanvasPromptImages(
    /** The annotated whole canvas. */
    val canvas: Boolean,
    /** The framed zone as its own picture. */
    val zone: Boolean,
    /** The zone is drawn on the canvas and named in the text, but not attached. */
    val zoneDescribedOnly: Boolean,
)

/** Whether the selected model can be asked about the canvas at all. */
internal sealed interface CanvasPromptSupport {
    /** [limit] pictures fit in one request (1 or more). */
    data class Ready(val limit: Int) : CanvasPromptSupport

    /** No image model is selected yet. */
    data object NoModel : CanvasPromptSupport

    /** The model takes no picture: there is nowhere to send the canvas. */
    data object NoImageInput : CanvasPromptSupport
}

internal fun canvasPromptSupport(model: MediaModel?): CanvasPromptSupport = when {
    model == null -> CanvasPromptSupport.NoModel
    !model.acceptsImageReference -> CanvasPromptSupport.NoImageInput
    else -> CanvasPromptSupport.Ready(model.imageReferenceLimit().coerceAtLeast(1))
}

internal fun canvasPromptImages(limit: Int, hasZone: Boolean): CanvasPromptImages = when {
    limit >= 2 -> CanvasPromptImages(canvas = true, zone = hasZone, zoneDescribedOnly = false)
    limit == 1 -> CanvasPromptImages(canvas = true, zone = false, zoneDescribedOnly = hasZone)
    else -> CanvasPromptImages(canvas = false, zone = false, zoneDescribedOnly = hasZone)
}

/**
 * The text that goes with the pictures: the user's own instruction, then one
 * sentence saying what the attached canvas is and where the zone is. Sizes are
 * the real sizes of the attached bitmaps, so the model and the user read the
 * same numbers the screen showed.
 */
internal fun canvasPromptText(
    instruction: String,
    canvas: IntSize,
    images: CanvasPromptImages,
    zone: IntSize?,
): String = buildString {
    append(instruction.trim())
    if (!images.canvas) return@buildString
    append("\n\nКанвас приложен целиком, ")
    append(canvas.width).append("×").append(canvas.height).append(" px, с разметкой.")
    when {
        zone != null && images.zone ->
            append(" Зона правки приложена отдельным фрагментом ${zone.width}×${zone.height} — измени только её.")

        zone != null && images.zoneDescribedOnly ->
            append(" Зона правки ${zone.width}×${zone.height} обведена на канвасе — измени только её.")
    }
}

/** The reason the send button is off, in the user's language; null when it is on. */
internal fun canvasPromptRefusal(support: CanvasPromptSupport, model: MediaModel?): String? = when (support) {
    CanvasPromptSupport.NoModel -> "Модель не выбрана — выберите модель изображения"

    CanvasPromptSupport.NoImageInput ->
        "«${model?.id ?: "Модель"}» не принимает картинку — правку по канвасу отправить некуда. Выберите модель, которая умеет редактировать изображение."

    is CanvasPromptSupport.Ready -> null
}

/** The same refusal as a §8 studio error, for the path that skips the screen; null when allowed. */
internal fun canvasPromptError(support: CanvasPromptSupport, model: MediaModel?): MediaStudioError? = when (support) {
    CanvasPromptSupport.NoModel -> MediaStudioError(
        "Нет доступной модели",
        "Каталог не вернул модель изображения. Обновите каталог и попробуйте снова.",
        MediaErrorCategory.Provider,
    )

    CanvasPromptSupport.NoImageInput -> MediaStudioError(
        "Модель не принимает картинку",
        "«${model?.id ?: "Модель"}» не умеет редактировать изображение, поэтому канвас отправить некуда. Выберите модель с приёмом исходника.",
        MediaErrorCategory.Provider,
    )

    is CanvasPromptSupport.Ready -> null
}

/**
 * What the canvas-prompt screen hands over: the pictures exactly as previewed,
 * the typed instruction, and the framed zone's size (named in the text even
 * when the model cannot take it as a second picture).
 */
@Immutable
internal data class CanvasPromptRequest(
    val instruction: String,
    /** The annotated whole canvas, opaque, as the preview shows it. */
    val canvas: ImageBitmap,
    /** The framed zone, when the model can take a second picture. */
    val zone: ImageBitmap? = null,
    /** The framed zone's size in px; null when no zone was framed. */
    val zoneSize: IntSize? = null,
)
