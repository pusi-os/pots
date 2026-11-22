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
// separate card). So the annotation is *visible ink in the picture*: the arrows
// and strokes the user drew land in the PNG, and the zone is either a second
// picture or one sentence of text.
//
// No frame is drawn into the picture either. A dragged zone cannot be expressed
// in the renderer's space: cards and text grow around their own centre, while
// strokes and arrows grow from the origin, so no single affine map covers both
// and a frame would sit in the wrong place at any zoom but 1. The zone travels
// as its own crop instead, which is exact at any pan and zoom because the crop
// *is* the screen rectangle the finger saw.
//
// The one hard limit is arity: a model with a single-key image field cannot take
// two pictures, and sending both would silently replace the canvas with the zone
// (`appendImageReference` wrote one key per reference). A one-picture model
// therefore gets the zone alone when there is one — the picture of what the user
// asked to change — rather than a whole canvas with the zone described in words
// the picture does not show.

/** Which pictures the request carries, and what the text says about the zone. */
@Immutable
internal data class CanvasPromptImages(
    /** The annotated whole canvas. */
    val canvas: Boolean,
    /** The framed zone as its own picture. */
    val zone: Boolean,
    /** No picture travels at all: the zone is only named in the text. */
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

/**
 * What travels, given the model's picture arity and whether a zone was dragged:
 *
 * | arity | zone | pictures |
 * |---|---|---|
 * | ≥ 2 | yes | canvas + zone |
 * | ≥ 2 | no  | canvas |
 * | 1   | yes | zone only — a second picture would overwrite the first |
 * | 1   | no  | canvas |
 * | 0   | yes | none; the text is the only carrier |
 */
internal fun canvasPromptImages(limit: Int, hasZone: Boolean): CanvasPromptImages = when {
    limit >= 2 -> CanvasPromptImages(canvas = true, zone = hasZone, zoneDescribedOnly = false)
    limit == 1 && hasZone -> CanvasPromptImages(canvas = false, zone = true, zoneDescribedOnly = false)
    limit == 1 -> CanvasPromptImages(canvas = true, zone = false, zoneDescribedOnly = false)
    else -> CanvasPromptImages(canvas = false, zone = false, zoneDescribedOnly = hasZone)
}

/**
 * The text that goes with the pictures: the user's own instruction, then one
 * sentence saying what the attached picture is. Sizes are the real sizes of the
 * attached bitmaps, so the model and the user read the same numbers the screen
 * showed. A zone never gets «обведена на канвасе»: nothing is drawn around it,
 * the picture either is the zone or there is no zone picture.
 */
internal fun canvasPromptText(
    instruction: String,
    canvas: IntSize?,
    images: CanvasPromptImages,
    zone: IntSize?,
): String = buildString {
    append(instruction.trim())
    when {
        images.canvas && canvas != null && images.zone && zone != null -> {
            append("\n\nКанвас приложен целиком, ")
            append(canvas.width).append("×").append(canvas.height).append(" px, с разметкой.")
            append(" Зона правки приложена отдельным фрагментом ${zone.width}×${zone.height} — измени только её.")
        }

        images.canvas && canvas != null -> {
            append("\n\nКанвас приложен целиком, ")
            append(canvas.width).append("×").append(canvas.height).append(" px, с разметкой.")
        }

        images.zone && zone != null ->
            append("\n\nПриложен фрагмент канваса ${zone.width}×${zone.height} — это область, которую нужно изменить.")

        images.zoneDescribedOnly && zone != null ->
            append("\n\nЗона правки ${zone.width}×${zone.height} — это область, которую нужно изменить.")
    }
}

/** The reason the send button is off, in the user's language; null when it is on. */
internal fun canvasPromptRefusal(support: CanvasPromptSupport, model: MediaModel?): String? = when (support) {
    CanvasPromptSupport.NoModel -> "Модель не выбрана — выберите модель изображения"

    CanvasPromptSupport.NoImageInput ->
        "«${model?.id ?: "Модель"}» не принимает картинку — правку по канвасу отправить некуда. Выберите модель, которая умеет редактировать изображение."

    is CanvasPromptSupport.Ready -> null
}

/**
 * The send button's state, decided in one place so the screen and its tests
 * read the same rule: off while a refusal stands, while the instruction is
 * empty, while the picture is still rendering — and while the previous tap is
 * encoding the screenshots, which takes seconds and would otherwise send the
 * same request twice.
 */
@Immutable
internal data class CanvasPromptSend(val enabled: Boolean, val label: String)

internal fun canvasPromptSend(
    refusal: String?,
    instruction: String,
    canvasReady: Boolean,
    preparing: Boolean,
): CanvasPromptSend = when {
    preparing -> CanvasPromptSend(enabled = false, label = "Готовим скриншот…")
    refusal != null || !canvasReady || instruction.isBlank() -> CanvasPromptSend(enabled = false, label = "Отправить в модель")
    else -> CanvasPromptSend(enabled = true, label = "Отправить в модель")
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
 * What the canvas-prompt screen hands over: exactly the pictures it previewed
 * and promised in «Уйдёт: …», plus the typed instruction. Either picture can be
 * absent — a one-picture model gets the zone alone, and a model with no picture
 * field gets neither (the request is then refused before it is built).
 */
@Immutable
internal data class CanvasPromptRequest(
    val instruction: String,
    /** The annotated whole canvas, opaque, as the preview shows it. */
    val canvas: ImageBitmap? = null,
    /** The zone's own crop, opaque: the screen rectangle the user dragged. */
    val zone: ImageBitmap? = null,
)
