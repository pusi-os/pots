package com.pusios.pots.ui.media

/**
 * Minimal in-app audio playback: play, pause, seek, position/duration.
 * Desktop (the primary target) decodes MP3 via JLayer and WAV via javax.sound;
 * Android uses [android.media.MediaPlayer]; other platforms are safe no-ops
 * reporting [isSupported] = false.
 *
 * Bytes, not paths: the studio already caches results in app storage and the
 * common side resolves them before calling [load].
 */
interface MediaAudioEngine {
    /** False on platforms without an embedded player (UI degrades to a static card). */
    val isSupported: Boolean
    val durationMs: Long
    val positionMs: Long
    val isPlaying: Boolean

    /** Parses the container (MP3/WAV) and computes duration. Idempotent per instance. */
    fun load(bytes: ByteArray)

    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun release()
}

expect fun createMediaAudioEngine(): MediaAudioEngine
