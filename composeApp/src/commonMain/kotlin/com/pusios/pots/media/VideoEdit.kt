package com.pusios.pots.media

/**
 * Video editing core: pure command building and timecode math for local
 * desktop processing with the system `ffmpeg`/`ffprobe` binaries. Video
 * codecs are far beyond deterministic sample/pixel math, so the app shells
 * out instead of decoding by hand; everything testable (argument layout,
 * trim/speed timecodes, progress parsing, §8 errors) still lives here as
 * common code. Running the processes is the only platform-dependent step
 * ([createVideoProcessor]).
 *
 * Output of every operation is a NEW `.mp4` under `media/edits/`; the
 * source element is never touched.
 *
 * The progress callback reports 0..1; returning false cancels the operation.
 */
typealias VideoEditProgress = (fraction: Float) -> Boolean

/** Probe result the editor needs before offering operations. */
class VideoFileInfo(
    val durationMs: Long,
    val hasAudio: Boolean,
)

/** Speed range mirrors the audio editor (0.25×–4×). */
const val VIDEO_SPEED_MIN = 0.25f
const val VIDEO_SPEED_MAX = 4f

/** Milliseconds as ffmpeg seconds with three decimals (2500 → "2.500"). */
fun formatSecondsForFfmpeg(ms: Long): String {
    require(ms >= 0) { "ms must not be negative" }
    val seconds = ms / 1000L
    val millis = ms % 1000L
    return "$seconds.${millis.toString().padStart(3, '0')}"
}

/**
 * Duration of a trim result. Ranges must be positive: an empty or inverted
 * selection has nothing to cut, exactly like the audio editor.
 */
fun trimmedDurationMs(startMs: Long, endMs: Long): Long {
    if (startMs < 0 || endMs <= startMs) throw VideoEditException.EmptyRange()
    return endMs - startMs
}

/** Duration of a speed result: 2× plays twice as fast (half the frames). */
fun speedDurationMs(durationMs: Long, factor: Float): Long {
    validateSpeedFactor(factor)
    require(durationMs > 0) { "durationMs must be positive" }
    return kotlin.math.round(durationMs / factor).toLong().coerceAtLeast(1L)
}

/** Refuses factors outside 0.25×–4× with the §8 card. */
fun validateSpeedFactor(factor: Float) {
    if (factor.isNaN() || factor < VIDEO_SPEED_MIN || factor > VIDEO_SPEED_MAX) {
        throw VideoEditException.BadSpeed(factor)
    }
}

/**
 * Splits [factor] into cascaded `atempo` stages, each inside ffmpeg's
 * 0.5–2.0 range (4× = atempo=2,atempo=2; 0.3× = atempo=0.5,atempo=0.6).
 */
fun atempoChain(factor: Float): List<Float> {
    validateSpeedFactor(factor)
    val chain = mutableListOf<Float>()
    var rest = factor
    while (rest > 2f + 1e-3f) {
        chain.add(2f)
        rest /= 2f
    }
    while (rest < 0.5f - 1e-3f) {
        chain.add(0.5f)
        rest *= 2f
    }
    // Two decimals keep the filter string readable and the product within
    // half a percent of the requested factor.
    chain.add(kotlin.math.round(rest * 100f) / 100f)
    return chain
}

/** Encode settings shared by every re-encoding command (trim, speed, sequence). */
internal fun videoEncodeFlags(hasAudio: Boolean): List<String> = buildList {
    addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p"))
    if (hasAudio) addAll(listOf("-c:a", "aac", "-b:a", "192k")) else add("-an")
    addAll(listOf("-movflags", "+faststart"))
}

/** Quiet, overwrite-friendly prefix shared by every ffmpeg command. */
internal val FFMPEG_PREFIX = listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostats", "-y")

/**
 * Frame-accurate trim: fast seek before `-i`, re-encode, cut exactly
 * [startMs]..[endMs] via `-t` (output timestamps restart at the seek point).
 */
fun ffmpegTrimArgs(input: String, output: String, startMs: Long, endMs: Long, hasAudio: Boolean): List<String> {
    val duration = trimmedDurationMs(startMs, endMs)
    return FFMPEG_PREFIX + listOf("-progress", "pipe:1") +
        listOf("-ss", formatSecondsForFfmpeg(startMs), "-i", input, "-t", formatSecondsForFfmpeg(duration)) +
        videoEncodeFlags(hasAudio) + output
}

