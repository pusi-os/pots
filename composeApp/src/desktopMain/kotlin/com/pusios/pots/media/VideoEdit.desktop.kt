package com.pusios.pots.media

import com.pusios.pots.getAppFilesDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Desktop actual: drives the system `ffmpeg`/`ffprobe` binaries through
 * `ProcessBuilder`. Choice of tooling (system binary, not a library wrapper):
 * zero new dependencies — wrapper libraries bundle 100–300 MB of native
 * codecs per OS/arch, while the binary is already present on the Linux
 * desktop this app targets; absence is detected at runtime and reported as
 * the §8 card with install steps. See docs/features/media-studio.md.
 */

private val FFMPEG_CANDIDATES =
    listOf("ffmpeg", "/usr/bin/ffmpeg", "/usr/sbin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg")
private val FFPROBE_CANDIDATES =
    listOf("ffprobe", "/usr/bin/ffprobe", "/usr/sbin/ffprobe", "/usr/local/bin/ffprobe", "/opt/homebrew/bin/ffprobe")

private val ffmpegCache = AtomicReference<String?>(null)
private val ffprobeCache = AtomicReference<String?>(null)

private fun binaryWorks(path: String): Boolean = try {
    val process = ProcessBuilder(path, "-version").redirectErrorStream(true).start()
    process.inputStream.readBytes()
    process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
} catch (e: IOException) {
    false
}

private fun resolveBinary(candidates: List<String>): String? = candidates.firstOrNull { binaryWorks(it) }

/**
 * Caches only a positive result. A miss leaves the cache empty, so every
 * later request re-probes — the §8 card's «Проверить снова» picks up a
 * binary installed while the app is already running.
 */
internal fun probeBinaryCached(cache: AtomicReference<String?>, resolve: () -> String?): String? {
    cache.get()?.let { return it }
    val found = resolve()
    if (found != null) cache.set(found)
    return found
}

private fun findFfmpeg(): String? = probeBinaryCached(ffmpegCache) { resolveBinary(FFMPEG_CANDIDATES) }

private fun findFfprobe(): String? = probeBinaryCached(ffprobeCache) { resolveBinary(FFPROBE_CANDIDATES) }

/** Swaps the plain builder's first token for the resolved absolute binary. */
private fun withBinary(args: List<String>, binary: String): List<String> = listOf(binary) + args.drop(1)

private fun absoluteStoragePath(relativePath: String): File = File(getAppFilesDirectory(), relativePath)

