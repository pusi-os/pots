package com.pusios.pots.companion.cophone

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Ogg/Opus framer (pure Kotlin, no native libs): wraps raw Opus
 * packets from the cube into a valid Ogg Opus stream for
 * `/audio/transcriptions` endpoints (Whisper-compatible servers accept
 * `audio/ogg`). One job, no I/O: bytes in, bytes out.
 *
 * Layout: OpusHead + OpusTags + audio pages (one packet per page,
 * granule = cumulative 960-sample frames @48 kHz mono). CRC-32 per page.
 */
internal object CoPhoneOgg {
    private const val SAMPLES_PER_FRAME = 960 // 20 ms @ 48 kHz

    fun frame(packets: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val serial = 0x50555349 // "PUSI"
        var seq = 0
        var granule = 0L
        fun page(headerType: Int, payload: ByteArray, granulePos: Long) {
            // Segment table: full 255B laces + terminator (a bare [255]
            // would mean "packet continues on the next page").
            val nseg = payload.size / 255 + 1
            val head = ByteBuffer.allocate(27 + nseg).order(ByteOrder.LITTLE_ENDIAN)
            head.put("OggS".toByteArray())
            head.put(0) // version
            head.put(headerType.toByte())
            head.putLong(granulePos)
            head.putInt(serial)
            head.putInt(seq++)
            head.putInt(0) // checksum placeholder
            head.put(nseg.toByte())
            var rest = payload.size
            while (rest >= 255) {
                head.put(255.toByte())
                rest -= 255
            }
            head.put(rest.toByte())
            val hb = head.array()
            val crc = crc32(hb + payload)
            hb[22] = (crc and 0xFF).toByte()
            hb[23] = ((crc shr 8) and 0xFF).toByte()
            hb[24] = ((crc shr 16) and 0xFF).toByte()
            hb[25] = ((crc shr 24) and 0xFF).toByte()
            out.write(hb)
            out.write(payload)
        }
        // OpusHead: magic + version + channels + pre-skip + 48000 + gain + map(0)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray())
        head.put(1) // version
        head.put(1) // mono
        head.putShort(384) // pre-skip (20 ms @ 48k incl. lookahead)
        head.putInt(48000)
        head.putShort(0) // gain
        head.put(0) // channel mapping family 0
        page(0x02, head.array(), 0L) // BOS
        // OpusTags: magic + vendor + 0 user comments
        val vendor = "Pots-CoPhone".toByteArray()
        val tags = ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        tags.put("OpusTags".toByteArray())
        tags.putInt(vendor.size)
        tags.put(vendor)
        tags.putInt(0)
        page(0x00, tags.array(), 0L)
        // Audio pages.
        packets.forEachIndexed { i, p ->
            granule += SAMPLES_PER_FRAME
            val last = i == packets.lastIndex
            page(if (last) 0x04 else 0x00, p, granule)
        }
        return out.toByteArray()
    }

    /**
     * Extract raw Opus packets from an Ogg/Opus stream (e.g. the endpoint's
     * `response_format=opus` TTS). Drops the OpusHead/OpusTags header packets;
     * returns one entry per audio packet, ready for `AUDIO_DOWN`.
     */
    fun demux(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val cur = ByteArrayOutputStream()
        var off = 0
        while (off + 27 <= data.size) {
            if (!(
                    data[off] == 'O'.code.toByte() && data[off + 1] == 'g'.code.toByte() &&
                        data[off + 2] == 'g'.code.toByte() && data[off + 3] == 'S'.code.toByte()
                    )
            ) {
                off++
                continue
            }
            val nseg = data[off + 26].toInt() and 0xFF
            var p = off + 27
            if (p + nseg > data.size) break
            val segs = IntArray(nseg) { data[p + it].toInt() and 0xFF }
            p += nseg
            val payloadLen = segs.sum()
            if (p + payloadLen > data.size) break
            for (s in segs) {
                if (s > 0) cur.write(data, p, s)
                p += s
                if (s < 255) {
                    out.add(cur.toByteArray())
                    cur.reset()
                }
            }
            off = p
        }
        // First two packets are OpusHead + OpusTags.
        return out.drop(2).filter { it.isNotEmpty() }
    }

    private operator fun ByteArray.plus(o: ByteArray): ByteArray {
        val r = ByteArray(size + o.size)
        System.arraycopy(this, 0, r, 0, size)
        System.arraycopy(o, 0, r, size, o.size)
        return r
    }

    private val CRC_TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

    private fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) crc = CRC_TABLE[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        return crc xor 0xFFFFFFFF.toInt()
    }
}
