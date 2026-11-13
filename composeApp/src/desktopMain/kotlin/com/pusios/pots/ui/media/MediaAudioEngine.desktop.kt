package com.pusios.pots.ui.media

/**
 * Desktop audio engine: MP3 via JLayer, WAV via javax.sound. One worker thread
 * per engine decodes frames into a [javax.sound.sampled.SourceDataLine];
 * pause stops the writes, seek reopens the stream and skips frames.
 */
actual fun createMediaAudioEngine(): MediaAudioEngine = DesktopMediaAudioEngine()

internal class DesktopMediaAudioEngine : MediaAudioEngine {

    override val isSupported: Boolean = true
    override val supportsExtendedControls: Boolean = true

    private var source: ByteArray = ByteArray(0)
    private var isMp3 = false

    @Volatile private var duration = 0L

    @Volatile private var position = 0L

    @Volatile private var playing = false

    @Volatile private var released = false

    @Volatile private var pauseRequested = false

    @Volatile private var seekTargetMs = -1L

    @Volatile private var speed = 1f

    @Volatile private var volume = 1f

    @Volatile private var looping = false

    /** Set when [speed] changes mid-playback; the loop reopens at the position. */
    @Volatile private var speedChangePending = false

    private var worker: Thread? = null
    private var line: javax.sound.sampled.SourceDataLine? = null
    private var lineRate = 0

    override val durationMs: Long get() = duration
    override val positionMs: Long get() = position
    override val isPlaying: Boolean get() = playing

    override fun load(bytes: ByteArray) {
        release()
        released = false
        source = bytes
        isMp3 = looksLikeMp3(bytes)
        duration = if (isMp3) mp3DurationMs(bytes) else wavDurationMs(bytes)
        position = 0L
    }

    override fun play() {
        if (released || source.isEmpty() || playing) return
        if (worker?.isAlive == true) return
        pauseRequested = false
        worker = Thread(if (isMp3) ::mp3Loop else ::wavLoop, if (isMp3) "pots-audio-mp3" else "pots-audio-wav")
        worker!!.isDaemon = true
        worker!!.start()
    }

    override fun pause() {
        pauseRequested = true
    }

    override fun seek(positionMs: Long) {
        val target = positionMs.coerceIn(0L, duration.takeIf { it > 0 } ?: positionMs)
        if (worker?.isAlive == true) {
            seekTargetMs = target
        } else {
            position = target
        }
    }

