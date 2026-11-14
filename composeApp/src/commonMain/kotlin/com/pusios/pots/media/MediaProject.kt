package com.pusios.pots.media

import com.pusios.pots.deleteAppStorageFolder
import com.pusios.pots.listAppStorageFiles
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.writeAppStorageFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Media studio projects (spec §12): the whole studio state — canvas elements
 * with transforms, audio tracks and edit results, the video assembly
 * sequence, parameters — serializes into one `.potsproj` JSON file.
 *
 * The format is versioned via [schemaVersion]; a file from a newer schema is
 * refused with the §8 «Формат не поддерживается» card instead of a partial
 * read. There is no cloud sync in v1: local storage and files only.
 */
const val MEDIA_PROJECT_SCHEMA_VERSION = 1
const val MEDIA_PROJECT_MARK = "pots-media-studio"
const val MEDIA_PROJECT_EXTENSION = "potsproj"

/** One canvas element with its transform (position, scale, rotation, layer). */
@Serializable
data class MediaProjectElement(
    val id: String,
    /** Generation journal link; null for imports and edit results. */
    val jobId: String? = null,
    /** Remote signed URL (5-day lifetime); kept as a fallback for re-download. */
    val url: String? = null,
    /** Local copy under the app storage — survives the URL expiry. */
    val localPath: String? = null,
    val isVideo: Boolean = false,
    val isAudio: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val zIndex: Int = 0,
)

/** One clip of the saved assembly sequence (a canvas element resolved to its file). */
@Serializable
data class MediaProjectSequenceClip(
    val elementId: String,
    val relPath: String,
    val isVideo: Boolean = false,
    val durationMs: Long,
)

/**
 * The saved assembly sequence. Transitions are stored by [SequenceTransition]
 * name; unknown names fall back to the default on import, so a future
 * transition never breaks an old reader.
 */
@Serializable
data class MediaProjectSequence(
    val clips: List<MediaProjectSequenceClip> = emptyList(),
    val transitions: List<String> = emptyList(),
)

/**
 * Studio parameters: the active mode and audio flow, the selected models per
 * mode/flow, the current parameter chips and prompt drafts per mode+flow.
 * Mode/flow are stored by enum name with tolerant fallbacks on import.
 */
@Serializable
data class MediaProjectSettings(
    val mode: String = "Image",
    val audioFlow: String = "tts",
    val imageModelId: String = "",
    val videoModelId: String = "",
    val ttsModelId: String = "",
    val musicModelId: String = "",
    val params: Map<String, String> = emptyMap(),
    val drafts: Map<String, String> = emptyMap(),
)

/** The `.potsproj` payload. [schemaVersion] and [app] are checked before anything else. */
@Serializable
data class MediaProjectFile(
    val schemaVersion: Int,
    val app: String = MEDIA_PROJECT_MARK,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val elements: List<MediaProjectElement> = emptyList(),
    val sequence: MediaProjectSequence = MediaProjectSequence(),
    val settings: MediaProjectSettings = MediaProjectSettings(),
)

/**
 * Typed project import failures in the §8 shape (title + human detail), so
 * the UI shows the unified error card for a corrupt or foreign file.
 */
sealed class MediaProjectException(val title: String, val detail: String) : Exception("$title. $detail") {

    class CorruptFile :
        MediaProjectException(
            "Файл повреждён",
            "Это не файл проекта медиа-студии, или он записан с ошибкой.",
        )

    class UnsupportedVersion(val found: Int) :
        MediaProjectException(
            "Формат не поддерживается",
            "Проект создан версией $found схемы, а эта сборка читает версию $MEDIA_PROJECT_SCHEMA_VERSION. Обновите приложение.",
        )
}

private val projectJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/** Serializes a project to its `.potsproj` JSON text. */
fun encodeMediaProject(project: MediaProjectFile): String = projectJson.encodeToString(project)

/**
 * Parses a `.potsproj` text. Validates the schema version and the app mark
 * before decoding anything else; unknown future versions and broken files
 * throw the typed [MediaProjectException] instead of returning partial state.
 */
