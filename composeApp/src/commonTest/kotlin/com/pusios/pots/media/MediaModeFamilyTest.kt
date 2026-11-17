package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `mode` is per-model: only a route the model declares, and only one of its own
 * family, may be sent. A foreign value is a hard 422 (live catalog 2026-11-17).
 */
class MediaModeFamilyTest {

    @Test
    fun modeFamilyNamesTheFamilyOfTheRoute() {
        assertEquals("image", "image-to-image".modeFamily)
        assertEquals("image", "image-edit".modeFamily)
        assertEquals("image", "text-to-image".modeFamily)
        assertEquals("video", "first-frame-to-video".modeFamily)
        assertEquals("video", "reference-to-video".modeFamily)
        assertEquals("3d", "image-to-3d".modeFamily)
        // The output side decides: an image-to-video route is a video route.
        assertEquals("video", "image-to-video".modeFamily)
    }

    @Test
    fun serviceValuesNameNoFamily() {
        // Live catalog service values of the same `mode` param — never a route.
        listOf(
            "4K", "std", "pro", "edit", "remix", "reframe", "character", "region_edit", "remove-bg", "upscale",
            "simple", "advanced", "speed", "cost", "quality", "normal", "fun", "spicy", "lite", "basic",
            "transition", "extend",
        ).forEach { assertNull(it.modeFamily, "«$it» is not a family route") }
    }

    @Test
    fun imageModelGetsItsOwnImageRoute() {
        val model = model(MediaKind.Image, "text-to-image", "image-to-image")
        assertEquals("image-to-image", model.modeForReferences())
        assertFalse(model.modeHasNoReferenceRoute())
    }

    @Test
    fun imageModelWithoutAnImageRouteSendsNothing() {
        // recraft / grok-imagine-2.0-ext on the live catalog: `mode` names no
        // image input route, so the key stays out of the request.
        val model = model(MediaKind.Image, "text-to-image", "edit")
        assertNull(model.modeForReferences())
        assertTrue(model.modeHasNoReferenceRoute())
    }

    @Test
    fun imageModelWithADocumentedEditInputSendsTheEditAlias() {
        // nano-banana / nano-banana-pro / ideogram-*: [text-to-image, edit] and
        // the catalog documents `edit` with a mandatory image input.
        val model = model(MediaKind.Image, "text-to-image", "edit", imageInputModes = setOf("edit"))
        assertEquals("edit", model.modeForReferences())
        assertFalse(model.modeHasNoReferenceRoute())
    }

    @Test
    fun editAliasNeedsBothADeclaredEditAndADocumentedImageInput() {
        // Declared but not documented as an image route.
        assertNull(model(MediaKind.Image, "text-to-image", "edit").modeForReferences())
        // Documented but not declared by this model.
        assertNull(model(MediaKind.Image, "text-to-image", imageInputModes = setOf("edit")).modeForReferences())
        // A different documented mode does not unlock the alias.
        assertNull(
            model(MediaKind.Image, "text-to-image", "edit", imageInputModes = setOf("remix")).modeForReferences(),
        )
    }

    @Test
    fun baseFamilyRouteWinsOverTheEditAlias() {
        val model = model(
            MediaKind.Image,
            "text-to-image",
            "edit",
            "image-to-image",
            imageInputModes = setOf("edit"),
        )
        assertEquals("image-to-image", model.modeForReferences())
    }

    @Test
    fun editAliasNeverLeaksIntoVideoOr3d() {
        // wan-2-7: `edit` there documents a *video* input, so the alias must
        // not apply — the base family route stands.
        val video = model(
            MediaKind.Video,
            "text-to-video",
            "image-to-video",
            "edit",
            imageInputModes = setOf("edit"),
        )
        assertEquals("image-to-video", video.modeForReferences())
        // No family route at all → no mode, even though `edit` is documented.
        val videoOnlyEdit = model(MediaKind.Video, "text-to-video", "edit", imageInputModes = setOf("edit"))
        assertNull(videoOnlyEdit.modeForReferences())
        assertTrue(videoOnlyEdit.modeHasNoReferenceRoute())
        assertNull(model(MediaKind.Other3d, "text-to-3d", "edit", imageInputModes = setOf("edit")).modeForReferences())
    }

    @Test
    fun textToImageIsTheBaselineNotAReferenceRoute() {
        assertNull(model(MediaKind.Image, "text-to-image").modeForReferences())
        // A mode param with nothing but service values is no reference route either.
        assertNull(model(MediaKind.Video, "std", "pro", "4K").modeForReferences())
        assertTrue(model(MediaKind.Video, "std", "pro", "4K").modeHasNoReferenceRoute())
    }

    @Test
    fun familyOfAnotherKindIsNeverSent() {
        assertNull(model(MediaKind.Image, "text-to-image", "image-to-video").modeForReferences())
        assertEquals("image-to-video", model(MediaKind.Video, "text-to-video", "image-to-video").modeForReferences())
    }

    @Test
    fun firstFamilyRouteWinsInCatalogOrder() {
        assertEquals(
            "image-to-video",
            model(MediaKind.Video, "text-to-video", "image-to-video", "reference-to-video").modeForReferences(),
        )
        assertEquals(
            "first-frame-to-video",
            model(MediaKind.Video, "text-to-video", "first-frame-to-video", "reference-to-video").modeForReferences(),
        )
    }

    @Test
    fun threeDimensionalModelsUseTheirOwnRoute() {
        assertEquals("image-to-3d", model(MediaKind.Other3d, "text-to-3d", "image-to-3d").modeForReferences())
    }

    @Test
    fun audioAndMusicHaveNoFamily() {
        // `mode` here is a service switch (simple/advanced), not a route of a
        // media family — nothing is ever sent, and the no-route case is honest.
        val audio = model(MediaKind.Audio, "simple", "advanced")
        assertNull(audio.modeForReferences())
        assertTrue(audio.modeHasNoReferenceRoute())
        assertNull(model(MediaKind.Music, "simple", "advanced").modeForReferences())
    }

    @Test
    fun modelWithoutModeParamSendsNothing() {
        val model = MediaModel(id = "kling", kind = MediaKind.Video)
        assertNull(model.modeForReferences())
        assertFalse(model.modeHasNoReferenceRoute())
    }

    private fun model(
        kind: MediaKind,
        vararg options: String,
        imageInputModes: Set<String> = emptySet(),
    ) = MediaModel(
        id = "m",
        kind = kind,
        params = listOf(MediaParamDef(key = "mode", label = "mode", options = options.map { MediaCapValue(it) })),
        imageInputModes = imageInputModes,
    )
}