/**
 * Speed change: `setpts=PTS/f` retimes the video, cascaded `atempo` retimes
 * the audio. Pitch follows speed (tape behavior), same honesty as the audio
 * editor. Sources without an audio stream render silent.
 */
fun ffmpegSpeedArgs(input: String, output: String, factor: Float, hasAudio: Boolean): List<String> {
    validateSpeedFactor(factor)
    val setpts = "setpts=PTS/$factor"
    return if (hasAudio) {
        val atempo = atempoChain(factor).joinToString(",") { "atempo=$it" }
        FFMPEG_PREFIX + listOf("-progress", "pipe:1", "-i", input) +
            listOf("-filter_complex", "[0:v]$setpts[v];[0:a]$atempo[a]", "-map", "[v]", "-map", "[a]") +
            videoEncodeFlags(true) + output
    } else {
        FFMPEG_PREFIX + listOf("-progress", "pipe:1", "-i", input) +
            listOf("-vf", setpts, "-an") +
            videoEncodeFlags(false) + output
    }
}

/**
 * Mutes the clip: the video stream is copied bit-for-bit, so the operation
 * finishes in a second or two.
 */
fun ffmpegRemoveAudioArgs(input: String, output: String): List<String> = FFMPEG_PREFIX + listOf("-progress", "pipe:1", "-i", input) +
    listOf("-map", "0:v:0", "-an", "-c:v", "copy", "-movflags", "+faststart") + output

/**
 * Replaces the soundtrack: video copied, the picked track encoded to AAC.
 * A short track is padded with silence (`apad`), a long one cut at the
 * video end (`-t`), so the picture never changes length.
 */
fun ffmpegReplaceAudioArgs(input: String, audioInput: String, output: String, videoDurationMs: Long): List<String> {
    require(videoDurationMs > 0) { "videoDurationMs must be positive" }
    return FFMPEG_PREFIX + listOf("-progress", "pipe:1", "-i", input, "-i", audioInput) +
        listOf("-map", "0:v:0", "-map", "1:a:0", "-af", "apad", "-t", formatSecondsForFfmpeg(videoDurationMs)) +
        listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart") + output
}

/** Asks ffprobe for the container duration in seconds. */
fun ffmpegProbeDurationArgs(input: String): List<String> = listOf("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", input)

/** Asks ffprobe whether at least one audio stream exists. */
fun ffmpegProbeAudioArgs(input: String): List<String> = listOf("ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_type", "-of", "csv=p=0", input)

/** Grabs a single preview frame as PNG on stdout (fast seek before `-i`). */
fun ffmpegFrameArgs(input: String, atMs: Long): List<String> {
    require(atMs >= 0) { "atMs must not be negative" }
    return listOf(
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-ss", formatSecondsForFfmpeg(atMs), "-i", input,
        "-frames:v", "1", "-vf", "scale=320:-2", "-f", "image2", "-c:v", "png", "pipe:1",
    )
}

/**
 * Maps one `-progress pipe:1` line to a fraction of [expectedDurationMs].
 * `out_time_us` (and the misnamed `out_time_ms` twin) carry microseconds;
 * `progress=end` means done. Returns null for every other line.
 */
fun videoProgressFraction(line: String, expectedDurationMs: Long): Float? {
    if (line == "progress=end") return 1f
    val us = when {
        line.startsWith("out_time_us=") -> line.removePrefix("out_time_us=").toLongOrNull()
        line.startsWith("out_time_ms=") -> line.removePrefix("out_time_ms=").toLongOrNull()
        else -> null
    } ?: return null
    if (expectedDurationMs <= 0) return null
    return (us / 1000f / expectedDurationMs).coerceIn(0f, 1f)
}

/**
 * Runs video operations against the local toolchain. Implementations work on
 * app-storage-relative paths and return the relative path of the new `.mp4`.
 * Only the desktop actual processes anything; other targets report
 * [VideoEditException.UnsupportedPlatform].
 */
interface VideoProcessor {
    /** Runtime check that the ffmpeg + ffprobe binaries exist. */
    suspend fun isAvailable(): Boolean

