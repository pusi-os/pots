package com.pusios.pots.ui.media

import android.media.MediaPlayer
import java.io.File
import java.io.FileOutputStream

/**
 * Android audio engine: [MediaPlayer] over a temp file copy of the bytes
 * (MediaPlayer needs a path or fd, not raw memory).
 */
actual fun createMediaAudioEngine(): MediaAudioEngine = AndroidMediaAudioEngine()

internal class AndroidMediaAudioEngine : MediaAudioEngine {

    override val isSupported: Boolean = true
    override val supportsExtendedControls: Boolean = true

    private var player: MediaPlayer? = null
    private var tempFile: File? = null
    private var duration = 0L
    private var speed = 1f
    private var volume = 1f

    override val durationMs: Long get() = duration
    override val positionMs: Long get() = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    override val isPlaying: Boolean get() = runCatching { player?.isPlaying ?: false }.getOrDefault(false)

    override fun load(bytes: ByteArray) {
        release()
        val file = File.createTempFile("pots-audio", ".bin")
        FileOutputStream(file).use { it.write(bytes) }
        tempFile = file
        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setVolume(volume, volume)
        }
        player = mp
        duration = mp.duration.toLong()
    }

    override fun play() {
        runCatching {
            val mp = player ?: return
            if (!mp.isPlaying) mp.start()
            applySpeed(mp)
        }
    }

    override fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    override fun seek(positionMs: Long) {
        runCatching { player?.seekTo(positionMs.coerceIn(0L, duration).toInt()) }
    }

    override fun setSpeed(factor: Float) {
        speed = factor.coerceIn(0.25f, 4f)
        // Applying PlaybackParams to a never-started player can auto-start it on
        // some devices; play() applies the rate right after start instead.
        player?.takeIf { runCatching { it.isPlaying }.getOrDefault(false) }?.let(::applySpeed)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        runCatching { player?.setVolume(this.volume, this.volume) }
    }

    override fun setLooping(loop: Boolean) {
        runCatching { player?.isLooping = loop }
    }

    private fun applySpeed(mp: MediaPlayer) {
        if (speed == 1f) return
        runCatching {
            mp.playbackParams = android.media.PlaybackParams().setSpeed(speed)
        }
    }

    override fun release() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        tempFile?.delete()
        tempFile = null
        duration = 0L
    }
}
