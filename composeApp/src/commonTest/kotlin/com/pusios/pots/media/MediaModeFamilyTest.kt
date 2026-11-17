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
        // nano-banana / nano-banana-pro on the live catalog: [text-to-image, edit].
        val model = model(MediaKind.Image, "text-to-image", "edit")
        assertNull(model.modeForReferences())
        assertTrue(model.modeHasNoReferenceRoute())
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

    private fun model(kind: MediaKind, vararg options: String) = MediaModel(
        id = "m",
        kind = kind,
        params = listOf(MediaParamDef(key = "mode", label = "mode", options = options.map { MediaCapValue(it) })),
    )
}
