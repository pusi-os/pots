package com.pusios.pots.ui.media

import androidx.compose.runtime.Immutable
import com.pusios.pots.media.AudioFlow
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
 * transition per gap. Lives in memory — the §12 project autosave is a
 * later version, the same honesty as the other editors.
 */
@Immutable
data class MediaSequenceState(
    val clips: List<SequenceClip> = emptyList(),
    val transitions: List<SequenceTransition> = emptyList(),
)
