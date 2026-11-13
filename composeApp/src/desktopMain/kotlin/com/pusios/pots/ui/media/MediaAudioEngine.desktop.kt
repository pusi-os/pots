package com.pusios.pots.ui.media

/**
 * Desktop audio engine: MP3 via JLayer, WAV via javax.sound. One worker thread
 * per engine decodes frames into a [javax.sound.sampled.SourceDataLine];
 * pause stops the writes, seek reopens the stream and skips frames.
 */
actual fun createMediaAudioEngine(): MediaAudioEngine = DesktopMediaAudioEngine()

internal class DesktopMediaAudioEngine : MediaAudioEngine {

    override val isSupported: Boolean = true

    private var source: ByteArray = ByteArray(0)
    private var isMp3 = false

    @Volatile private var duration = 0L
    @Volatile private var position = 0L
    @Volatile private var playing = false
    @Volatile private var released = false
    @Volatile private var pauseRequested = false
    @Volatile private var seekTargetMs = -1L

    private var worker: Thread? = null
    private var line: javax.sound.sampled.SourceDataLine? = null

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

    override fun release() {
        released = true
        playing = false
        worker?.interrupt()
        worker = null
    }

    // -- MP3 ----------------------------------------------------------------

    private fun mp3DurationMs(bytes: ByteArray): Long {
        return try {
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
                val header = cursor.bitstream.readFrame() ?: break
                val output = cursor.decoder.decodeFrame(header, cursor.bitstream) as? javazoom.jl.decoder.SampleBuffer
                cursor.bitstream.closeFrame()
                if (output == null) {
                    index++
                    continue
                }
                val line = lineFor(output.sampleFrequency, output.channelCount) ?: break
                val samples = output.buffer
                val pcm = ByteArray(output.bufferLength * 2)
                for (i in 0 until output.bufferLength) {
                    val v = samples[i].toInt()
                    pcm[i * 2] = (v and 0xFF).toByte()
                    pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                line.write(pcm, 0, pcm.size)
                index++
                position = (index * cursor.msPerFrame).toLong().coerceAtMost(duration)
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

    private fun wavDurationMs(bytes: ByteArray): Long {
        return try {
            val stream = javax.sound.sampled.AudioSystem.getAudioInputStream(java.io.ByteArrayInputStream(bytes))
            val frames = stream.frameLength
            val rate = stream.format.frameRate
            if (frames > 0 && rate > 0f) frames * 1000L / rate.toLong() else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private class WavCursor(
        val stream: javax.sound.sampled.AudioInputStream,
        val format: javax.sound.sampled.AudioFormat,
        val line: javax.sound.sampled.SourceDataLine,
        val chunk: ByteArray,
        var framesWritten: Long,
    )

    private fun openWavAt(targetMs: Long): WavCursor? {
        return try {
            var stream = javax.sound.sampled.AudioSystem.getAudioInputStream(java.io.ByteArrayInputStream(source))
            var format = stream.format
            if (format.encoding != javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED) {
                val pcm = javax.sound.sampled.AudioFormat(
                    javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                    format.sampleRate, 16, format.channels, format.channels * 2, format.sampleRate, false,
                )
                stream = javax.sound.sampled.AudioSystem.getAudioInputStream(pcm, stream)
                format = pcm
            }
            var framesWritten = 0L
            if (targetMs > 0 && format.frameRate > 0f) {
                framesWritten = targetMs * format.frameRate.toLong() / 1000L
                stream.skip(framesWritten * format.frameSize)
            }
            val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, format)
            val line = javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
            line.open(format)
            line.start()
            WavCursor(stream, format, line, ByteArray((format.frameSize * 512).coerceAtLeast(1024)), framesWritten)
        } catch (e: Exception) {
            null
        }
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
                val read = cursor.stream.read(cursor.chunk, 0, cursor.chunk.size)
                if (read <= 0) break
                cursor.line.write(cursor.chunk, 0, read)
                cursor.framesWritten += read / cursor.format.frameSize
                position = (cursor.framesWritten * 1000L / cursor.format.frameRate.toLong()).coerceAtMost(duration)
            }
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
        line?.let { return it }
        return try {
            val format = javax.sound.sampled.AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
            val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, format)
            val opened = javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
            opened.open(format)
            opened.start()
            line = opened
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
