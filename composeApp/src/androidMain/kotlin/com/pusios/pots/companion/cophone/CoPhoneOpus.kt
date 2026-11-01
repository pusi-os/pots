package com.pusios.pots.companion.cophone

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus <-> PCM on the phone via MediaCodec (ported from the PixelTag CoPhone
 * reference, byte-compatible with the PotsCharm cube: Opus 16 kHz mono, 60 ms
 * frames in BOTH directions).
 *
 * The cube speaks Opus (one raw packet per BLE write) because raw PCM does not
 * fit the link. The phone decodes the cube's uplink to 16 kHz PCM for the
 * recognizer, and encodes TTS PCM back to Opus packets for the cube speaker.
 * Best-effort: if the device has no Opus codec these return null and the
 * caller degrades.
 *
 * Opus is internally always 48 kHz: the decoder emits 48 kHz PCM, decimated to
 * 16 kHz for the recognizer. The encoder runs at 16 kHz to match the cube's
 * codec/I2S rate exactly (no cube-side resample).
 */
internal object CoPhoneOpus {
    private const val TAG = "CoPhoneOpus"

    /** Last decode failure, for a diagnostic line. */
    @Volatile
    var lastError: String? = null

    /** 19-byte OpusHead (RFC 7845) for the codec config. */
    fun header(channels: Int, preSkip: Int, inputRate: Int): ByteArray {
        val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OpusHead".toByteArray(Charsets.US_ASCII))
        b.put(1)
        b.put(channels.toByte())
        b.putShort(preSkip.toShort())
        b.putInt(inputRate)
        b.putShort(0)
        b.put(0)
        return b.array()
    }

    private fun zeroLongLe(): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()

    /** 48 kHz -> 16 kHz: average three consecutive samples (cheap anti-alias). */
    fun decimate3(pcm: ShortArray): ShortArray {
        val n = pcm.size / 3
        val out = ShortArray(n)
        var i = 0
        var o = 0
        while (o < n) {
            val a = pcm[i].toInt()
            val b = pcm[i + 1].toInt()
            val c = pcm[i + 2].toInt()
            out[o] = ((a + b + c) / 3).toShort()
            i += 3
            o++
        }
        return out
    }

    /**
     * Resampler for TTS PCM -> 16 kHz. Linear interpolation when upsampling, a
     * box average when downsampling (step >= 2) so decimation does not alias.
     */
    fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || pcm.isEmpty()) return pcm
        val outLen = (pcm.size.toLong() * to / from).toInt()
        val out = ShortArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceIn(0, pcm.size - 1)
            if (step >= 2.0) {
                val i1 = (pos + step).toInt().coerceIn(i0 + 1, pcm.size)
                var acc = 0L
                for (j in i0 until i1) acc += pcm[j]
                out[i] = (acc / (i1 - i0)).toInt().toShort()
            } else {
                val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
                val f = (pos - i0).toFloat()
                out[i] = (pcm[i0] + (pcm[i1] - pcm[i0]) * f).toInt().toShort()
            }
        }
        return out
    }

    fun pcmToBytes(pcm: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        return bb.array()
    }

    // RFC 6716 Table 2: Opus TOC config -> frame size in 48 kHz samples, and
    // the frame-count code -> number of frames per packet.
    private val OPUS_FRAME_SAMPLES = intArrayOf(
        480, 960, 1920, 2880,
        480, 960, 1920, 2880,
        480, 960, 1920, 2880,
        480, 960,
        480, 960,
        120, 240, 480, 960,
        120, 240, 480, 960,
        120, 240, 480, 960,
        120, 240, 480, 960,
    )
    private val OPUS_FRAME_COUNT = intArrayOf(1, 2, 2, 3)

    /**
     * Duration of a raw Opus packet in ms, from its TOC byte. Used to pace the
     * downlink at real time so the cube's decode queue neither starves nor
     * overflows.
     */
    fun packetDurationMs(p: ByteArray): Int {
        if (p.isEmpty()) return 60
        val toc = p[0].toInt() and 0xFF
        val config = (toc ushr 3) and 0x1F
        val code = toc and 0x03
        val samples = OPUS_FRAME_SAMPLES[config] * OPUS_FRAME_COUNT[code]
        return (samples * 1000L / 48000L).toInt().coerceIn(2, 240)
    }

    /** Decode a whole utterance of raw Opus packets to 16 kHz mono PCM. */
    fun decodeAll(packets: List<ByteArray>): ShortArray? {
        lastError = null
        if (packets.isEmpty()) return ShortArray(0)
        var codec: MediaCodec? = null
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1)
            // All three CSD buffers are required by the Codec2 Opus decoder:
            // with only csd-0 it treats the first audio packets as header
            // buffers and never produces PCM.
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(header(1, 0, 48000)))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(zeroLongLe()))
            fmt.setByteBuffer("csd-2", ByteBuffer.wrap(zeroLongLe()))
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val out = ArrayList<Short>(packets.size * 960)
            var pts = 0L
            var outRate = 48000
            for (p in packets) {
                var inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx < 0) {
                    outRate = drain(codec, info, out, outRate)
                    inIdx = codec.dequeueInputBuffer(10_000)
                }
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    if (buf != null) {
                        buf.clear()
                        buf.put(p)
                        codec.queueInputBuffer(inIdx, 0, p.size, pts, 0)
                        pts += 60_000
                    }
                }
                outRate = drain(codec, info, out, outRate)
            }
            for (i in 0 until 8) outRate = drain(codec, info, out, outRate)

            val pcm = ShortArray(out.size)
            for (i in out.indices) pcm[i] = out[i]
            if (pcm.isEmpty()) {
                lastError = "empty out rate=$outRate"
                return ShortArray(0)
            }
            return if (outRate == 16000) pcm else decimate3(pcm)
        } catch (t: Throwable) {
            lastError = (t::class.simpleName ?: "err") + ": " + (t.message ?: "")
            Log.w(TAG, "opus decode unavailable", t)
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** Non-blocking drain; returns the (possibly updated) output rate. */
    private fun drain(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        out: ArrayList<Short>,
        rate: Int,
    ): Int {
        var r = rate
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 5_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    r = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)

                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return r

                o >= 0 -> {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(o)
                        if (ob != null) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val s = ShortArray(info.size / 2)
                            ob.slice().order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer().get(s)
                            for (v in s) out.add(v)
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return r
                }

                else -> return r
            }
        }
    }

    /**
     * Encode 16 kHz mono PCM to raw Opus packets (60 ms frames). Returns null
     * when the device has no Opus encoder.
     */
    fun encodeAll(pcm: ShortArray): List<ByteArray>? {
        if (pcm.isEmpty()) return emptyList()
        var codec: MediaCodec? = null
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 24000)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val packets = ArrayList<ByteArray>()
            val info = MediaCodec.BufferInfo()
            val frame = 960 // 60 ms @ 16 kHz — the cube's frame size
            var pos = 0
            var inputDone = false
            var outputDone = false
            var pts = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        if (pos < pcm.size) {
                            val n = minOf(frame, pcm.size - pos)
                            for (i in 0 until n) buf.putShort(pcm[pos + i])
                            for (i in n until frame) buf.putShort(0)
                            codec.queueInputBuffer(inIdx, 0, frame * 2, pts, 0)
                            pos += n
                            pts += 1_000_000L * frame / 16000
                        } else {
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                pts,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val p = ByteArray(info.size)
                        ob.get(p)
                        packets.add(p)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
            return packets
        } catch (t: Throwable) {
            Log.w(TAG, "opus encode unavailable", t)
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
        }
    }
}
