package com.pusios.pots.media

import kotlin.math.roundToInt

/**
 * Audio editing core: decoded PCM plus the pure operations the editor uses
 * (trim, speed, concat). Everything here is deterministic common code, so the
 * edit rules are unit-tested without codecs or platforms. Decoding is the only
 * platform-dependent step ([decodeAudioToPcm]); encoding reuses [pcm16ToWav].
 */
class AudioPcm(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(samples.size % channels == 0) { "samples must hold whole frames" }
    }

    val frameCount: Int get() = samples.size / channels
    val durationMs: Long get() = frameCount * 1000L / sampleRate
}

/** Reports progress 0..1; returning false cancels the operation. */
typealias AudioEditProgress = (fraction: Float) -> Boolean

/** Waveform peaks in 0..1, one per bucket, for drawing a real wave. */
fun AudioPcm.peaks(count: Int): FloatArray {
    require(count > 0) { "count must be positive" }
    val out = FloatArray(count)
    if (frameCount == 0) return out
    val framesPerBucket = frameCount.toDouble() / count
    for (bucket in 0 until count) {
        val from = (bucket * framesPerBucket).toInt().coerceAtMost(frameCount)
        val to = ((bucket + 1) * framesPerBucket).toInt().coerceAtMost(frameCount)
        if (to <= from) continue
        var max = 0
        for (frame in from until to) {
            val base = frame * channels
            for (c in 0 until channels) {
                val value = samples[base + c].toInt()
                val magnitude = if (value == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(value)
                if (magnitude > max) max = magnitude
            }
        }
        out[bucket] = max / 32768f
    }
    return out
}

/**
 * Cuts [startMs]..[endMs] out of the track (end exclusive). Clamps to the
 * duration; empty or inverted ranges throw [AudioEditException.EmptyRange].
 */
fun AudioPcm.trimRange(startMs: Long, endMs: Long): AudioPcm {
    if (endMs <= startMs) throw AudioEditException.EmptyRange()
    val startFrame = frameAtMs(startMs.coerceAtLeast(0L))
    val endFrame = frameAtMs(endMs)
    if (endFrame <= startFrame) throw AudioEditException.EmptyRange()
    return AudioPcm(samples.copyOfRange(startFrame * channels, endFrame * channels), sampleRate, channels)
}

/**
 * Resamples by [factor]: 2.0 plays twice as fast (half the frames), 0.5 twice
 * as slow. Pitch follows speed (tape behavior); preserving pitch needs a
 * time-stretcher and is intentionally out of v1.
 */
fun AudioPcm.changeSpeed(factor: Float, onProgress: AudioEditProgress? = null): AudioPcm {
    if (factor.isNaN() || factor <= 0f) throw AudioEditException.BadSpeed(factor)
    if (frameCount == 0) throw AudioEditException.EmptyRange()
    return resampleLinear(factor.toDouble(), onProgress)
}

/**
 * Joins tracks top-to-bottom. Every track is converted to the first track's
 * channel count and sample rate, so mixed sources still produce one file.
 */
fun concatAudio(tracks: List<AudioPcm>, onProgress: AudioEditProgress? = null): AudioPcm {
    if (tracks.isEmpty()) throw AudioEditException.EmptyRange()
    if (tracks.size == 1) return tracks.first()
    val targetRate = tracks.first().sampleRate
    val targetChannels = tracks.first().channels

    // Conversions take 90% of the progress budget, the final copy the rest.
    val prepared = ArrayList<AudioPcm>(tracks.size)
    for ((index, track) in tracks.withIndex()) {
        val converted = track.toFormat(targetChannels, targetRate) { fraction ->
            onProgress?.invoke((index + fraction * 0.9f) / tracks.size) ?: true
        }
        prepared.add(converted)
    }

    val totalSamples = prepared.sumOf { it.samples.size }
    val out = ShortArray(totalSamples)
    var offset = 0
    for ((index, track) in prepared.withIndex()) {
        track.samples.copyInto(out, offset)
        offset += track.samples.size
        val fraction = 0.9f + 0.1f * (index + 1) / tracks.size
        if (onProgress != null && !onProgress(fraction.coerceAtMost(1f))) throw AudioEditException.Cancelled()
    }
    if (onProgress != null && !onProgress(1f)) throw AudioEditException.Cancelled()
    return AudioPcm(out, targetRate, targetChannels)
}

private fun AudioPcm.frameAtMs(ms: Long): Int = (ms * sampleRate / 1000L).toInt().coerceIn(0, frameCount)

/** Linear-interpolation resampler shared by speed change and rate conversion. */
private fun AudioPcm.resampleLinear(factor: Double, onProgress: AudioEditProgress?): AudioPcm {
    val outFrames = (frameCount / factor).roundToInt().coerceAtLeast(1)
    val out = ShortArray(outFrames * channels)
    val lastFrame = frameCount - 1
    var reportedPct = -1
    for (i in 0 until outFrames) {
        val src = i * factor
        val i0 = src.toInt().coerceAtMost(lastFrame)
        val i1 = if (i0 < lastFrame) i0 + 1 else i0
        val frac = (src - i0).toFloat()
        val baseIn = i0 * channels
        val baseNext = i1 * channels
        val baseOut = i * channels
        for (c in 0 until channels) {
            val a = samples[baseIn + c].toInt()
            val b = samples[baseNext + c].toInt()
            out[baseOut + c] = (a + (b - a) * frac).roundToInt().toShort()
        }
        if (onProgress != null) {
            val pct = ((i + 1).toLong() * 100 / outFrames).toInt()
            if (pct != reportedPct) {
                reportedPct = pct
                if (!onProgress(pct / 100f)) throw AudioEditException.Cancelled()
            }
        }
    }
    return AudioPcm(out, sampleRate, channels)
}

/** Converts to [targetChannels] and [targetRate]; progress maps to resampling. */
private fun AudioPcm.toFormat(targetChannels: Int, targetRate: Int, onProgress: AudioEditProgress?): AudioPcm {
    var current = this
    if (current.channels != targetChannels) {
        current = current.toChannels(targetChannels)
    }
    if (current.sampleRate != targetRate) {
        current = current.resampleLinear(current.sampleRate.toDouble() / targetRate, onProgress)
    } else if (onProgress != null) {
        if (!onProgress(1f)) throw AudioEditException.Cancelled()
    }
    return current
}

/** Mono duplicates into every channel; multichannel mixes down to average. */
private fun AudioPcm.toChannels(target: Int): AudioPcm {
    if (target == channels) return this
    val out = ShortArray(frameCount * target)
    for (frame in 0 until frameCount) {
        val base = frame * channels
        when {
            channels == 1 -> {
                val mono = samples[frame]
                for (c in 0 until target) out[frame * target + c] = mono
            }

            target == 1 -> {
                var sum = 0
                for (c in 0 until channels) sum += samples[base + c]
                out[frame] = (sum / channels).toShort()
            }

            else -> for (c in 0 until target) {
                out[frame * target + c] = samples[base + minOf(c, channels - 1)]
            }
        }
    }
    return AudioPcm(out, sampleRate, target)
}

/**
 * Decodes MP3/WAV bytes into PCM. Desktop understands both; other targets
 * degrade to WAV only and throw [AudioEditException.UnsupportedFormat] for
 * everything else. Throws [AudioEditException.DecodeFailed] on broken files.
 */
expect fun decodeAudioToPcm(bytes: ByteArray): AudioPcm

/**
 * Editor failures in the §8 shape: a Russian title, one human sentence of
 * detail and a rescue ([fix]) the UI turns into an action button.
 */
sealed class AudioEditException(
    val title: String,
    val detail: String,
    val fix: String,
) : Exception("$title. $detail") {

    class UnsupportedFormat(formats: String) :
        AudioEditException(
            "Формат не поддерживается",
            "Этот файл нельзя обработать. Поддерживаются: $formats.",
            "Выбрать другой файл",
        )

    class DecodeFailed :
        AudioEditException(
            "Не удалось прочитать аудио",
            "Файл повреждён или записан в неподдерживаемой кодировке.",
            "Выбрать другой файл",
        )

    class EmptyRange :
        AudioEditException(
            "Пустой диапазон",
            "Выделите отрезок на волновой дорожке — тогда обрезке будет что вырезать.",
            "Изменить выделение",
        )

    class BadSpeed(factor: Float) :
        AudioEditException(
            "Недопустимая скорость",
            "Скорость $factor не поддерживается. Допустимый диапазон — от 0.25× до 4×.",
            "Сбросить параметры",
        )

    class Cancelled :
        AudioEditException(
            "Обработка остановлена",
            "Операция отменена до завершения, файл не изменён.",
            "Закрыть",
        )

    class ExportFailed :
        AudioEditException(
            "Не удалось собрать аудио",
            "Файл не сохранился. Проверьте свободное место и повторите.",
            "Повторить экспорт",
        )
}

/** Snackbar-friendly one-liner: title plus the human detail. */
fun AudioEditException.userMessageRu(): String = "$title $detail"
