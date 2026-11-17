package com.pusios.pots.media

/**
 * `mode` is a per-model route selector, not a free-form hint: a value the model
 * does not declare is a hard 422 (live catalog, 2026-11-17 — 38 models declare
 * `mode`, 22 of them without `image-to-image`).
 *
 * Pots sends a mode only for a reference request, and only a route of the
 * model's own family: `image-to-image` for image models, `image-to-video` for
 * video, `image-to-3d` for 3d. `edit` names no family by itself, but the
 * provider documents it as an image-input route — it is accepted as an image
 * route only when the model declares it *and* the catalog documents it with a
 * mandatory image input ([MediaModel.imageInputModes]); nano-banana and
 * ideogram route references that way. It is never a global synonym and never
 * applies to video or 3d.
 *
 * Any other value that names no family (`std`, `pro`, `4K`, `remove-bg`) is no
 * reference route — when a model has none, [modeForReferences] returns null,
 * the key is left out of the body and the provider default stands.
 */
fun MediaModel.modeForReferences(): String? {
    val family = kind.modeFamily ?: return null
    val declared = params.firstOrNull { it.key == MODE_PARAM }?.options?.map { it.value } ?: return null
    declared.firstOrNull { it.modeFamily == family && it != "text-to-$family" }?.let { return it }
    if (family == IMAGE_FAMILY && EDIT_ROUTE in declared && EDIT_ROUTE in imageInputModes) return EDIT_ROUTE
    return null
}

/**
 * True when the model declares `mode` but offers no route of its own family —
 * Pots cannot switch it into a reference mode and sends no `mode` at all.
 */
fun MediaModel.modeHasNoReferenceRoute(): Boolean = modeForReferences() == null && params.any { it.key == MODE_PARAM }

/**
 * Family a `mode` value names: `image-to-image` → `image`, `image-edit` →
 * `image`, `first-frame-to-video` → `video`, `text-to-3d` → `3d`. Null when the
 * value names no family (`4K`, `edit`, `upscale`).
 */
internal val String.modeFamily: String?
    get() {
        val tail = substringAfterLast("to-", this).lowercase()
        return MODE_FAMILIES.firstOrNull { tail.startsWith(it) }
    }

private val MediaKind.modeFamily: String?
    get() = when (this) {
        MediaKind.Image -> "image"
        MediaKind.Video -> "video"
        MediaKind.Other3d -> "3d"
        MediaKind.Audio, MediaKind.Music -> null
    }

private const val MODE_PARAM = "mode"

/** The catalog's image-input route for models that have no `image-to-image`. */
private const val EDIT_ROUTE = "edit"

private const val IMAGE_FAMILY = "image"

private val MODE_FAMILIES = listOf("image", "video", "3d")
