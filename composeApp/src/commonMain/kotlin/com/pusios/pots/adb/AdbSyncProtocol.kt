package com.pusios.pots.adb

/** What the daemon answered to a sync frame: an `OKAY` or a `FAIL` carrying a message length. */
sealed class AdbSyncResponse {
    data object Okay : AdbSyncResponse()

    data class Fail(val messageLength: Int) : AdbSyncResponse()
}

/**
 * The wire format of the `sync:` service, which is how `adb push` works.
 *
 * libadb-android opens the service and hands back a byte stream; the framing on top of it is ours.
 * Every frame starts with a four-byte little-endian id followed by a four-byte little-endian length,
 * and a push is `SEND(path)` then `DATA` chunks then `DONE(mtime)` then one response frame:
 *
 * ```
 * SEND  id | path_length | path bytes (no NUL)
 * DATA  id | chunk_length | chunk bytes
 * DONE  id | mtime_seconds
 * OKAY  id | 0                  — or —
 * FAIL  id | message_length | message bytes
 * ```
 *
 * Keeping the framing here, as pure functions over byte arrays, is what makes it testable: the
 * transport only moves bytes, and a wrong byte order is caught by a test instead of by a device.
 */
object AdbSyncProtocol {
    /** The largest payload a single DATA frame may carry. */
    const val MAX_DATA_CHUNK = 64 * 1024

    /** Every sync frame header is an id and a length, both little-endian 32-bit. */
    const val HEADER_SIZE = 8

    val ID_SEND = frameId("SEND")
    val ID_DATA = frameId("DATA")
    val ID_DONE = frameId("DONE")
    val ID_OKAY = frameId("OKAY")
    val ID_FAIL = frameId("FAIL")

    /** The opening frame of a push: where the file goes on the device. */
    fun sendRequest(path: String): ByteArray {
        val pathBytes = path.encodeToByteArray()
        require(pathBytes.isNotEmpty()) { "sync path must not be empty" }
        val frame = ByteArray(HEADER_SIZE + pathBytes.size)
        writeInt(frame, 0, ID_SEND)
        writeInt(frame, 4, pathBytes.size)
        pathBytes.copyInto(frame, HEADER_SIZE)
        return frame
    }

    /** One chunk of file content. Callers split the file into [MAX_DATA_CHUNK] pieces. */
    fun dataChunk(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= payload.size) { "chunk out of bounds" }
        require(length <= MAX_DATA_CHUNK) { "chunk of $length bytes exceeds $MAX_DATA_CHUNK" }
        val frame = ByteArray(HEADER_SIZE + length)
        writeInt(frame, 0, ID_DATA)
        writeInt(frame, 4, length)
        payload.copyInto(frame, HEADER_SIZE, offset, offset + length)
        return frame
    }

    /** The closing frame of a push, carrying the file's modification time in seconds. */
    fun doneChunk(mtimeSeconds: Int): ByteArray {
        val frame = ByteArray(HEADER_SIZE)
        writeInt(frame, 0, ID_DONE)
        writeInt(frame, 4, mtimeSeconds)
        return frame
    }

    /**
     * Reads the daemon's answer to a finished push. On [AdbSyncResponse.Fail] the caller reads
     * [AdbSyncResponse.Fail.messageLength] more bytes for the reason.
     */
    fun parseResponse(header: ByteArray): AdbSyncResponse {
        require(header.size >= HEADER_SIZE) { "response header must be $HEADER_SIZE bytes" }
        val id = readInt(header, 0)
        val length = readInt(header, 4)
        return when (id) {
            ID_OKAY -> AdbSyncResponse.Okay
            ID_FAIL -> AdbSyncResponse.Fail(length)
            else -> throw IllegalArgumentException("unexpected sync response id 0x${id.toUInt().toString(16)}")
        }
    }

    private fun frameId(word: String): Int = (word[0].code) or (word[1].code shl 8) or (word[2].code shl 16) or (word[3].code shl 24)

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readInt(source: ByteArray, offset: Int): Int = (source[offset].toInt() and 0xFF) or
        ((source[offset + 1].toInt() and 0xFF) shl 8) or
        ((source[offset + 2].toInt() and 0xFF) shl 16) or
        ((source[offset + 3].toInt() and 0xFF) shl 24)
}
