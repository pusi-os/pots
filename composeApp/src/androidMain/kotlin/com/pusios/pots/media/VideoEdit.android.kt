package com.pusios.pots.media

import android.media.MediaMetadataRetriever
import com.pusios.pots.getAppFilesDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android has no local video transcoder in the app: the editor chip is
 * never shown ([videoEditingPlatformSupported] = false), and the processor
 * refuses every operation with the §8 card should something still call it.
 */
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
 * Android actual: MediaMetadataRetriever reads the track's dimensions and
 * rotation without ffmpeg. An unreadable source degrades to null — the
 * canvas falls back to its default box instead of failing.
 */
actual suspend fun probeVideoAspect(source: String): Float? = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        if (source.contains("://")) {
            retriever.setDataSource(source, emptyMap())
        } else {
            retriever.setDataSource(File(getAppFilesDirectory(), source).absolutePath)
        }
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        when {
            width <= 0 || height <= 0 -> null

            // Portrait phone videos store landscape frames plus a rotation.
            rotation % 180 != 0 -> height.toFloat() / width

            else -> width.toFloat() / height
        }
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** No local ffmpeg path on Android: no still capture, the pencil stays image-only (honest degradation). */
actual suspend fun captureVideoFrame(source: String, atMs: Long): ByteArray? = null
