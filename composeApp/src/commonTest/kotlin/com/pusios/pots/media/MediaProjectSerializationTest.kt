package com.pusios.pots.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Media studio project file (spec §12): one `.potsproj` JSON with a schema
 * version. The codec must round-trip the full studio state (canvas elements
 * with transforms, the assembly sequence, parameters) and refuse unknown or
 * corrupt files with the typed §8 errors.
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
                jobId = "job-9",
                url = "https://example.com/cat.png",
                localPath = "media/jobs/job-9.png",
                isVideo = false,
                isAudio = false,
                x = 12.5f,
                y = -40f,
                scale = 1.25f,
                rotation = 90f,
                zIndex = 3,
            ),
            MediaProjectElement(
                id = "el-2",
                localPath = "media/edits/abc.wav",
                isAudio = true,
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

    @Test
    fun roundTripKeepsFullState() {
        val project = sampleProject()
        val decoded = decodeMediaProject(encodeMediaProject(project))
        assertEquals(project, decoded)
    }

    @Test
    fun encodedJsonCarriesSchemaVersionAndMark() {
        val root = Json.parseToJsonElement(encodeMediaProject(sampleProject())).jsonObject
        assertEquals(MEDIA_PROJECT_SCHEMA_VERSION.toString(), root["schemaVersion"]!!.jsonPrimitive.content)
        assertEquals(MEDIA_PROJECT_MARK, root["app"]!!.jsonPrimitive.content)
    }

    @Test
    fun futureSchemaVersionRefusedWithVersionText() {
        val text = encodeMediaProject(sampleProject())
            .replace("\"schemaVersion\": $MEDIA_PROJECT_SCHEMA_VERSION", "\"schemaVersion\": 999")
        try {
            decodeMediaProject(text)
            fail("expected UnsupportedVersion")
        } catch (e: MediaProjectException.UnsupportedVersion) {
            assertEquals(999, e.found)
            assertEquals("Формат не поддерживается", e.title)
            assertTrue(e.detail.contains("999"), "detail must name the foreign version")
            assertTrue(e.detail.contains(MEDIA_PROJECT_SCHEMA_VERSION.toString()), "detail must name the known version")
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
        val text = encodeMediaProject(sampleProject())
            .replace("\"elements\": [", "\"futureField\": 42, \"elements\": [")
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
