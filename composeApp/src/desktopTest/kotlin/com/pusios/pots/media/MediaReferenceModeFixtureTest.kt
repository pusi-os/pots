package com.pusios.pots.media

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Total check over a verbatim snapshot of the live catalog
 * (`docs/design/media-expansion/pots-media-catalog-live-2026-11-17.json`,
 * fetched 2026-11-17 from the public `GET /api/media-catalog`): for every
 * model, with and without references, the `mode` field is either absent or a
 * value that model declares *and* that belongs to its own kind family.
 *
 * The snapshot is committed as-is — never hand-edited — so catalog drift shows
 * up here instead of on a paid request.
 */
class MediaReferenceModeFixtureTest {

    private val provider = PlusVibeMediaProvider()

    private val catalog: List<MediaModel> by lazy { provider.parseCatalog(loadFixture()) }

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
            assertEquals("image", mode.modeFamily, "${model.id}: «$mode» is not an image route")
        }
    }

    // --- the reported regression: nano-banana family ---

    @Test
    fun nanoBananaFamilyGetsNoForeignModeButKeepsTheReference() {
        listOf("nano-banana", "nano-banana-pro").forEach { id ->
            val model = catalog.first { it.id == id }
            assertTrue(modeOptions(model).none { it == "image-to-image" }, "$id must not declare image-to-image")
            val body = assertNotNull(body(model, listOf(imageReference())), id)
            assertNull(opts(body)["mode"], "$id must not get a foreign mode")
            assertTrue(carriesImageReference(opts(body)), "$id still carries the reference")
        }
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
            assertEquals(familyOf(model.kind), mode.modeFamily, "${model.id}: «$mode» is not a ${model.kind} route")
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

    private fun familyOf(kind: MediaKind): String? = when (kind) {
        MediaKind.Image -> "image"
        MediaKind.Video -> "video"
        MediaKind.Other3d -> "3d"
        MediaKind.Audio, MediaKind.Music -> null
    }

    private fun carriesImageReference(opts: JsonObject): Boolean = opts.keys.any { it.startsWith("image") || it.startsWith("input") || it.startsWith("reference_image") || it.startsWith("first_frame") }

    private fun imageReference() = MediaReference(base64DataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==")

    private fun videoReference() = MediaReference(base64DataUri = "data:video/mp4;base64,AAAAIGZ0eXA=", isVideo = true)

    private fun loadFixture(): String {
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

    private companion object {
        const val FIXTURE = "pots-media-catalog-live-2026-11-17.json"
    }
}
