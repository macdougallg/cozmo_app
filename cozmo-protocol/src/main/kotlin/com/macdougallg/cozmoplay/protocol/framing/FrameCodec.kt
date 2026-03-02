package com.macdougallg.cozmoplay.protocol.framing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes and decodes Cozmo UDP frames.
 *
 * Frame structure (all fields little-endian, per PyCozmo frame.py):
 *
 *   Offset  Size   Field
 *   0       7      Magic: "COZ\x03RE\x01"
 *   7       1      Frame type (FrameType)
 *   8       2      first_seq: first packet seq in frame, or 0 (wire = actual+1 mod 0x10000)
 *   10      2      seq:       last  packet seq in frame, or 0 (wire = actual+1 mod 0x10000)
 *   12      2      ack:       peer's last seq acknowledged  (wire = actual+1 mod 0x10000)
 *   14      N      Packet data (0 or more packets)
 *
 * Per-packet structure:
 *   0       1      Packet type (PacketType)
 *   1       2      Length (includes id byte for COMMAND/EVENT packets)
 *   3       1      Packet ID (only for COMMAND=0x04 or EVENT=0x05 packets)
 *   4/3     N      Payload bytes
 *
 * Sequence encoding: wire_value = (actual + 1) % 0x10000
 * OOB_SEQ (0xffff) encodes as wire value 0.
 */
object FrameCodec {

    // ── Constants ─────────────────────────────────────────────────────────────

    val MAGIC = byteArrayOf(0x43, 0x4F, 0x5A, 0x03, 0x52, 0x45, 0x01) // "COZ\x03RE\x01"
    const val HEADER_SIZE = 14 // 7 magic + 1 type + 2 first_seq + 2 seq + 2 ack
    const val OOB_SEQ = 0xffff // Out-of-band sequence number (pings, reset ack)

    // Frame types (source: PyCozmo frame.py FrameType enum)
    const val FRAME_RESET: Byte            = 0x01         // engine → robot: initiate connection
    const val FRAME_RESET_ACK: Byte        = 0x02         // robot  → engine: reset acknowledged
    const val FRAME_DISCONNECT: Byte       = 0x03         // engine → robot: clean shutdown
    const val FRAME_ENGINE_SINGLE: Byte    = 0x04         // engine → robot: single packet
    const val FRAME_ENGINE_PACKETS: Byte   = 0x07         // engine → robot: one or more packets
    const val FRAME_ROBOT_PACKETS: Byte    = 0x09.toByte()// robot  → engine: one or more packets
    const val FRAME_OOB_PING: Byte         = 0x0b         // engine → robot: out-of-band ping

    // Packet types (source: PyCozmo frame.py PacketType enum)
    const val PACKET_CONNECT: Byte     = 0x02         // robot  → engine: sent after reset
    const val PACKET_DISCONNECT: Byte  = 0x03         // engine → robot
    const val PACKET_COMMAND: Byte     = 0x04         // engine → robot: has id byte
    const val PACKET_EVENT: Byte       = 0x05         // robot  → engine: has id byte
    const val PACKET_PING: Byte        = 0x0b         // engine → robot: OOB keepalive

    // ── Encoding ──────────────────────────────────────────────────────────────

    /**
     * Encodes the 14-byte frame header + any pre-encoded packet bytes.
     *
     * Seq values are stored on wire as (actual + 1) % 0x10000.
     * Pass [OOB_SEQ] for out-of-band frames (ping, reset ack).
     */
    fun encodeFrame(
        type: Byte,
        firstSeq: Int,
        seq: Int,
        ack: Int,
        packetBytes: ByteArray = ByteArray(0),
    ): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + packetBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.put(type)
        buf.putShort(((firstSeq + 1) % 0x10000).toShort())
        buf.putShort(((seq + 1) % 0x10000).toShort())
        buf.putShort(((ack + 1) % 0x10000).toShort())
        if (packetBytes.isNotEmpty()) buf.put(packetBytes)
        return buf.array()
    }

    /** Bare Reset frame — no packets. Ack set to OOB_SEQ (wire = 0). */
    fun encodeReset(): ByteArray = encodeFrame(FRAME_RESET, 0, 0, OOB_SEQ)

    /** Engine packets frame containing a single Command packet. */
    fun encodeCommand(commandId: Byte, payload: ByteArray, seq: Int, ack: Int): ByteArray {
        val packet = encodePacket(PACKET_COMMAND, commandId, payload)
        return encodeFrame(FRAME_ENGINE_PACKETS, seq, seq, ack, packet)
    }

    /** OOB Ping frame containing a Ping packet. */
    fun encodePing(pingPayload: ByteArray, ack: Int): ByteArray {
        val packet = encodePacket(PACKET_PING, null, pingPayload)
        return encodeFrame(FRAME_OOB_PING, OOB_SEQ, OOB_SEQ, ack, packet)
    }

    /**
     * Encodes a single packet. For COMMAND and EVENT packets the id byte is
     * included and the length field covers (id + payload). For all other types
     * the id is omitted and length covers payload only.
     */
    fun encodePacket(type: Byte, id: Byte?, payload: ByteArray): ByteArray {
        val hasId = (type == PACKET_COMMAND || type == PACKET_EVENT)
        val length = payload.size + (if (hasId) 1 else 0)
        val buf = ByteBuffer.allocate(3 + (if (hasId) 1 else 0) + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(type)
        buf.putShort(length.toShort())
        if (hasId && id != null) buf.put(id)
        if (payload.isNotEmpty()) buf.put(payload)
        return buf.array()
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    /**
     * Decodes a raw UDP datagram.
     *
     * @return [DecodeResult] on success, null if the frame is too short or
     *         doesn't start with the Cozmo magic bytes.
     */
    fun decode(data: ByteArray): DecodeResult? {
        if (data.size < HEADER_SIZE) return null
        // Validate magic
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) return null
        }

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(MAGIC.size) // skip magic

        val frameType = buf.get()
        val firstSeqWire = buf.short.toInt() and 0xffff
        val seqWire      = buf.short.toInt() and 0xffff
        val ackWire      = buf.short.toInt() and 0xffff

        // Decode: actual = (wire - 1 + 0x10000) % 0x10000
        val firstSeq = (firstSeqWire - 1 + 0x10000) % 0x10000
        val seq      = (seqWire      - 1 + 0x10000) % 0x10000
        val ack      = (ackWire      - 1 + 0x10000) % 0x10000

        val packets = mutableListOf<IncomingPacket>()
        while (buf.remaining() >= 3) { // type(1) + length(2) minimum
            val pktType = buf.get()
            val length  = buf.short.toInt() and 0xffff
            if (buf.remaining() < length) break

            val hasId = (pktType == PACKET_COMMAND || pktType == PACKET_EVENT)
            val id: Byte? = if (hasId && length >= 1) buf.get() else null
            val payloadSize = length - (if (hasId) 1 else 0)

            val payload = if (payloadSize > 0) {
                ByteArray(payloadSize).also { buf.get(it) }
            } else {
                ByteArray(0)
            }
            packets.add(IncomingPacket(pktType, id, payload))
        }

        return DecodeResult(frameType, firstSeq, seq, ack, packets)
    }
}

data class DecodeResult(
    val frameType: Byte,
    val firstSeq: Int,
    val seq: Int,
    val ack: Int,
    val packets: List<IncomingPacket>,
)

data class IncomingPacket(
    val type: Byte,
    val id: Byte?,           // present only for COMMAND and EVENT packets
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingPacket) return false
        return type == other.type && id == other.id && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
