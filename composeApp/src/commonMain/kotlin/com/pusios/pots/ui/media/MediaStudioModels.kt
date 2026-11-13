package com.pusios.pots.ui.media

import androidx.compose.runtime.Immutable

/** Studio generation mode; drives chips, the model sheet and the dock. */
enum class MediaMode {
    Image,
    Video,
    Audio,
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