    override fun setSpeed(factor: Float) {
        val next = factor.coerceIn(0.25f, 4f)
        if (next == speed) return
        speed = next
        speedChangePending = true
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun setLooping(loop: Boolean) {
        looping = loop
    }

    override fun release() {
        released = true
        playing = false
        worker?.interrupt()
        worker = null
    }

    // -- MP3 ----------------------------------------------------------------

    private fun mp3DurationMs(bytes: ByteArray): Long = try {
        val bitstream = javazoom.jl.decoder.Bitstream(java.io.ByteArrayInputStream(bytes))
        var frames = 0f
        var msPerFrame = 0f
        while (true) {
            val header = bitstream.readFrame() ?: break
            if (msPerFrame == 0f) msPerFrame = header.ms_per_frame()
            frames += 1f
            bitstream.closeFrame()
        }
        bitstream.close()
        (frames * msPerFrame).toLong()
    } catch (e: Exception) {
        0L
    }

    private class Mp3Cursor(
        val bitstream: javazoom.jl.decoder.Bitstream,
        val decoder: javazoom.jl.decoder.Decoder,
        val index: Long,
        val msPerFrame: Float,
    )

    /** Opens the stream positioned at [targetMs]; earlier frames are skipped without synthesis. */
    private fun openMp3At(targetMs: Long): Mp3Cursor? {
        val bitstream = javazoom.jl.decoder.Bitstream(java.io.ByteArrayInputStream(source))
        var msPerFrame = 0f
        var index = 0L
        var elapsed = 0f
        try {
            while (true) {
                val header = bitstream.readFrame() ?: return null
                if (msPerFrame == 0f) msPerFrame = header.ms_per_frame()
                if (elapsed + msPerFrame > targetMs) {
                    bitstream.unreadFrame()
                    break
                }
                elapsed += msPerFrame
                index++
                bitstream.closeFrame()
            }
        } catch (e: Exception) {
            return null
        }
        return Mp3Cursor(bitstream, javazoom.jl.decoder.Decoder(), index, msPerFrame)
    }

    private fun mp3Loop() {
        playing = true
        try {
            var cursor = openMp3At(0L) ?: return
            var index = cursor.index
            while (!released) {
                if (waitWhilePaused()) break
                val target = seekTargetMs
                if (target >= 0) {
                    seekTargetMs = -1L
                    runCatching { cursor.bitstream.close() }
                    cursor = openMp3At(target) ?: break
                    index = cursor.index
                    position = target
                }
                val header = cursor.bitstream.readFrame()
                if (header == null) {
                    if (looping) {
                        // Restart from the beginning instead of stopping.
                        runCatching { cursor.bitstream.close() }
                        cursor = openMp3At(0L) ?: break
                        index = cursor.index
                        position = 0L
                        continue
                    }
                    break
                }
                val output = cursor.decoder.decodeFrame(header, cursor.bitstream) as? javazoom.jl.decoder.SampleBuffer
                cursor.bitstream.closeFrame()
                if (output == null) {
                    index++
                    continue
                }
                val line = lineFor((output.sampleFrequency * speed).toInt(), output.channelCount) ?: break
                val samples = output.buffer
                val pcm = ByteArray(output.bufferLength * 2)
                for (i in 0 until output.bufferLength) {
                    val v = samples[i].toInt()
                    pcm[i * 2] = (v and 0xFF).toByte()
                    pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                applyVolume(pcm, pcm.size)
                line.write(pcm, 0, pcm.size)
                index++
                position = (index * cursor.msPerFrame).toLong().coerceAtMost(duration)
                if (speedChangePending) {
                    speedChangePending = false
                    // The line's sample rate encodes speed; reopen to apply it.
                    closeLine()
                }
            }
            if (!released) position = duration
        } catch (e: Exception) {
            // Decode or line failure: stop quietly, keep the last position.
        } finally {
            playing = false
            closeLine()
        }
    }

    // -- WAV ----------------------------------------------------------------

    private fun wavDurationMs(bytes: ByteArray): Long = try {
        val stream = javax.sound.sampled.AudioSystem.getAudioInputStream(java.io.ByteArrayInputStream(bytes))
        val frames = stream.frameLength
        val rate = stream.format.frameRate
        if (frames > 0 && rate > 0f) frames * 1000L / rate.toLong() else 0L
    } catch (e: Exception) {
        0L
    }

    private class WavCursor(
        val stream: javax.sound.sampled.AudioInputStream,
        val format: javax.sound.sampled.AudioFormat,
        val line: javax.sound.sampled.SourceDataLine,
        val chunk: ByteArray,
        var framesWritten: Long,
    )

    private fun openWavAt(targetMs: Long): WavCursor? = try {
        var stream = javax.sound.sampled.AudioSystem.getAudioInputStream(java.io.ByteArrayInputStream(source))
        var format = stream.format
        if (format.encoding != javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED) {
            val pcm = javax.sound.sampled.AudioFormat(
                javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                format.sampleRate,
                16,
                format.channels,
                format.channels * 2,
                format.sampleRate,
                false,
            )
            stream = javax.sound.sampled.AudioSystem.getAudioInputStream(pcm, stream)
            format = pcm
        }
        var framesWritten = 0L
        if (targetMs > 0 && format.frameRate > 0f) {
            framesWritten = targetMs * format.frameRate.toLong() / 1000L
            stream.skip(framesWritten * format.frameSize)
        }
        // Speed trick: the line plays raw PCM at its own sample rate, so opening
        // it at rate*speed resamples without any DSP (pitch follows speed).
        val lineFormat = if (speed == 1f) {
            format
        } else {
            javax.sound.sampled.AudioFormat(format.sampleRate * speed, 16, format.channels, true, false)
        }
        val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, lineFormat)
        val line = javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
        line.open(lineFormat)
        line.start()
        WavCursor(stream, format, line, ByteArray((format.frameSize * 512).coerceAtLeast(1024)), framesWritten)
    } catch (e: Exception) {
        null
    }

    private fun wavLoop() {
        playing = true
        try {
            var cursor = openWavAt(0L) ?: return
            while (!released) {
                if (waitWhilePaused()) break
                val target = seekTargetMs
                if (target >= 0) {
                    seekTargetMs = -1L
                    runCatching { cursor.line.close() }
                    cursor = openWavAt(target) ?: break
                    position = target
                }
                if (speedChangePending) {
                    speedChangePending = false
                    val posMs = if (cursor.format.frameRate > 0f) {
                        cursor.framesWritten * 1000L / cursor.format.frameRate.toLong()
                    } else {
                        0L
                    }
                    runCatching { cursor.line.close() }
                    cursor = openWavAt(posMs) ?: break
                    position = posMs
                }
                val read = cursor.stream.read(cursor.chunk, 0, cursor.chunk.size)
                if (read <= 0) {
                    if (looping) {
                        // Restart from the beginning instead of stopping.
                        runCatching { cursor.line.close() }
                        cursor = openWavAt(0L) ?: break
                        position = 0L
                        continue
                    }
                    break
                }
                applyVolume(cursor.chunk, read)
                cursor.line.write(cursor.chunk, 0, read)
                cursor.framesWritten += read / cursor.format.frameSize
                position = (cursor.framesWritten * 1000L / cursor.format.frameRate.toLong()).coerceAtMost(duration)
            }
            runCatching { cursor.line.close() }
            if (!released) position = duration
        } catch (e: Exception) {
            // Decode or line failure: stop quietly.
        } finally {
            playing = false
            closeLine()
        }
    }

    /** Blocks while paused; true when the engine was released during the wait. */
    private fun waitWhilePaused(): Boolean {
        if (!pauseRequested) return false
        playing = false
        while (pauseRequested && !released) Thread.sleep(50)
        if (released) return true
        playing = true
        return false
    }

    // -- Shared line management ---------------------------------------------

    private fun lineFor(sampleRate: Int, channels: Int): javax.sound.sampled.SourceDataLine? {
        line?.let {
            if (lineRate == sampleRate) return it
            closeLine()
        }
        return try {
            val format = javax.sound.sampled.AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
            val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, format)
            val opened = javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
            opened.open(format)
            opened.start()
            line = opened
            lineRate = sampleRate
            opened
        } catch (e: Exception) {
            null
        }
    }

    private fun closeLine() {
        runCatching {
            line?.stop()
            line?.close()
        }
        line = null
        lineRate = 0
    }

    /** Scales interleaved 16-bit PCM in place by the current volume. */
    private fun applyVolume(pcm: ByteArray, length: Int) {
        val gain = volume
        if (gain >= 1f) return
        if (gain <= 0f) {
            for (i in 0 until length) pcm[i] = 0
            return
        }
        var i = 0
        while (i + 1 < length) {
            val value = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
            val scaled = (value * gain).toInt().coerceIn(-32768, 32767).toShort()
            pcm[i] = (scaled.toInt() and 0xFF).toByte()
            pcm[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}

/** ID3v2 tag or MPEG frame sync. */
private fun looksLikeMp3(bytes: ByteArray): Boolean {
    if (bytes.size < 2) return false
    if (bytes.size >= 3 && bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) return true // "ID3"
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    return b0 == 0xFF && (b1 and 0xE0) == 0xE0
}
