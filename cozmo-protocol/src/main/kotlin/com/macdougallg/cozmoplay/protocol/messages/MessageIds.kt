package com.macdougallg.cozmoplay.protocol.messages

/**
 * Cozmo protocol packet ID constants.
 *
 * These are the 1-byte ID fields carried inside COMMAND (0x04) and EVENT (0x05) packets.
 * Source: PyCozmo protocol_declaration.py + protocol.html packet ID table.
 *
 * Commands travel engine → robot inside an ENGINE_PACKETS frame (type 0x07).
 * Events  travel robot  → engine inside a ROBOT_PACKETS frame  (type 0x09).
 */
object CommandIds {

    // ── Init sequence (sent after handshake) ──────────────────────────────────
    const val ENABLE: Byte           = 0x25         //  0 bytes: enable robot subsystems (sent twice)
    const val SET_ORIGIN: Byte       = 0x45         // 24 bytes: world-frame origin (see MessageBuilder)
    const val SYNC_TIME: Byte        = 0x4b         //  8 bytes: timestamp sync — triggers RobotState streaming

    // ── Drive ─────────────────────────────────────────────────────────────────
    const val DRIVE_WHEELS: Byte     = 0x32         // 16 bytes: lspeed,rspeed,laccel,raccel (4×float)
    const val STOP_ALL_MOTORS: Byte  = 0x3b.toByte()//  0 bytes

    // ── Head ──────────────────────────────────────────────────────────────────
    const val DRIVE_HEAD: Byte       = 0x35         //  4 bytes: speed (float)
    const val SET_HEAD_ANGLE: Byte   = 0x37         // 17 bytes: angle,maxSpeed,accel,duration (4×float) + actionId (uint8)

    // ── Lift ──────────────────────────────────────────────────────────────────
    const val DRIVE_LIFT: Byte       = 0x34         //  4 bytes: speed (float)
    const val SET_LIFT_HEIGHT: Byte  = 0x36         // 17 bytes: height,maxSpeed,accel,duration (4×float) + actionId (uint8)

    // ── Camera ────────────────────────────────────────────────────────────────
    const val ENABLE_CAMERA: Byte    = 0x4c         //  2 bytes: imageSendMode (uint8), imageResolution (uint8)
}

object EventIds {
    // ── Streaming events (require SyncTime to be sent first) ──────────────────
    const val ROBOT_STATE: Byte      = 0xf0.toByte() // 91 bytes: full robot telemetry
    const val ANIMATION_STATE: Byte  = 0xf1.toByte() // 15 bytes
    const val IMAGE_CHUNK: Byte      = 0xf2.toByte() // 24–1172 bytes: camera frame chunk
    const val OBJECT_AVAILABLE: Byte = 0xf3.toByte() //  9 bytes: cube detected
    const val OBJECT_CONNECTION_STATE: Byte = 0xd0.toByte() // 13 bytes: cube connect/disconnect
    const val ACKNOWLEDGE_ACTION: Byte = 0xc4.toByte() //  1 byte: action complete

    // ── Initial-burst packets (robot → engine after RESET, type 0x04) ─────────
    const val HARDWARE_INFO: Byte      = 0xc9.toByte() //  6 bytes: CPU/hw revision info
    const val FIRMWARE_SIGNATURE: Byte = 0xee.toByte() // 449 bytes: firmware build signature
    const val DEBUG_DATA: Byte         = 0xb0.toByte() // variable: robot debug/diag data
    const val BODY_INFO: Byte          = 0xed.toByte() //  4 bytes: body revision (triggers init)
}
