package com.pusios.pots.media

/**
 * Android decodes WAV in pure common code; MP3 stays desktop-only in v1
 * (MediaPlayer gives playback but no PCM, so editing degrades honestly).
 */
actual fun decodeAudioToPcm(bytes: ByteArray): AudioPcm = wavToPcm(bytes) ?: throw AudioEditException.UnsupportedFormat("WAV")
