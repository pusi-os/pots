package com.pusios.pots.media

/** iOS has no local video transcoder in the app; the editor never shows. */
private object UnsupportedVideoProcessor : VideoProcessor {
    override suspend fun isAvailable(): Boolean = false

    override suspend fun probe(inputRelPath: String): VideoFileInfo = throw VideoEditException.UnsupportedPlatform()

    override suspend fun grabFramePng(inputRelPath: String, atMs: Long): ByteArray? = null

    override suspend fun trim(
        inputRelPath: String,
        startMs: Long,
        endMs: Long,
        info: VideoFileInfo,
        onProgress: VideoEditProgress,
    ): String = throw VideoEditException.UnsupportedPlatform()

    override suspend fun changeSpeed(
        inputRelPath: String,
        factor: Float,
        info: VideoFileInfo,
        onProgress: VideoEditProgress,
    ): String = throw VideoEditException.UnsupportedPlatform()

    override suspend fun removeAudio(
        inputRelPath: String,
        info: VideoFileInfo,
        onProgress: VideoEditProgress,
    ): String = throw VideoEditException.UnsupportedPlatform()

    override suspend fun replaceAudio(
        inputRelPath: String,
        audioRelPath: String,
        info: VideoFileInfo,
        onProgress: VideoEditProgress,
    ): String = throw VideoEditException.UnsupportedPlatform()

    override suspend fun assembleSequence(request: SequenceAssemblyRequest, onProgress: VideoEditProgress): String = throw VideoEditException.UnsupportedPlatform()

    override suspend fun assembleAudioClip(request: AudioClipAssemblyRequest, analysis: AudioAnalysis, onProgress: VideoEditProgress): String = throw VideoEditException.UnsupportedPlatform()
}

actual fun createVideoProcessor(): VideoProcessor = UnsupportedVideoProcessor

actual fun videoEditingPlatformSupported(): Boolean = false

/**
 * No frame probe on iOS in v1 (AVFoundation interop is not wired): canvas
 * video elements fall back to the default 16:9 box with Fit inside — an
 * honest degradation, never a crop.
 */
actual suspend fun probeVideoAspect(source: String): Float? = null

/** No still capture on iOS in v1: the pencil stays image-only (honest degradation). */
actual suspend fun captureVideoFrame(source: String, atMs: Long): ByteArray? = null
