package com.pusios.pots.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Media studio project file (spec §12): one `.potsproj` JSON with a schema
 * version. The codec must round-trip the full studio state (canvas layers
 * with transforms, text and stroke payloads, the assembly sequence,
 * parameters) and refuse unknown or corrupt files with the typed §8 errors.
 *
 * Schema 2 is the media-editor layer format; schema 1 files (no `kind`,
 * `opacity`, `visible`, `text`, `points`) must keep reading through defaults,
 * and a *newer* file must be refused instead of silently losing layers.
 */
class MediaProjectSerializationTest {

    private fun sampleProject() = MediaProjectFile(
        schemaVersion = MEDIA_PROJECT_SCHEMA_VERSION,
        name = "Проект, 14 ноя",
        createdAtMs = 1_700_000_000_000L,
        updatedAtMs = 1_700_000_900_000L,
        elements = listOf(
            MediaProjectElement(
                id = "el-1",
                kind = MediaElementKind.Image.name,
                jobId = "job-9",
                url = "https://example.com/cat.png",
                localPath = "media/jobs/job-9.png",
                isVideo = false,
                isAudio = false,
                x = 12.5f,
                y = -40f,
                scale = 1.25f,
                rotation = 90f,
                opacity = 0.6f,
                visible = false,
                zIndex = 0,
                aspectRatio = 1.5f,
            ),
            // A schema-1 style element: no kind at all, flags only.
            MediaProjectElement(
                id = "el-2",
                localPath = "media/edits/abc.wav",
                isAudio = true,
                zIndex = 1,
            ),
            MediaProjectElement(
                id = "el-3",
                kind = MediaElementKind.Stroke.name,
                zIndex = 2,
                colorArgb = -16777216,
                strokeWidthPx = 6f,
                points = "CgAAABQAAAAFAAIA/f/8/w==",
            ),
        ),
        sequence = MediaProjectSequence(
            clips = listOf(
                MediaProjectSequenceClip(elementId = "el-1", relPath = "media/jobs/job-9.png", isVideo = false, durationMs = 3000L),
            ),
            transitions = listOf(SequenceTransition.Crossfade.name),
        ),
        settings = MediaProjectSettings(
            mode = "Audio",
            audioFlow = "music",
            imageModelId = "gpt-image-2",
            videoModelId = "veo-3.1",
            ttsModelId = "gpt-4o-mini-tts",
            musicModelId = "suno",
            params = mapOf("duration" to "30"),
            drafts = mapOf("audio_music" to "лоу-фай трек"),
        ),
    )

    /** Re-encodes the sample with one top-level field replaced. */
    private fun withRootField(project: MediaProjectFile, key: String, value: JsonPrimitive): String {
        val root = Json.parseToJsonElement(encodeMediaProject(project)).jsonObject
        return JsonObject(root + (key to value)).toString()
    }

    @Test
    fun roundTripKeepsFullState() {
        val project = sampleProject()
        val decoded = decodeMediaProject(encodeMediaProject(project))
        assertEquals(project, decoded)
    }

    @Test
    fun schemaVersionIsTwo() {
        assertEquals(2, MEDIA_PROJECT_SCHEMA_VERSION)
        val root = Json.parseToJsonElement(encodeMediaProject(sampleProject())).jsonObject
        assertEquals("2", root["schemaVersion"]!!.jsonPrimitive.content)
        assertEquals(MEDIA_PROJECT_MARK, root["app"]!!.jsonPrimitive.content)
    }

    /** Autosave rewrites the active snapshot constantly — the default must stay compact. */
    @Test
    fun encodedProjectIsCompactAndPrettyIsOptIn() {
        val compact = encodeMediaProject(sampleProject())
        val pretty = encodeMediaProject(sampleProject(), pretty = true)
        assertFalse(compact.contains("\n"), "the default encoding must not pretty-print")
        assertTrue(pretty.contains("\n"), "pretty printing is a debugging knob")
        assertEquals(sampleProject(), decodeMediaProject(compact))
        assertEquals(sampleProject(), decodeMediaProject(pretty))
    }

    /** A schema-1 file has none of the new fields: it decodes through the defaults. */
    @Test
    fun schemaOneFileReadsWithDefaults() {
        val text = """
            {"schemaVersion":1,"app":"$MEDIA_PROJECT_MARK","name":"Старый","createdAtMs":1,"updatedAtMs":2,
             "elements":[{"id":"e1","localPath":"media/x.png","zIndex":4}]}
        """.trimIndent()
        val element = decodeMediaProject(text).elements.single()
        assertEquals("e1", element.id)
        assertNull(element.kind)
        assertEquals(1f, element.opacity, 0f)
        assertTrue(element.visible)
        assertEquals(4, element.zIndex)
        assertNull(element.points)
        assertNull(element.text)
    }

