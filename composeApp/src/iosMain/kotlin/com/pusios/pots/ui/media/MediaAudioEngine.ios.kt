package com.pusios.pots.ui.media

/** No embedded audio player yet (video has the same stub); UI degrades gracefully. */
actual fun createMediaAudioEngine(): MediaAudioEngine = NoopMediaAudioEngine()

internal class NoopMediaAudioEngine : MediaAudioEngine {
    override val isSupported: Boolean = false
    override val durationMs: Long = 0L
    override val positionMs: Long = 0L
    override val isPlaying: Boolean = false

    override fun load(bytes: ByteArray) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seek(positionMs: Long) = Unit
    override fun release() = Unit
}
