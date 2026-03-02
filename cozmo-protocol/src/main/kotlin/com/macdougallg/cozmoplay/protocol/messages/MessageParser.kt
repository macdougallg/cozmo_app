package com.macdougallg.cozmoplay.protocol.messages

import android.util.Log
import com.macdougallg.cozmoplay.protocol.framing.FrameCodec
import com.macdougallg.cozmoplay.protocol.framing.IncomingPacket
import com.macdougallg.cozmoplay.types.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses raw inbound packets into domain objects.
 *
 * Each parse function corresponds to a robot → app packet type.
 * Unknown or malformed packets are logged and return null — they never crash the receive loop.
 *
 * Reference: PyCozmo protocol_declaration.py field definitions.
 */
object MessageParser {

    private const val TAG = "MessageParser"

    /**
     * Dispatch an incoming packet to the correct parser.
     * Returns a [ParsedMessage] or null if the packet is unknown/malformed.
     */
    fun parse(pkt: IncomingPacket): ParsedMessage? {
        return try {
            when (pkt.type) {
                FrameCodec.PACKET_CONNECT -> parseConnected(pkt.payload)
                FrameCodec.PACKET_EVENT -> {
                    val id = pkt.id ?: return null
                    when (id) {
                        EventIds.ROBOT_STATE        -> parseRobotState(pkt.payload)
                        EventIds.IMAGE_CHUNK        -> parseCameraImage(pkt.payload)
                        EventIds.ANIMATION_STATE    -> parseAnimationCompleted(pkt.payload)
                        EventIds.OBJECT_AVAILABLE   -> parseObservedObject(pkt.payload)
                        EventIds.ACKNOWLEDGE_ACTION -> parseCompletedAction(pkt.payload)
                        else -> {
                            Log.v(TAG, "Unknown event ID: 0x${id.toInt().and(0xff).toString(16)}")
                            null
                        }
                    }
                }
                else -> {
                    Log.v(TAG, "Unknown packet type: 0x${pkt.type.toInt().and(0xff).toString(16)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse packet type 0x${pkt.type.toInt().and(0xff).toString(16)}: ${e.message}")
            null
        }
    }

    // ── Parser Functions ──────────────────────────────────────────────────────

    private fun parseConnected(payload: ByteArray): ParsedMessage.Connected {
        // version(2) + robot_id(4) — may be empty on some firmware versions
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val version = if (buf.remaining() >= 2) buf.short.toInt() else 0
        val robotId = if (buf.remaining() >= 4) buf.int else 0
        return ParsedMessage.Connected(version, robotId)
    }

    private fun parseRobotState(payload: ByteArray): ParsedMessage.RobotStateMsg {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        // Field order per PyCozmo RobotState event definition
        val poseX        = if (buf.remaining() >= 4) buf.float else 0f
        val poseY        = if (buf.remaining() >= 4) buf.float else 0f
        val poseAngle    = if (buf.remaining() >= 4) buf.float else 0f
        val headAngle    = if (buf.remaining() >= 4) buf.float else 0f
        val liftHeight   = if (buf.remaining() >= 4) buf.float else 0f
        val battery      = if (buf.remaining() >= 4) buf.float else 0f
        val leftSpeed    = if (buf.remaining() >= 4) buf.float else 0f
        val rightSpeed   = if (buf.remaining() >= 4) buf.float else 0f
        val flags        = if (buf.remaining() >= 4) buf.int else 0

        val isCarryingBlock    = (flags and 0x01) != 0
        val isPickingOrPlacing = (flags and 0x02) != 0
        val isMoving           = (flags and 0x04) != 0
        val isAnimating        = (flags and 0x08) != 0
        val emotionBits        = (flags shr 8) and 0xFF
        val emotion            = emotionFromBits(emotionBits)

        return ParsedMessage.RobotStateMsg(
            RobotState(
                poseX = poseX,
                poseY = poseY,
                poseAngle = poseAngle,
                headAngle = headAngle,
                liftHeight = liftHeight,
                batteryVoltage = battery,
                isCarryingBlock = isCarryingBlock,
                isPickingOrPlacing = isPickingOrPlacing,
                isMoving = isMoving,
                isAnimating = isAnimating,
                leftWheelSpeed = leftSpeed,
                rightWheelSpeed = rightSpeed,
                emotion = emotion,
            )
        )
    }

    private fun parseObservedObject(payload: ByteArray): ParsedMessage.ObservedObject {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val objectId  = if (buf.remaining() >= 4) buf.int else 0
        val cubeId    = if (buf.remaining() >= 4) buf.int else objectId
        val isVisible = if (buf.remaining() >= 1) buf.get().toInt() != 0 else true
        val signalStr = if (buf.remaining() >= 4) buf.float else 1f
        return ParsedMessage.ObservedObject(
            objectId = objectId,
            cubeId = cubeId,
            isVisible = isVisible,
            signalStrength = signalStr,
            lastSeenMs = System.currentTimeMillis(),
        )
    }

    private fun parseAnimationCompleted(payload: ByteArray): ParsedMessage.AnimationCompleted {
        val nullIdx = payload.indexOf(0)
        val nameBytes = if (nullIdx > 0) payload.copyOf(nullIdx) else payload
        val name = String(nameBytes, Charsets.UTF_8)
        val resultByte = if (nullIdx >= 0 && nullIdx + 1 < payload.size)
            payload[nullIdx + 1].toInt() else 0
        val result = if (resultByte == 0) ActionResult.Success
                     else ActionResult.Failure("Animation result code: $resultByte")
        return ParsedMessage.AnimationCompleted(name, result)
    }

    private fun parseCompletedAction(payload: ByteArray): ParsedMessage.CompletedAction {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val actionId   = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else -1
        val resultCode = if (buf.remaining() >= 1) buf.get().toInt() else 0
        val result = when (resultCode) {
            0    -> ActionResult.Success
            else -> ActionResult.Failure("Action failed (code $resultCode)")
        }
        return ParsedMessage.CompletedAction(actionId, result)
    }

    private fun parseCameraImage(payload: ByteArray): ParsedMessage.CameraImage {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val imageId  = if (buf.remaining() >= 4) buf.int else 0
        val width    = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else 320
        val height   = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else 240
        val jpegData = if (buf.remaining() > 0) {
            val remaining = buf.remaining()
            ByteArray(remaining).also { buf.get(it) }
        } else ByteArray(0)
        return ParsedMessage.CameraImage(imageId, width, height, jpegData)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun emotionFromBits(bits: Int): Emotion = when (bits) {
        1    -> Emotion.HAPPY
        2    -> Emotion.EXCITED
        3    -> Emotion.ANGRY
        4    -> Emotion.SAD
        5    -> Emotion.SURPRISED
        6    -> Emotion.BORED
        7    -> Emotion.SCARED
        else -> Emotion.NEUTRAL
    }
}

// ── Parsed message sealed class ───────────────────────────────────────────────

sealed class ParsedMessage {
    data class Connected(val version: Int, val robotId: Int) : ParsedMessage()
    data class RobotStateMsg(val state: RobotState) : ParsedMessage()
    data class ObservedObject(
        val objectId: Int,
        val cubeId: Int,
        val isVisible: Boolean,
        val signalStrength: Float,
        val lastSeenMs: Long,
    ) : ParsedMessage()
    data class AnimationCompleted(val name: String, val result: ActionResult) : ParsedMessage()
    data class CompletedAction(val actionId: Int, val result: ActionResult) : ParsedMessage()
    data class CameraImage(
        val imageId: Int,
        val width: Int,
        val height: Int,
        val jpegData: ByteArray,
    ) : ParsedMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CameraImage) return false
            return imageId == other.imageId && jpegData.contentEquals(other.jpegData)
        }
        override fun hashCode(): Int = 31 * imageId + jpegData.contentHashCode()
    }
}
