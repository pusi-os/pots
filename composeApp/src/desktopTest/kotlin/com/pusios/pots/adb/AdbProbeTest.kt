// The probe's wire handling, checked without a device: the exact CNXN bytes every adb client sends,
// the four answers adbd can give, the broken-packet case that the 3.21.0 TV report mistook for
// silence, and the timeout each verdict earns. A passing test here says the classification is right,
// not that the socket works.

package com.pusios.pots.adb

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdbProbeTest {

    private fun readInt(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun reply(
        command: Int,
        arg0: Int = 0,
        arg1: Int = 0,
        payload: ByteArray = ByteArray(0),
        magic: Int = command xor -1,
    ): ByteArray {
        val header = ByteArray(AdbProbeProtocol.HEADER_SIZE)
        writeInt(header, 0, command)
        writeInt(header, 4, arg0)
        writeInt(header, 8, arg1)
        writeInt(header, 12, payload.size)
        writeInt(header, 16, payload.sumOf { byte -> (byte.toInt() and 0xFF).toLong() }.toInt())
        writeInt(header, 20, magic)
        return header + payload
    }

    @Test
    fun `the probe sends the 31-byte CNXN the report measured against the TV`() {
        val packet = AdbProbeProtocol.connectPacket()

        assertEquals(31, packet.size)
        assertEquals(0x4E584E43, readInt(packet, 0), "command must be CNXN")
        assertEquals(0x01000000, readInt(packet, 4), "protocol version")
        assertEquals(4096, readInt(packet, 8), "max payload")
        assertEquals(7, readInt(packet, 12), "payload length")
        assertEquals(0x232, readInt(packet, 16), "payload checksum")
        // magic = command xor 0xFFFFFFFF; the broken 0xFFFFFFF8 packet of the report fails this.
        assertEquals(0xB1A7B1BC.toInt(), readInt(packet, 20), "magic")
        assertEquals("host::\u0000", packet.copyOfRange(AdbProbeProtocol.HEADER_SIZE, packet.size).decodeToString())
    }

    @Test
    fun `A_AUTH means the daemon wants a key, which is the waiting-for-the-owner case`() {
        val token = ByteArray(20) { index -> index.toByte() }

        val result = AdbProbeProtocol.parseReply(reply(AdbProbeProtocol.A_AUTH, arg0 = 1, payload = token))

        assertEquals(AdbProbeResult.AuthRequired, result)
        assertTrue(adbProbeIsDaemon(result))
    }

    @Test
    fun `A_STLS means a wireless-debugging TLS daemon`() {
        assertEquals(AdbProbeResult.TlsRequired, AdbProbeProtocol.parseReply(reply(AdbProbeProtocol.A_STLS)))
        assertTrue(adbProbeIsDaemon(AdbProbeResult.TlsRequired))
    }

    @Test
    fun `A_CNXN means the key is already trusted`() {
        assertEquals(AdbProbeResult.Connected, AdbProbeProtocol.parseReply(reply(AdbProbeProtocol.A_CNXN)))
    }

    @Test
    fun `the broken magic of the TV report is not an ADB daemon`() {
        // The report's "0 bytes in 20 s" packet had magic 0xFFFFFFF8 instead of command xor 0xFFFFFFFF.
        // adbd drops such a transport silently; a probe must call that "not ADB", never "auth pending".
        val broken = reply(AdbProbeProtocol.A_AUTH, magic = 0xFFFFFFF8.toInt())

        assertIs<AdbProbeResult.NotAdb>(AdbProbeProtocol.parseReply(broken))
        assertEquals(false, adbProbeIsDaemon(AdbProbeProtocol.parseReply(broken)))
    }

    @Test
    fun `a truncated header is not an ADB daemon`() {
        val truncated = AdbProbeProtocol.connectPacket().copyOfRange(0, 10)

        assertIs<AdbProbeResult.NotAdb>(AdbProbeProtocol.parseReply(truncated))
    }

    @Test
    fun `an unknown packet is not an ADB daemon`() {
        val unknown = reply(0x11223344)

        val result = AdbProbeProtocol.parseReply(unknown)

        assertIs<AdbProbeResult.NotAdb>(result)
        assertTrue(result.detail.contains("unexpected"))
    }

    @Test
    fun `socket failures are classified by their names`() {
        assertEquals(AdbProbeResult.PortClosed, adbProbeFailureOf(ConnectException("Connection refused")))
        assertEquals(
            AdbProbeResult.NoRoute,
            adbProbeFailureOf(ConnectException("Network is unreachable")),
        )
        assertEquals(AdbProbeResult.NoRoute, adbProbeFailureOf(NoRouteToHostException("No route to host")))
        assertEquals(AdbProbeResult.Timeout, adbProbeFailureOf(SocketTimeoutException("connect timed out")))
        assertIs<AdbProbeResult.Failed>(adbProbeFailureOf(IllegalStateException("weird")))
    }

    @Test
    fun `only a daemon that asked for a key earns the two-minute window`() {
        assertEquals(AdbTimeouts.CONNECT_FIRST_SEC, adbConnectTimeoutSec(AdbProbeResult.AuthRequired, firstContact = false))
        assertEquals(AdbTimeouts.CONNECT_FIRST_SEC, adbConnectTimeoutSec(AdbProbeResult.AuthRequired, firstContact = true))
        assertEquals(AdbTimeouts.CONNECT_SEC, adbConnectTimeoutSec(AdbProbeResult.Connected, firstContact = false))
        assertEquals(AdbTimeouts.CONNECT_SEC, adbConnectTimeoutSec(AdbProbeResult.TlsRequired, firstContact = false))
        assertEquals(AdbTimeouts.CONNECT_FIRST_SEC, adbConnectTimeoutSec(AdbProbeResult.TlsRequired, firstContact = true))
    }

    @Test
    fun `a closed port and a silent port fail with different words`() {
        val closed = adbProbeException("192.168.1.243", 5555, AdbProbeResult.PortClosed)
        val silent = adbProbeException("192.168.1.243", 5555, AdbProbeResult.Silent)

        assertEquals(AdbErrorKind.UNREACHABLE, closed.kind)
        assertTrue(closed.message.orEmpty().contains("closed"))
        assertEquals(AdbErrorKind.NOT_ADB, silent.kind)
        assertTrue(silent.message.orEmpty().contains("does not answer as ADB"))
    }

    @Test
    fun `reachability separates a silent port from a closed one`() {
        assertEquals(false, adbProbeTcpReachable(AdbProbeResult.PortClosed))
        assertEquals(false, adbProbeTcpReachable(AdbProbeResult.NoRoute))
        assertEquals(false, adbProbeTcpReachable(AdbProbeResult.Timeout))
        assertEquals(true, adbProbeTcpReachable(AdbProbeResult.Silent))
        assertEquals(true, adbProbeTcpReachable(AdbProbeResult.AuthRequired))
    }

    @Test
    fun `the hint for a waiting device tells the owner what to tap`() {
        val hint = adbProbeHint(AdbProbeResult.AuthRequired).orEmpty()

        assertTrue(hint.contains("«Allow»"))
        assertTrue(hint.contains("Always allow"))
        assertEquals(null, adbProbeHint(AdbProbeResult.Connected))
    }
}
