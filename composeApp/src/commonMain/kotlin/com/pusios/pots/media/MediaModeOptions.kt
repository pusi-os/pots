package com.pusios.pots.media

/**
 * `mode` is a per-model route selector, not a free-form hint: a value the model
 * does not declare is a hard 422 (live catalog, 2026-11-17 — 38 models declare
 * `mode`, 22 of them without `image-to-image`).
 *
 * Pots sends a mode only for a reference request, and only a route of the
 * model's own family: `image-to-image` for image models, `image-to-video` for
 * video, `image-to-3d` for 3d. Values that name no family (`std`, `pro`, `4K`,
 * `edit`, `remove-bg`) are not reference routes of a family — when a model has
 * none, [modeForReferences] returns null, the key is left out of the body and
 * the provider default stands.
 */
fun MediaModel.modeForReferences(): String? {
    val family = kind.modeFamily ?: return null
    return params.firstOrNull { it.key == MODE_PARAM }
        ?.options
        ?.asSequence()
        ?.map { it.value }
        ?.firstOrNull { it.modeFamily == family && it != "text-to-$family" }
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

private val MODE_FAMILIES = listOf("image", "video", "3d")
