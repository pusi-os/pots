package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `sync:` framing is the part of `adb push` that a device cannot forgive: a wrong byte order
 * silently installs a truncated APK. These tests pin the exact bytes, not just the round trip.
 */
class AdbSyncProtocolTest {

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    @Test
    fun `send request carries the path without a terminator`() {
        val path = "/data/local/tmp/pots-adb-install.apk"
        val frame = AdbSyncProtocol.sendRequest(path)

        assertEquals("SEND", frame.copyOfRange(0, 4).decodeToString())
        assertEquals(path.encodeToByteArray().size, littleEndianInt(frame, 4))
        assertEquals(path, frame.copyOfRange(AdbSyncProtocol.HEADER_SIZE, frame.size).decodeToString())
        assertEquals(AdbSyncProtocol.HEADER_SIZE + path.encodeToByteArray().size, frame.size)
    }

    @Test
    fun `data chunk frames only the requested slice`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        val frame = AdbSyncProtocol.dataChunk(payload, offset = 2, length = 3)

        assertEquals("DATA", frame.copyOfRange(0, 4).decodeToString())
        assertEquals(3, littleEndianInt(frame, 4))
        assertEquals(listOf<Byte>(3, 4, 5), frame.copyOfRange(AdbSyncProtocol.HEADER_SIZE, frame.size).toList())
    }

    @Test
    fun `done chunk carries the modification time`() {
        val frame = AdbSyncProtocol.doneChunk(1_700_000_000)

        assertEquals("DONE", frame.copyOfRange(0, 4).decodeToString())
        assertEquals(1_700_000_000, littleEndianInt(frame, 4))
    }

    @Test
    fun `okay and fail responses are recognised`() {
        val okay = "OKAY".encodeToByteArray() + byteArrayOf(0, 0, 0, 0)
        assertEquals(AdbSyncResponse.Okay, AdbSyncProtocol.parseResponse(okay))

        val fail = "FAIL".encodeToByteArray() + byteArrayOf(4, 0, 0, 0)
        assertEquals(AdbSyncResponse.Fail(4), AdbSyncProtocol.parseResponse(fail))
    }

    @Test
    fun `an unknown response id is a hard error`() {
        val unknown = "STAT".encodeToByteArray() + byteArrayOf(0, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> { AdbSyncProtocol.parseResponse(unknown) }
    }

    @Test
    fun `an oversized chunk is rejected before it reaches the socket`() {
        val payload = ByteArray(AdbSyncProtocol.MAX_DATA_CHUNK + 1)
        assertFailsWith<IllegalArgumentException> { AdbSyncProtocol.dataChunk(payload) }
    }
}
