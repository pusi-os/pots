package com.pusios.pots.ui.media

import androidx.compose.runtime.Immutable
import com.pusios.pots.media.AudioFlow
import com.pusios.pots.media.MediaProjectElement
import com.pusios.pots.media.SequenceClip
import com.pusios.pots.media.SequenceTransition

/** Studio generation mode; drives chips, the model sheet and the dock. */
enum class MediaMode {
    Image,
    Video,
    Audio,
}

/**
 * Draft storage key per mode; audio drafts are separate per flow (spec §13),
 * image/video have no flow dimension. Drafts live in `MediaSettings`, never in
 * transient `remember` state.
 */
internal fun mediaDraftKey(mode: MediaMode, flow: AudioFlow): String = when (mode) {
    MediaMode.Image -> "image"
    MediaMode.Video -> "video"
    MediaMode.Audio -> "audio_" + flow.storageKey
}

/** One placed asset on the Media Studio canvas. */
@Immutable
data class CanvasElement(
    val id: String,
    val jobId: String? = null,
    /** Remote signed URL; opened without a key. */
    val url: String? = null,
    /** Local cached copy (`media/jobs/…`) once downloaded. */
    val localPath: String? = null,
    val isVideo: Boolean = false,
    /** Audio/music result: rendered as a player card instead of an image. */
    val isAudio: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val zIndex: Int = 0,
)

enum class CanvasAction {
    Move,
    Resize,
    Rotate,
    Duplicate,
    Delete,
    BringForward,
    SendBack,
    UseAsReference,
    ClearSelection,
}

/**
 * The assembly sequence (spec §7): clips in playback order plus one
 * transition per gap. Persisted by the §12 project autosave.
 */
@Immutable
data class MediaSequenceState(
    val clips: List<SequenceClip> = emptyList(),
    val transitions: List<SequenceTransition> = emptyList(),
)

/** Project serialization (spec §12): canvas element → project DTO. */
fun CanvasElement.toProjectElement(): MediaProjectElement = MediaProjectElement(
    id = id,
    jobId = jobId,
    url = url,
    localPath = localPath,
    isVideo = isVideo,
    isAudio = isAudio,
    x = x,
    y = y,
    scale = scale,
    rotation = rotation,
    zIndex = zIndex,
)

/** Project import (spec §12): project DTO → canvas element. */
fun MediaProjectElement.toCanvasElement(): CanvasElement = CanvasElement(
    id = id,
    jobId = jobId,
    url = url,
    localPath = localPath,
    isVideo = isVideo,
    isAudio = isAudio,
    x = x,
    y = y,
    scale = scale,
    rotation = rotation,
    zIndex = zIndex,
)

/** Tolerant mode restore from a project file: an unknown name degrades to Image. */
fun mediaModeFromProject(raw: String): MediaMode = MediaMode.entries.firstOrNull { it.name == raw } ?: MediaMode.Image