    /** Reads duration and audio presence; throws [VideoEditException]. */
    suspend fun probe(inputRelPath: String): VideoFileInfo

    /** One preview frame as PNG bytes; null when the grab fails. */
    suspend fun grabFramePng(inputRelPath: String, atMs: Long): ByteArray?

    /** Cuts [startMs]..[endMs]; returns the new file's relative path. */
    suspend fun trim(inputRelPath: String, startMs: Long, endMs: Long, info: VideoFileInfo, onProgress: VideoEditProgress): String

    /** Retimes by [factor] (0.25×–4×); returns the new file's relative path. */
    suspend fun changeSpeed(inputRelPath: String, factor: Float, info: VideoFileInfo, onProgress: VideoEditProgress): String

    /** Copies the picture without sound; returns the new file's relative path. */
    suspend fun removeAudio(inputRelPath: String, info: VideoFileInfo, onProgress: VideoEditProgress): String

    /** Swaps the soundtrack for [audioRelPath]; returns the new file's relative path. */
    suspend fun replaceAudio(inputRelPath: String, audioRelPath: String, info: VideoFileInfo, onProgress: VideoEditProgress): String

    /** Assembles the sequence into one clip; returns the new file's relative path. */
    suspend fun assembleSequence(request: SequenceAssemblyRequest, onProgress: VideoEditProgress): String
}

expect fun createVideoProcessor(): VideoProcessor

/**
 * Static platform capability: whether this target can edit video at all.
 * Desktop = true (the ffmpeg processor decides at runtime whether the
 * binaries are really there); Android/iOS/web = false, so the UI never shows
 * a video editor it cannot run — the same honesty as local TTS.
 */
expect fun videoEditingPlatformSupported(): Boolean

/**
 * Editor failures in the §8 shape: a Russian title, one human sentence of
 * detail and a rescue ([fix]) the UI turns into an action button.
 */
sealed class VideoEditException(
    val title: String,
    val detail: String,
    val fix: String,
) : Exception("$title. $detail") {

    class ToolMissing :
        VideoEditException(
            "ffmpeg не найден",
            "Для редактирования видео нужен установленный в системе ffmpeg. " +
                "Установка: Arch Linux — «sudo pacman -S ffmpeg», Debian/Ubuntu — «sudo apt install ffmpeg», " +
                "macOS — «brew install ffmpeg», Windows — «winget install ffmpeg». После установки повторите.",
            "Проверить снова",
        )

    class UnsupportedPlatform :
        VideoEditException(
            "Недоступно на этой платформе",
            "Редактирование видео пока поддерживает только десктопная версия — ей нужен системный ffmpeg.",
            "Закрыть",
        )

    class ProbeFailed :
        VideoEditException(
            "Не удалось прочитать видео",
            "Файл повреждён или записан в неподдерживаемом формате.",
            "Выбрать другой файл",
        )

    class SourceMissing :
        VideoEditException(
            "Исходник не найден",
            "Локальный файл видео ещё не сохранён. Дождитесь загрузки или импортируйте файл заново.",
            "Закрыть",
        )

    class EmptyRange :
        VideoEditException(
            "Пустой диапазон",
            "Сдвиньте ручки начала и конца так, чтобы между ними остался отрезок, — тогда обрезке будет что вырезать.",
            "Изменить выделение",
        )

    class EmptySequence :
        VideoEditException(
            "Пустая последовательность",
            "Добавьте хотя бы один фото- или видео-элемент — тогда сборке будет из чего монтировать ролик.",
            "Добавить элементы",
        )

    class BadSpeed(factor: Float) :
        VideoEditException(
            "Недопустимая скорость",
            "Скорость $factor не поддерживается. Допустимый диапазон — от 0.25× до 4×.",
            "Сбросить параметры",
        )

    class Cancelled :
        VideoEditException(
            "Обработка остановлена",
            "Операция отменена до завершения, файл не изменён.",
            "Закрыть",
        )

    class ExportFailed(reason: String) :
        VideoEditException(
            "Не удалось собрать видео",
            "Рендер остановлен: $reason.",
            "Повторить",
        )
}

/** Snackbar-friendly one-liner: title plus the human detail. */
fun VideoEditException.userMessageRu(): String = "$title $detail"
