package com.macdougallg.cozmoplay.protocol.messages

/**
 * Cozmo protocol message ID constants.
 *
 * Derived from PyCozmo clad/robot/messaging.py and associated .clad definition files.
 * All agents MUST verify these against PyCozmo source before sending to real hardware.
 *
 * Reference: https://github.com/zayfod/pycozmo
 */
object MessageIds {

    // ── Outbound: App → Robot ─────────────────────────────────────────────────

    const val CONNECT: UShort                = 0x0001u
    const val DISCONNECT: UShort             = 0x0002u
    const val PING: UShort                   = 0x0003u

    const val DRIVE_WHEELS: UShort           = 0x0031u
    const val TURN_IN_PLACE: UShort          = 0x0032u
    const val DRIVE_ARC: UShort              = 0x0033u
    const val STOP_ALL_MOTORS: UShort        = 0x003Bu

    const val MOVE_HEAD: UShort              = 0x0037u
    const val SET_HEAD_ANGLE: UShort         = 0x0038u
    const val MOVE_LIFT: UShort              = 0x0039u
    const val SET_LIFT_HEIGHT: UShort        = 0x003Au

    const val PLAY_ANIMATION: UShort         = 0x00B0u
    const val PLAY_ANIMATION_GROUP: UShort   = 0x00B1u

    const val SET_CUBE_LIGHTS: UShort        = 0x00D0u
    const val PICKUP_OBJECT: UShort          = 0x00A0u
    const val PLACE_OBJECT: UShort           = 0x00A1u
    const val ROLL_OBJECT: UShort            = 0x00A3u

    const val GO_TO_POSE: UShort             = 0x00A5u
    const val ENABLE_FREEPLAY: UShort        = 0x0120u

    const val SET_CAMERA_PARAMS: UShort      = 0x0070u
    const val ENABLE_CAMERA: UShort          = 0x0071u

    // ── Inbound: Robot → App ──────────────────────────────────────────────────

    const val CONNECTED: UShort              = 0x0005u
    const val PONG: UShort                   = 0x0006u

    const val ROBOT_STATE: UShort            = 0x00F0u

    const val ROBOT_OBSERVED_OBJECT: UShort  = 0x00C0u
    const val ROBOT_PICKED_UP_OBJECT: UShort = 0x00C1u
    const val ROBOT_PLACED_OBJECT: UShort    = 0x00C2u

    const val ANIMATION_COMPLETED: UShort    = 0x00B5u
    const val ROBOT_COMPLETED_ACTION: UShort = 0x00A8u

    const val CAMERA_IMAGE: UShort           = 0x0080u

    // Face events (v2 — parsed but not acted upon in v1)
    const val ROBOT_OBSERVED_FACE: UShort    = 0x0111u
}
