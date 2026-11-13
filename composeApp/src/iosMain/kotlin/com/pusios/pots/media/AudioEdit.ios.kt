package com.pusios.pots.media

/**
 * iOS decodes WAV in pure common code; MP3 editing is not wired in v1
 * (playback is not wired here either, so the editor degrades to WAV).
 */
actual fun decodeAudioToPcm(bytes: ByteArray): AudioPcm = wavToPcm(bytes) ?: throw AudioEditException.UnsupportedFormat("WAV")
