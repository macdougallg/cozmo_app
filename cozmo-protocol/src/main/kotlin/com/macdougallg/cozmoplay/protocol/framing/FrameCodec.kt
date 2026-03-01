package com.macdougallg.cozmoplay.protocol.framing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes and decodes Cozmo UDP frame headers.
 *
 * Frame structure (all fields little-endian, per PyCozmo connection.py):
 *
 *   Offset  Size   Field
 *   0       4      Frame ID (uint32) — monotonically increasing sequence number
 *   4       4      Ack ID  (uint32)  — last frame ID received from the other side
 *   8       1      Message Count (uint8) — number of messages in this frame
 *   --- per message ---
 *   9       2      Message ID (uint16)
 *   11      2      Payload Length (uint16)
 *   13      N      Protobuf payload bytes
 */
object FrameCodec {

    // Frame header: frameId(4) + ackId(4) + msgCount(1) = 9 bytes
    const val FRAME_HEADER_SIZE = 9
    // Per-message header: msgId(2) + payloadLen(2) = 4 bytes
    const val MESSAGE_HEADER_SIZE = 4

    /**
     * Encodes a single-message frame ready to send via UDP.
     *
     * @param frameId Monotonically increasing sequence number managed by caller.
     * @param ackId Last frame ID received from the robot.
     * @param messageId The [com.macdougallg.cozmoplay.protocol.messages.MessageIds] constant.
     * @param payload Serialised protobuf bytes (may be empty for messages with no fields).
     */
    fun encode(
        frameId: UInt,
        ackId: UInt,
        messageId: UShort,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val totalSize = FRAME_HEADER_SIZE + MESSAGE_HEADER_SIZE + payload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Frame header
        buffer.putInt(frameId.toInt())
        buffer.putInt(ackId.toInt())
        buffer.put(1.toByte()) // message count — always 1 per frame in our implementation

        // Message header
        buffer.putShort(messageId.toShort())
        buffer.putShort(payload.size.toShort())

        // Payload
        if (payload.isNotEmpty()) buffer.put(payload)

        return buffer.array()
    }

    /**
     * Decodes all messages from a received UDP frame.
     *
     * @param data Raw bytes received from the robot.
     * @return [DecodeResult] containing frame metadata and list of decoded messages,
     *         or null if the frame is malformed.
     */
    fun decode(data: ByteArray): DecodeResult? {
        if (data.size < FRAME_HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val frameId = buffer.int.toUInt()
        val ackId = buffer.int.toUInt()
        val messageCount = buffer.get().toInt() and 0xFF

        val messages = mutableListOf<IncomingMessage>()

        repeat(messageCount) {
            if (buffer.remaining() < MESSAGE_HEADER_SIZE) return@repeat
            val messageId = buffer.short.toUShort()
            val payloadLen = buffer.short.toInt() and 0xFFFF

            if (buffer.remaining() < payloadLen) return@repeat
            val payload = ByteArray(payloadLen)
            buffer.get(payload)

            messages.add(IncomingMessage(messageId, payload))
        }

        return DecodeResult(frameId, ackId, messages)
    }
}

data class DecodeResult(
    val frameId: UInt,
    val ackId: UInt,
    val messages: List<IncomingMessage>,
)

data class IncomingMessage(
    val messageId: UShort,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingMessage) return false
        return messageId == other.messageId && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int = 31 * messageId.hashCode() + payload.contentHashCode()
}
