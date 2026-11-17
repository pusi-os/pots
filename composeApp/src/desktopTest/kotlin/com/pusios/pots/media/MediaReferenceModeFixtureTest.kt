package com.pusios.pots.media

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Total check over a verbatim snapshot of the live catalog
 * (`docs/design/media-expansion/pots-media-catalog-live-2026-11-17.json`,
 * fetched 2026-11-17 from the public `GET /api/media-catalog`): for every
 * model, with and without references, the `mode` field is either absent or a
 * value that model declares *and* that routes its own kind family — either by
 * naming the family (`image-to-image`, `image-to-video`, …) or through the
 * `edit` alias, which counts only for image models whose catalog operation
 * documents `edit` with a mandatory image input.
 *
 * The snapshot is committed as-is — never hand-edited — so catalog drift shows
 * up here instead of on a paid request.
 */
class MediaReferenceModeFixtureTest {

    // --- acceptance: all image models × {no references, references} ---

    @Test
    fun everyImageModelSendsOnlyItsOwnDeclaredImageMode() {
        val imageModels = catalog.filter { it.kind == MediaKind.Image }
        assertTrue(imageModels.size >= 20, "image models in the snapshot: ${imageModels.size}")
        imageModels.forEach { model ->
            val plain = assertNotNull(body(model, emptyList()), "no-reference body for ${model.id}")
            assertNull(opts(plain)["mode"], "no references must leave the provider default: ${model.id}")

            val withRefs = body(model, listOf(imageReference())) ?: return@forEach
            val mode = opts(withRefs)["mode"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            assertTrue(mode in modeOptions(model), "${model.id} declares no «$mode»")
            assertTrue(isImageRoute(model, mode), "${model.id}: «$mode» is not an image route")
        }
    }

    // --- the reported regression: nano-banana family ---

    @Test
    fun nanoBananaFamilyRoutesItsReferenceThroughTheEditAlias() {
        listOf("nano-banana", "nano-banana-pro").forEach { id ->
            val model = catalog.first { it.id == id }
            assertTrue(modeOptions(model).none { it == "image-to-image" }, "$id must not declare image-to-image")
            assertTrue("edit" in model.imageInputModes, "$id documents edit with an image input")
            val body = assertNotNull(body(model, listOf(imageReference())), id)
            assertEquals("edit", opts(body)["mode"]?.jsonPrimitive?.contentOrNull, "$id routes the reference via edit")
            assertTrue(carriesImageReference(opts(body)), "$id still carries the reference")
            assertFalse(model.modeHasNoReferenceRoute(), "$id does support references")
        }
    }

    @Test
    fun ideogramModelsAlsoRouteThroughTheEditAlias() {
        listOf("ideogram-v3", "ideogram-character").forEach { id ->
            val model = catalog.first { it.id == id }
            assertEquals("edit", model.modeForReferences(), id)
            assertFalse(model.modeHasNoReferenceRoute(), id)
        }
    }

    @Test
    fun modelsWithNoDocumentedImageRouteStillSendNothing() {
        // `edit` is not declared here, or documents no image input — the alias
        // must not spread beyond the models the catalog qualifies.
        listOf("recraft", "grok-imagine-2.0-ext").forEach { id ->
            val model = catalog.first { it.id == id }
            assertNull(model.modeForReferences(), id)
            assertTrue(model.modeHasNoReferenceRoute(), id)
            val body = assertNotNull(body(model, listOf(imageReference())), id)
            assertNull(opts(body)["mode"], "$id must keep the provider default")
        }
    }

    @Test
    fun editAliasNeverLeaksIntoVideoModels() {
        // wan-2-7 declares `edit` too, but its operation documents a *video*
        // input — the video family route must stand.
        val model = catalog.first { it.id == "wan-2-7" }
        assertFalse("edit" in model.imageInputModes, "wan-2-7 edit takes a video, not an image")
        val body = assertNotNull(body(model, listOf(imageReference())), "wan-2-7")
        assertEquals("image-to-video", opts(body)["mode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun imageToImageModelsStillSwitchIntoTheirOwnRoute() {
        listOf("gpt-image-2", "gpt-image-2-6", "seedream-5", "qwen-image").forEach { id ->
            val body = assertNotNull(body(catalog.first { it.id == id }, listOf(imageReference())), id)
            assertEquals("image-to-image", opts(body)["mode"]?.jsonPrimitive?.contentOrNull, id)
        }
    }

    // --- every model of every kind that declares `mode` ---

    @Test
    fun everyModeSentIsDeclaredAndOfTheModelsFamily() {
        val withMode = catalog.filter { model -> model.params.any { it.key == "mode" } }
        assertTrue(withMode.size >= 30, "models declaring `mode` in the snapshot: ${withMode.size}")
        withMode.forEach { model ->
            val plain = assertNotNull(body(model, emptyList()), "no-reference body for ${model.id}")
            assertNull(opts(plain)["mode"], "no references must leave the provider default: ${model.id}")

            val references = if (model.kind == MediaKind.Video) listOf(videoReference()) else listOf(imageReference())
            val body = body(model, references) ?: return@forEach
            val mode = opts(body)["mode"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            assertTrue(mode in modeOptions(model), "${model.id} declares no «$mode»")
            if (mode.modeFamily == null) {
                // Only the qualified `edit` alias may name no family.
                assertEquals("edit", mode, "${model.id}: «$mode» is not a route")
                assertEquals(MediaKind.Image, model.kind, "${model.id}: the edit alias is image-only")
                assertTrue("edit" in model.imageInputModes, "${model.id}: edit has no documented image input")
            } else {
                assertEquals(familyOf(model.kind), mode.modeFamily, "${model.id}: «$mode» is not a ${model.kind} route")
            }
        }
    }

    @Test
    fun snapshotCarriesBothModeClasses() {
        val imageWithMode = catalog.filter { it.kind == MediaKind.Image && it.params.any { p -> p.key == "mode" } }
        assertTrue(imageWithMode.any { it.modeForReferences() != null }, "no image model has a reference route")
        assertTrue(imageWithMode.any { it.modeForReferences() == null }, "the regression class is gone from the snapshot")
    }

    @Test
    fun explicitUserModeIsNeverOverridden() {
        val request = MediaJobRequest(
            model = "nano-banana",
            prompt = "тест",
            opts = mapOf("mode" to "edit"),
            references = listOf(imageReference()),
        )
        assertEquals("edit", opts(provider.buildGenerateBody(request))["mode"]?.jsonPrimitive?.contentOrNull)
    }

    // --- helpers ---

    private fun body(model: MediaModel, references: List<MediaReference>): JsonObject? {
        val request = MediaJobRequest(model = model.id, prompt = "тест", references = references)
        return runCatching { provider.buildGenerateBody(request) }.getOrElse { error ->
            // A model that accepts no reference is refused locally, before the
            // request leaves the app — nothing to validate about `mode`.
            assertIs<MediaException.BadRequest>(error, "unexpected failure for ${model.id}")
            null
        }
    }

    private fun opts(body: JsonObject): JsonObject = body["opts"]?.jsonObject ?: JsonObject(emptyMap())

    private fun modeOptions(model: MediaModel): Set<String> = model.params.firstOrNull { it.key == "mode" }?.options?.map { it.value }?.toSet().orEmpty()

    /** A family route, or the `edit` alias the catalog qualifies for this model. */
    private fun isImageRoute(model: MediaModel, mode: String): Boolean = mode.modeFamily == "image" || (mode == "edit" && mode in model.imageInputModes)

    private fun familyOf(kind: MediaKind): String? = when (kind) {
        MediaKind.Image -> "image"
        MediaKind.Video -> "video"
        MediaKind.Other3d -> "3d"
        MediaKind.Audio, MediaKind.Music -> null
    }

    private fun carriesImageReference(opts: JsonObject): Boolean = opts.keys.any { it.startsWith("image") || it.startsWith("input") || it.startsWith("reference_image") || it.startsWith("first_frame") }

    private fun imageReference() = MediaReference(base64DataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==")

    private fun videoReference() = MediaReference(base64DataUri = "data:video/mp4;base64,AAAAIGZ0eXA=", isVideo = true)

    private companion object {
        const val FIXTURE = "pots-media-catalog-live-2026-11-17.json"

        // JUnit builds a fresh instance per test method: read and parse the
        // 1.27 MB snapshot once per class instead of nine times.
        val provider = PlusVibeMediaProvider()
        val catalog: List<MediaModel> by lazy { provider.parseCatalog(loadFixture()) }

        fun loadFixture(): String {
            // Test working dir is the module dir; fall back to the repo root.
            val candidates = listOf(
                File("../docs/design/media-expansion/$FIXTURE"),
                File("docs/design/media-expansion/$FIXTURE"),
                File("../../docs/design/media-expansion/$FIXTURE"),
            )
            val found = candidates.firstOrNull { it.isFile }
            assertNotNull(found, "fixture $FIXTURE not found; tried: " + candidates.joinToString { it.absolutePath })
            return found.readText()
        }
    }
}
