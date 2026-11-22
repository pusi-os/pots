package com.pusios.pots.media

import com.pusios.pots.writeAppStorageBytes
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the provider puts on the wire for a canvas prompt: the live catalog of
 * 2026-11-17 (214 models), the real `parseCatalog`, the real `buildGenerateBody`.
 *
 * The defect this pins down: `appendImageReference` wrote one key per reference,
 * so on a model that declares both `image_url` and `input_urls` the second
 * picture silently replaced the first. Canvas + zone is exactly two pictures, so
 * the arity now picks the field: several → the plural one (`input_urls` for a
 * public URL, `input_base64[]` for local bytes), one → the single key as before.
 */
class CanvasPromptReferenceMappingTest {

    private val provider = PlusVibeMediaProvider()

    private lateinit var fakeHome: File
    private lateinit var originalHome: String

    /**
     * The provider reads a local reference's bytes from app storage, so the
     * fixture has to be a real file: a path alone maps to nothing and the mapper
     * rightly refuses it.
     */
    @BeforeTest
    fun setUp() {
        fakeHome = kotlin.io.path.createTempDirectory("pots-canvas-mapping").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.absolutePath)
        for (name in listOf("canvas", "zone")) {
            assertTrue(
                writeAppStorageBytes("media/refs/$name.png", encodeGridToPng(PixelGrid(4, 4, IntArray(16) { 0xFF3366CC.toInt() }))),
                "the $name fixture must land in app storage",
            )
        }
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalHome)
        fakeHome.deleteRecursively()
    }

    private val catalog: List<MediaModel> by lazy { provider.parseCatalog(loadFixture()) }

    private fun loadFixture(): String {
        val name = "pots-media-catalog-live-2026-11-17.json"
        val candidates = listOf(
            File("../docs/design/media-expansion/$name"),
            File("docs/design/media-expansion/$name"),
            File("../../docs/design/media-expansion/$name"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(found, "fixture $name not found; tried: " + candidates.joinToString { it.absolutePath })
        return found.readText()
    }

    private fun model(id: String): MediaModel = catalog.first { it.id == id }

    private fun body(modelId: String, vararg references: MediaReference): kotlinx.serialization.json.JsonObject =
        provider.buildGenerateBody(
            MediaJobRequest(
                model = modelId,
                prompt = "убери подпись",
                opts = emptyMap(),
                references = references.toList(),
            ),
        )

    private fun local(name: String) = MediaReference(localPath = "media/refs/$name.png")

    private fun remote(name: String) = MediaReference(url = "https://example.test/$name.png")

    @Test
    fun twoLocalPicturesGoIntoThePluralBase64FieldAndBothSurvive() {
        val body = body(model("nano-banana").id, local("canvas"), local("zone"))
        val opts = body["opts"]!!.jsonObject

        val pictures = assertNotNull(opts["input_base64"], "local bytes go to the documented multi-file field").jsonArray
        assertEquals(2, pictures.size, "canvas + zone, neither replacing the other")
        assertTrue(pictures[0].jsonPrimitive.content.startsWith("data:image/png;base64,"))
        assertTrue(pictures[1].jsonPrimitive.content.startsWith("data:image/png;base64,"))
        assertNull(opts["image_base64"], "the single-key field would carry only the last picture")
        assertNull(opts["input_urls"], "no public URL was given")
        // The route the request takes is still the model's own edit route.
        assertEquals("edit", opts["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun twoPublicPicturesGoIntoThePluralUrlField() {
        val body = body(model("nano-banana").id, remote("canvas"), remote("zone"))
        val opts = body["opts"]!!.jsonObject

        val pictures = assertNotNull(opts["input_urls"]).jsonArray
        assertEquals(
            listOf("https://example.test/canvas.png", "https://example.test/zone.png"),
            pictures.map { it.jsonPrimitive.content },
        )
        assertNull(opts["image_url"], "the single key would hold only the zone")
    }

    @Test
    fun onePictureKeepsTheSingleKeyTheMapperAlwaysUsed() {
        val body = body(model("nano-banana").id, local("canvas"))
        val opts = body["opts"]!!.jsonObject
        assertNotNull(opts["image_base64"], "one picture, the familiar field")
        assertNull(opts["input_base64"], "no array for a single file")
    }

    @Test
    fun aSingleKeyModelStillGetsItsOwnField() {
        val single = model("ideogram-v3")
        assertNull(single.imageReferenceField(several = true)?.takeIf { it.plural }, "ideogram-v3 takes one picture")

        val body = body(single.id, remote("canvas"))
        val opts = body["opts"]!!.jsonObject
        assertEquals("https://example.test/canvas.png", opts["image_url"]?.jsonPrimitive?.content)
        assertNull(opts["input_urls"])
    }

    @Test
    fun aModelWithoutPictureInputIsRefusedByTheMapper() {
        val textOnly = model("imagen4")
        assertTrue(!textOnly.acceptsImageReference)

        val failure = assertFailsWith<MediaException.BadRequest> { body(textOnly.id, remote("canvas")) }
        assertTrue(failure.message.orEmpty().contains("imagen4"), "the message names the model: ${failure.message}")
    }

    @Test
    fun theCatalogAgreesWithTheArityRule() {
        val image = catalog.filter { it.kind == MediaKind.Image }
        val multi = image.filter { it.imageReferenceLimit() > 1 }
        val single = image.filter { it.imageReferenceLimit() == 1 }
        val none = image.filter { it.imageReferenceLimit() == 0 }

        // The live numbers this feature was designed against: most edit models
        // take several pictures, a handful take one, and some take none at all.
        assertTrue(multi.size >= 15, "multi-picture edit models: ${multi.size}")
        assertTrue(single.size >= 15, "single-picture edit models: ${single.size}")
        assertTrue(none.isNotEmpty(), "image models without picture input: ${none.map { it.id }}")
        assertEquals(image.size, multi.size + single.size + none.size)
        // Every model that declares `input_urls` can take two pictures; the
        // canvas prompt depends on that.
        assertTrue(image.filter { m -> m.params.any { it.key == "input_urls" } }.all { it.imageReferenceLimit() > 1 })
    }
}