fun decodeMediaProject(text: String): MediaProjectFile {
    val root = try {
        projectJson.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        throw MediaProjectException.CorruptFile()
    }
    val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        ?: throw MediaProjectException.CorruptFile()
    if (version > MEDIA_PROJECT_SCHEMA_VERSION) throw MediaProjectException.UnsupportedVersion(version)
    val mark = (root["app"] as? JsonPrimitive)?.contentOrNull
    if (mark != MEDIA_PROJECT_MARK) throw MediaProjectException.CorruptFile()
    return try {
        projectJson.decodeFromString<MediaProjectFile>(text)
    } catch (_: Exception) {
        throw MediaProjectException.CorruptFile()
    }
}

/** Tolerant transition restore: an unknown name degrades to the safe default. */
fun sequenceTransitionFromProject(raw: String): SequenceTransition = SequenceTransition.entries.firstOrNull { it.name == raw } ?: SEQUENCE_DEFAULT_TRANSITION

/** Restores the typed sequence; transition gaps fill with the default. */
fun projectSequenceToClips(sequence: MediaProjectSequence): Pair<List<SequenceClip>, List<SequenceTransition>> {
    val clips = sequence.clips.map { clip ->
        SequenceClip(elementId = clip.elementId, relPath = clip.relPath, isVideo = clip.isVideo, durationMs = clip.durationMs)
    }
    val transitions = List((clips.size - 1).coerceAtLeast(0)) { index ->
        sequence.transitions.getOrNull(index)?.let(::sequenceTransitionFromProject) ?: SEQUENCE_DEFAULT_TRANSITION
    }
    return clips to transitions
}

/** One entry of the project list: the file id plus the fields the sheet shows. */
data class MediaProjectMeta(
    val id: String,
    val name: String,
    val updatedAtMs: Long,
    val elementCount: Int,
)

/**
 * Projects under `media/projects/` in app storage. The active snapshot
 * ([ACTIVE_ID]) is the autosave target; named projects sit next to it. The
 * storage primitives are injectable so the logic is unit-testable without
 * the platform filesystem.
 */
class MediaProjectStore(
    private val readBytes: (String) -> ByteArray? = ::readAppStorageBytes,
    private val writeText: (String, String) -> Boolean = ::writeAppStorageFile,
    private val listPaths: (String) -> List<String> = ::listAppStorageFiles,
    private val deletePath: (String) -> Boolean = ::deleteAppStorageFolder,
) {

    /** Saves under `media/projects/<id>.potsproj`; refuses path-unsafe ids. */
    fun save(id: String, project: MediaProjectFile): Boolean {
        if (!isValidId(id)) return false
        return writeText("$DIR/$id.$MEDIA_PROJECT_EXTENSION", encodeMediaProject(project))
    }

    /** Loads one project; null when missing or unreadable (a corrupt file never throws). */
    fun load(id: String): MediaProjectFile? {
        if (!isValidId(id)) return null
        val text = readBytes("$DIR/$id.$MEDIA_PROJECT_EXTENSION")?.decodeToString() ?: return null
        return runCatching { decodeMediaProject(text) }.getOrNull()
    }

    /** Named projects, newest first; the active snapshot and corrupt files stay out. */
    fun list(): List<MediaProjectMeta> = listPaths(DIR)
        .filter { it.endsWith(".$MEDIA_PROJECT_EXTENSION") }
        .mapNotNull { path ->
            val id = path.substringAfterLast('/').removeSuffix(".$MEDIA_PROJECT_EXTENSION")
            if (id == ACTIVE_ID || !isValidId(id)) return@mapNotNull null
            load(id)?.let { project ->
                MediaProjectMeta(id = id, name = project.name, updatedAtMs = project.updatedAtMs, elementCount = project.elements.size)
            }
        }
        .sortedByDescending { it.updatedAtMs }

    fun delete(id: String): Boolean {
        if (!isValidId(id)) return false
        return deletePath("$DIR/$id.$MEDIA_PROJECT_EXTENSION")
    }

    private fun isValidId(id: String): Boolean = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        const val DIR = "media/projects"

        /** The autosave target; never shown in the project list. */
        const val ACTIVE_ID = "active"
    }
}