private class DesktopVideoProcessor : VideoProcessor {

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        findFfmpeg() != null && findFfprobe() != null
    }

    override suspend fun probe(inputRelPath: String): VideoFileInfo = withContext(Dispatchers.IO) {
        val input = absoluteStoragePath(inputRelPath)
        if (!input.isFile) throw VideoEditException.SourceMissing()
        val ffprobe = findFfprobe() ?: throw VideoEditException.ToolMissing()

        val durationText = capture(ffprobe, ffmpegProbeDurationArgs(input.path))
        val durationSeconds = durationText.trim().toDoubleOrNull()
            ?: throw VideoEditException.ProbeFailed()
        val audioStreams = capture(ffprobe, ffmpegProbeAudioArgs(input.path))
        VideoFileInfo(
            durationMs = (durationSeconds * 1000).toLong().coerceAtLeast(0L),
            hasAudio = audioStreams.isNotBlank(),
        )
    }

    override suspend fun grabFramePng(inputRelPath: String, atMs: Long): ByteArray? = withContext(Dispatchers.IO) {
        val ffmpeg = findFfmpeg() ?: return@withContext null
        val input = absoluteStoragePath(inputRelPath)
        if (!input.isFile) return@withContext null
        runCatching {
            val process = ProcessBuilder(withBinary(ffmpegFrameArgs(input.path, atMs), ffmpeg))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val bytes = process.inputStream.readBytes()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0 && bytes.isNotEmpty()) bytes else null
        }.getOrNull()
    }

    override suspend fun trim(
        inputRelPath: String,
        startMs: Long,
        endMs: Long,
        info: VideoFileInfo,
        onProgress: VideoEditProgress,
    ): String = render(inputRelPath, expectedDurationMs = trimmedDurationMs(startMs, endMs), onProgress = onProgress) { input, output ->
        ffmpegTrimArgs(input, output, startMs, endMs, info.hasAudio)
    }

    override suspend fun changeSpeed(
        inputRelPath: String,
        factor: Float,
        info: VideoFileInfo,
        onProgress: VideoEditProgress,
    ): String = render(inputRelPath, expectedDurationMs = speedDurationMs(info.durationMs, factor), onProgress = onProgress) { input, output ->
        ffmpegSpeedArgs(input, output, factor, info.hasAudio)
    }

    override suspend fun removeAudio(
        inputRelPath: String,
        info: VideoFileInfo,
        onProgress: VideoEditProgress,
    ): String = render(inputRelPath, expectedDurationMs = info.durationMs, onProgress = onProgress) { input, output ->
        ffmpegRemoveAudioArgs(input, output)
    }

    override suspend fun replaceAudio(
        inputRelPath: String,
        audioRelPath: String,
        info: VideoFileInfo,
        onProgress: VideoEditProgress,
    ): String {
        val audio = absoluteStoragePath(audioRelPath)
        if (!audio.isFile) throw VideoEditException.SourceMissing()
        return render(inputRelPath, expectedDurationMs = info.durationMs, onProgress = onProgress) { input, output ->
            ffmpegReplaceAudioArgs(input, audio.path, output, info.durationMs)
        }
    }

    /**
     * Runs one render command: progress lines stream through [onProgress]
     * (false cancels by destroying the process), stderr lands in a temp file
     * whose tail feeds the §8 card. Returns the new file's relative path.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun render(
        inputRelPath: String,
        expectedDurationMs: Long,
        onProgress: VideoEditProgress,
        buildArgs: (inputAbs: String, outputAbs: String) -> List<String>,
    ): String = withContext(Dispatchers.IO) {
        val ffmpeg = findFfmpeg() ?: throw VideoEditException.ToolMissing()

        val outputRel = "media/edits/${Uuid.random()}.mp4"
        val output = absoluteStoragePath(outputRel)
        output.parentFile?.mkdirs()
        val input = absoluteStoragePath(inputRelPath)
        if (!input.isFile) throw VideoEditException.SourceMissing()
        val args = withBinary(buildArgs(input.path, output.path), ffmpeg)

        executeFfmpeg(args, expectedDurationMs, onProgress, output)
        MediaLog.d("Video edit rendered: $outputRel (${output.length()} bytes)")
        outputRel
    }

    override suspend fun assembleSequence(request: SequenceAssemblyRequest, onProgress: VideoEditProgress): String = withContext(Dispatchers.IO) {
        val ffmpeg = findFfmpeg() ?: throw VideoEditException.ToolMissing()
        if (request.clips.isEmpty()) throw VideoEditException.EmptySequence()

        val inputs = request.clips.map { clip ->
            val file = absoluteStoragePath(clip.relPath)
            if (!file.isFile) throw VideoEditException.SourceMissing()
            SequenceInput(file.path, clip.isVideo, clip.durationMs)
        }
        val audioFile = request.audioRelPath?.let { rel ->
            val file = absoluteStoragePath(rel)
            if (!file.isFile) throw VideoEditException.SourceMissing()
            file
        }

        val junctions = resolveSequenceJunctions(request.clips, request.transitions)
        val totalMs = sequenceDurationMs(request.clips, request.transitions)
        val cues = request.subtitleText?.let { splitSubtitleCues(it, totalMs) }.orEmpty()
        val srtFile = if (cues.isNotEmpty()) File.createTempFile("pots-sequence", ".srt") else null
        srtFile?.writeText(subtitleCuesToSrt(cues))

        @OptIn(ExperimentalUuidApi::class)
        val outputRel = "media/edits/${Uuid.random()}.mp4"
        val output = absoluteStoragePath(outputRel)
        output.parentFile?.mkdirs()

        val spec = SequenceRenderSpec(
            inputs = inputs,
            junctions = junctions,
            totalDurationMs = totalMs,
            width = request.width,
            height = request.height,
            fps = request.fps,
            audioPath = audioFile?.path,
            srtPath = srtFile?.path,
        )
        try {
            executeFfmpeg(withBinary(ffmpegSequenceArgs(spec, output.path), ffmpeg), totalMs, onProgress, output)
            MediaLog.d("Sequence assembled: $outputRel (${output.length()} bytes, ${request.clips.size} clips)")
            outputRel
        } finally {
            srtFile?.delete()
        }
    }

    /**
     * Audio-reactive clip (spec §11): plans scenes over the track's energy
     * peaks, builds the reactive filtergraph (bass zoom pulses, treble
     * flashes, warm tint, Cinema vignette) and renders it. With no visual
     * sources a deterministic gradient stands in — the clip is always
     * possible. The background/vignette PNGs are temporary; the sources are
     * never touched.
     */
    override suspend fun assembleAudioClip(request: AudioClipAssemblyRequest, analysis: AudioAnalysis, onProgress: VideoEditProgress): String = withContext(Dispatchers.IO) {
        val ffmpeg = findFfmpeg() ?: throw VideoEditException.ToolMissing()
        val track = absoluteStoragePath(request.trackRelPath)
        if (!track.isFile) throw VideoEditException.SourceMissing()

        val plan = planAudioClip(request, analysis)
        val tempFiles = ArrayList<File>()
        try {
            val backgroundSeed = track.inputStream().use { it.readNBytes(4096) }
            val inputs = plan.spans.map { span ->
                val spanMs = span.endMs - span.startMs
                if (span.sourceIndex < 0) {
                    val background = File.createTempFile("pots-clip-bg", ".png")
                    background.writeBytes(encodeGridToPng(audioClipBackgroundGrid(request.width, request.height, backgroundSeed)))
                    tempFiles.add(background)
                    SequenceInput(background.path, isVideo = false, durationMs = spanMs)
                } else {
                    val source = request.scenes[span.sourceIndex]
                    val file = absoluteStoragePath(source.relPath)
                    if (!file.isFile) throw VideoEditException.SourceMissing()
                    SequenceInput(file.path, source.isVideo, durationMs = spanMs)
                }
            }
            val vignette = if (plan.needsVignette) {
                File.createTempFile("pots-clip-vignette", ".png").also { file ->
                    file.writeBytes(encodeGridToPng(audioClipVignetteGrid(request.width, request.height)))
                    tempFiles.add(file)
                }
            } else {
                null
            }

            val spec = AudioClipRenderSpec(
                inputs = inputs,
                junctions = plan.junctions,
                visibleDurationMs = plan.visibleDurationMs,
                renderDurationMs = plan.renderDurationMs,
                width = request.width,
                height = request.height,
                fps = request.fps,
                audioPath = track.path,
                zoomExprs = plan.sceneZoomExprs,
                flashes = plan.flashWindows,
                vignettePath = vignette?.path,
            )

            @OptIn(ExperimentalUuidApi::class)
            val outputRel = "media/edits/${Uuid.random()}.mp4"
            val output = absoluteStoragePath(outputRel)
            output.parentFile?.mkdirs()
            executeFfmpeg(withBinary(ffmpegAudioClipArgs(spec, output.path), ffmpeg), plan.renderDurationMs, onProgress, output)
            MediaLog.d("Audio clip assembled: $outputRel (${output.length()} bytes, style=${request.style}, ${plan.spans.size} scenes)")
            outputRel
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    /**
     * Runs one prepared ffmpeg command: `-progress pipe:1` lines stream
     * through [onProgress] (false cancels by destroying the process), stderr
     * lands in a temp file whose tail feeds the §8 card. Verifies the output
     * file exists and is non-empty.
     */
    private fun executeFfmpeg(args: List<String>, expectedDurationMs: Long, onProgress: VideoEditProgress, output: File) {
        val stderrFile = File.createTempFile("pots-video-edit", ".stderr")
        val process = ProcessBuilder(args).redirectError(stderrFile).start()
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val fraction = videoProgressFraction(line, expectedDurationMs)
                    if (fraction != null && !onProgress(fraction)) {
                        process.destroy()
                        throw VideoEditException.Cancelled()
                    }
                }
            }
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            if (!finished) {
                process.destroy()
                throw VideoEditException.ExportFailed("обработка заняла слишком много времени")
            }
            if (process.exitValue() != 0) {
                throw VideoEditException.ExportFailed(stderrTail(stderrFile))
            }
            if (!output.isFile || output.length() == 0L) {
                throw VideoEditException.ExportFailed("итоговый файл не появился")
            }
        } catch (e: CancellationException) {
            process.destroy()
            throw e
        } finally {
            stderrFile.delete()
        }
    }

    private fun capture(binary: String, argsTemplate: List<String>): String {
        val process = ProcessBuilder(withBinary(argsTemplate, binary))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val text = process.inputStream.readBytes().decodeToString()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished || process.exitValue() != 0) throw VideoEditException.ProbeFailed()
        return text
    }

    private fun stderrTail(file: File): String {
        val text = runCatching { file.readText() }.getOrDefault("")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "ffmpeg завершился с ошибкой"
        return trimmed.lineSequence().lastOrNull { it.isNotBlank() } ?: "ffmpeg завершился с ошибкой"
    }
}

actual fun createVideoProcessor(): VideoProcessor = DesktopVideoProcessor()

actual fun videoEditingPlatformSupported(): Boolean = true