    /** The very next schema (a v3 build writing a file) must be refused, not guessed. */
    @Test
    fun futureSchemaVersionRefusedWithVersionText() {
        val text = withRootField(sampleProject(), "schemaVersion", JsonPrimitive(3))
        try {
            decodeMediaProject(text)
            fail("expected UnsupportedVersion")
        } catch (e: MediaProjectException.UnsupportedVersion) {
            assertEquals(3, e.found)
            assertEquals("Формат не поддерживается", e.title)
            assertTrue(e.detail.contains("3"), "detail must name the foreign version")
            assertTrue(e.detail.contains(MEDIA_PROJECT_SCHEMA_VERSION.toString()), "detail must name the known version")
        }
    }

    @Test
    fun farFutureSchemaVersionRefused() {
        val text = withRootField(sampleProject(), "schemaVersion", JsonPrimitive(999))
        try {
            decodeMediaProject(text)
            fail("expected UnsupportedVersion")
        } catch (e: MediaProjectException.UnsupportedVersion) {
            assertEquals(999, e.found)
        }
    }

    @Test
    fun corruptJsonRefused() {
        try {
            decodeMediaProject("это не json")
            fail("expected CorruptFile")
        } catch (e: MediaProjectException) {
            assertIs<MediaProjectException.CorruptFile>(e)
        }
    }

    @Test
    fun missingSchemaVersionRefused() {
        val text = """{"app":"$MEDIA_PROJECT_MARK","name":"x","createdAtMs":1,"updatedAtMs":2}"""
        try {
            decodeMediaProject(text)
            fail("expected CorruptFile")
        } catch (e: MediaProjectException) {
            assertIs<MediaProjectException.CorruptFile>(e)
        }
    }

    @Test
    fun foreignAppMarkRefused() {
        val text = encodeMediaProject(sampleProject()).replace(MEDIA_PROJECT_MARK, "another-app")
        try {
            decodeMediaProject(text)
            fail("expected CorruptFile")
        } catch (e: MediaProjectException) {
            assertIs<MediaProjectException.CorruptFile>(e)
        }
    }

    @Test
    fun unknownFieldsTolerated() {
        val text = withRootField(sampleProject(), "futureField", JsonPrimitive(42))
        assertEquals(sampleProject(), decodeMediaProject(text))
    }

    @Test
    fun minimalFileDecodesWithDefaults() {
        val text = """
            {"schemaVersion":$MEDIA_PROJECT_SCHEMA_VERSION,"app":"$MEDIA_PROJECT_MARK",
             "name":"Пустой","createdAtMs":1,"updatedAtMs":2}
        """.trimIndent()
        val decoded = decodeMediaProject(text)
        assertEquals("Пустой", decoded.name)
        assertTrue(decoded.elements.isEmpty())
        assertTrue(decoded.sequence.clips.isEmpty())
        assertEquals("Image", decoded.settings.mode)
    }

    /** Element files without the block-4 aspect field decode with null, not with a failure. */
    @Test
    fun elementWithoutAspectRatioDecodesNull() {
        val text = """
            {"schemaVersion":1,"app":"$MEDIA_PROJECT_MARK",
             "name":"Пустой","createdAtMs":1,"updatedAtMs":2,
             "elements":[{"id":"e1","localPath":"media/x.mp4","isVideo":true}]}
        """.trimIndent()
        val decoded = decodeMediaProject(text)
        assertNull(decoded.elements.single().aspectRatio)
    }

    @Test
    fun unknownTransitionNameFallsBackToDefault() {
        assertEquals(SEQUENCE_DEFAULT_TRANSITION, sequenceTransitionFromProject("Hologram"))
        assertEquals(
            SequenceTransition.Wipe,
            sequenceTransitionFromProject(SequenceTransition.Wipe.name),
        )
    }

    @Test
    fun projectSequenceRoundTripsTypedTransitions() {
        val typed = listOf(SequenceTransition.Cut, SequenceTransition.Wipe)
        val clips = listOf(
            MediaProjectSequenceClip("a", "media/x.png", false, 3000L),
            MediaProjectSequenceClip("b", "media/y.mp4", true, 5000L),
            MediaProjectSequenceClip("c", "media/z.png", false, 2000L),
        )
        val (restoredClips, restoredTransitions) = projectSequenceToClips(
            MediaProjectSequence(clips = clips, transitions = typed.map { it.name }),
        )
        assertEquals(clips.size, restoredClips.size)
        assertEquals(typed, restoredTransitions)
        assertEquals("media/y.mp4", restoredClips[1].relPath)
    }
}
