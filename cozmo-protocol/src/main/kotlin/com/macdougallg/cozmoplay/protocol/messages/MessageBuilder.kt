package com.macdougallg.cozmoplay.protocol.messages

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds payload bytes for each outbound Cozmo command.
 *
 * These payloads are wrapped by [com.macdougallg.cozmoplay.protocol.framing.FrameCodec.encodeCommand]
 * or [encodePacket] — the command ID byte is NOT included here (it's added by the codec).
 *
 * All fields are little-endian, matching PyCozmo protocol_encoder.py.
 * Source: PyCozmo protocol_declaration.py field definitions.
 */
object MessageBuilder {

    // ── Drive ─────────────────────────────────────────────────────────────────

    /**
     * DriveWheels — 16 bytes.
     * lspeed, rspeed, laccel, raccel (all float32, mm/s or mm/s²).
     */
    fun driveWheels(leftMmps: Float, rightMmps: Float): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(leftMmps.coerceIn(-220f, 220f))
        buf.putFloat(rightMmps.coerceIn(-220f, 220f))
        buf.putFloat(0f) // lwheel_accel — 0 = immediate
        buf.putFloat(0f) // rwheel_accel
        return buf.array()
    }

    /** StopAllMotors — 0 bytes. */
    fun stopAllMotors(): ByteArray = ByteArray(0)

    // ── Head ──────────────────────────────────────────────────────────────────

    /**
     * DriveHead — 4 bytes.
     * speed_rad_per_sec (float32). Pass 0f to stop.
     */
    fun driveHead(speedRadPerSec: Float): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(speedRadPerSec)
        return buf.array()
    }

    /**
     * SetHeadAngle — 17 bytes.
     * angle_rad, max_speed, accel, duration (all float32) + action_id (uint8).
     */
    fun setHeadAngle(angleRad: Float, actionId: Int = 0): ByteArray {
        val buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(angleRad.coerceIn(-0.44f, 0.78f))
        buf.putFloat(10f)            // max_speed_rad_per_sec
        buf.putFloat(10f)            // accel_rad_per_sec2
        buf.putFloat(0f)             // duration_sec (0 = use speed/accel)
        buf.put(actionId.toByte())
        return buf.array()
    }

    // ── Lift ──────────────────────────────────────────────────────────────────

    /**
     * DriveLift — 4 bytes.
     * speed_rad_per_sec (float32). Pass 0f to stop.
     */
    fun driveLift(speedRadPerSec: Float): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(speedRadPerSec)
        return buf.array()
    }

    /**
     * SetLiftHeight — 17 bytes.
     * height_mm, max_speed, accel, duration (all float32) + action_id (uint8).
     */
    fun setLiftHeight(heightMm: Float, actionId: Int = 0): ByteArray {
        val buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(heightMm.coerceIn(32f, 92f))
        buf.putFloat(10f)            // max_speed_rad_per_sec
        buf.putFloat(10f)            // accel_rad_per_sec2
        buf.putFloat(0f)             // duration_sec
        buf.put(actionId.toByte())
        return buf.array()
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    /**
     * EnableCamera — 2 bytes.
     * imageSendMode (uint8): 0=Off, 1=Stream, 2=SingleShot  (IMAGE_SEND_MODE enum)
     * imageResolution (uint8): 4=QVGA 320x240               (IMAGE_RESOLUTION enum)
     */
    fun enableCamera(enabled: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(if (enabled) 1.toByte() else 0.toByte()) // mode: 1=Stream, 0=Off
        buf.put(4.toByte())                               // resolution: 4=QVGA (320x240)
        return buf.array()
    }

    /** EnableColorImages — 1 byte. 1=color (enc=9), 0=grayscale (enc=8). */
    fun enableColorImages(enabled: Boolean): ByteArray =
        byteArrayOf(if (enabled) 1 else 0)

    /** SetHeadLight — 1 byte. Toggles Cozmo's forehead LED for night vision. */
    fun setHeadLight(enabled: Boolean): ByteArray =
        byteArrayOf(if (enabled) 1 else 0)

    /**
     * SetCameraParams — 7 bytes.
     * gain (float32), exposure_ms (uint16), auto_exposure_enabled (bool)
     */
    fun setCameraParams(gain: Float, exposureMs: Int, autoExposure: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(gain)
        buf.putShort(exposureMs.toShort())
        buf.put(if (autoExposure) 1 else 0)
        return buf.array()
    }

    // ── Init sequence ─────────────────────────────────────────────────────────

    /** Enable — 0 bytes. Sent twice after FirmwareSignature to trigger BodyInfo. */
    fun enable(): ByteArray = ByteArray(0)

    /**
     * SetOrigin — 24 bytes. Sets world-frame origin to (0,0,0).
     * Fields: unknown0(uint32), pose_frame_id(uint32), pose_origin_id(uint32=1),
     *         pose_x(float32), pose_y(float32), unknown5(uint32=0x80000000).
     */
    fun setOrigin(): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)                      // unknown0
        buf.putInt(0)                      // pose_frame_id
        buf.putInt(1)                      // pose_origin_id (default = 1)
        buf.putFloat(0f)                   // pose_x
        buf.putFloat(0f)                   // pose_y
        buf.putInt(-0x80000000)            // unknown5 (0x80000000)
        return buf.array()
    }

    /**
     * SyncTime — 8 bytes. Triggers RobotState streaming once robot acknowledges.
     * Fields: timestamp(uint32=0), unknown(uint32=0).
     */
    fun syncTime(): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)   // timestamp
        buf.putInt(0)   // unknown
        return buf.array()
    }

    // ── Keepalive ─────────────────────────────────────────────────────────────

    /**
     * Ping packet payload — 17 bytes.
     * time_sent_ms (double/8), counter (uint32/4), last (uint32/4), unknown (uint8/1).
     */
    fun ping(counter: UInt, timeSentMs: Double = System.currentTimeMillis().toDouble()): ByteArray {
        val buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        buf.putDouble(timeSentMs)
        buf.putInt(counter.toInt())
        buf.putInt(0)   // last
        buf.put(0)      // unknown
        return buf.array()
    }
}
