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

    /**
     * Extended transport controls. Engines without them keep the defaults
     * (no-ops, [supportsExtendedControls] false) and the UI hides the widgets.
     */
    val supportsExtendedControls: Boolean get() = false

    /** Playback rate multiplier (0.25–4); pitch follows speed. */
    fun setSpeed(factor: Float) {}

    /** Linear gain 0..1. */
    fun setVolume(volume: Float) {}

    /** Restart from the beginning when the track ends. */
    fun setLooping(loop: Boolean) {}
}

expect fun createMediaAudioEngine(): MediaAudioEngine
