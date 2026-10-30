package com.pusios.pots.companion.cophone

import java.util.UUID

/**
 * CoPhone wire UUIDs and opcodes, v2 (BROMESH-SPEC v1.3 §13). Byte-identical
 * to the firmware (`firmwares/voice/pixeltag/main/display/compixi/cophone.cc`).
 * Never change without a version bump: the cube matches the service UUID in
 * the scan.
 */
object CoPhoneUuids {
    val SERVICE: UUID = UUID.fromString("8f4b8c1e-9d2a-4c5e-b6f0-1a2b3c4d5e01")
    val AUDIO_UP: UUID = UUID.fromString("8f4b8c1e-9d2a-4c5e-b6f0-1a2b3c4d5e02")
    val AUDIO_DOWN: UUID = UUID.fromString("8f4b8c1e-9d2a-4c5e-b6f0-1a2b3c4d5e03")
    val TEXT: UUID = UUID.fromString("8f4b8c1e-9d2a-4c5e-b6f0-1a2b3c4d5e04")
    val CTRL: UUID = UUID.fromString("8f4b8c1e-9d2a-4c5e-b6f0-1a2b3c4d5e05")

    /** Dialog opcodes, both directions (firmware `CTRL_*`). */
    const val LISTEN_START = 0x01
    const val LISTEN_STOP = 0x02
    const val ABORT = 0x03
    const val PING = 0x04
    const val PONG = 0x05
    const val STATE = 0x06

    /** Pairing + auth opcodes (firmware `CoPhone::CP_*`, v2). */
    const val CP_PAIR_HELLO = 0x13 // cube->phone: [ephem 32][cubeMac 6]
    const val CP_PAIR_REPLY = 0x14 // phone->cube: [ephem 32][phoneId 6]
    const val CP_PAIR_ACCEPT = 0x15 // cube->phone: [hmac 8]
    const val CP_PAIR_REJECT = 0x16 // cube->phone: empty
    const val CP_AUTH_CHAL = 0x17 // cube->phone: [nonce 16]
    const val CP_AUTH_RESP = 0x18 // phone->cube: [hmac 16][nonce 16]
    const val CP_AUTH_OK = 0x19 // cube->phone: [hmac 16]
}
