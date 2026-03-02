package com.macdougallg.cozmoplay.protocol

import app.cash.turbine.test
import com.macdougallg.cozmoplay.protocol.framing.FrameCodec
import com.macdougallg.cozmoplay.protocol.messages.CommandIds
import com.macdougallg.cozmoplay.protocol.messages.MessageBuilder
import com.macdougallg.cozmoplay.types.*
import com.macdougallg.cozmoplay.wifi.MockCozmoWifi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameCodecTest {

    @Test
    fun `encodeReset produces 14-byte frame with correct magic and type`() {
        val frame = FrameCodec.encodeReset()
        assertEquals(FrameCodec.HEADER_SIZE, frame.size)
        // Magic: "COZ\x03RE\x01"
        assertEquals(0x43.toByte(), frame[0])
        assertEquals(0x4F.toByte(), frame[1])
        assertEquals(0x5A.toByte(), frame[2])
        assertEquals(0x03.toByte(), frame[3])
        assertEquals(0x52.toByte(), frame[4])
        assertEquals(0x45.toByte(), frame[5])
        assertEquals(0x01.toByte(), frame[6])
        assertEquals(FrameCodec.FRAME_RESET, frame[7])
    }

    @Test
    fun `encodeCommand decode round-trip preserves id and payload`() {
        val payload = MessageBuilder.driveWheels(100f, -50f)
        val frame = FrameCodec.encodeCommand(CommandIds.DRIVE_WHEELS, payload, seq = 1, ack = 0)
        val decoded = FrameCodec.decode(frame)
        assertNotNull(decoded)
        assertEquals(FrameCodec.FRAME_ENGINE_PACKETS, decoded!!.frameType)
        assertEquals(1, decoded.packets.size)
        val pkt = decoded.packets[0]
        assertEquals(FrameCodec.PACKET_COMMAND, pkt.type)
        assertEquals(CommandIds.DRIVE_WHEELS, pkt.id)
        assertTrue(pkt.payload.contentEquals(payload))
    }

    @Test
    fun `encodePing decode round-trip preserves frame type`() {
        val pingPayload = MessageBuilder.ping(1u)
        val frame = FrameCodec.encodePing(pingPayload, ack = 0)
        val decoded = FrameCodec.decode(frame)
        assertNotNull(decoded)
        assertEquals(FrameCodec.FRAME_OOB_PING, decoded!!.frameType)
        assertEquals(1, decoded.packets.size)
        assertEquals(FrameCodec.PACKET_PING, decoded.packets[0].type)
    }

    @Test
    fun `decode returns null for frame too short`() {
        assertNull(FrameCodec.decode(ByteArray(5)))
    }

    @Test
    fun `decode returns null for empty frame`() {
        assertNull(FrameCodec.decode(ByteArray(0)))
    }

    @Test
    fun `sequence encoding — OOB_SEQ ack encodes as wire value 0`() {
        // encodeReset: firstSeq=0, seq=0, ack=OOB_SEQ(0xffff)
        // ack wire = (0xffff + 1) % 0x10000 = 0
        val frame = FrameCodec.encodeReset()
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(8) // skip magic(7) + type(1)
        val firstSeqWire = buf.short.toInt() and 0xffff
        val seqWire      = buf.short.toInt() and 0xffff
        val ackWire      = buf.short.toInt() and 0xffff
        assertEquals(1, firstSeqWire) // (0+1)%0x10000 = 1
        assertEquals(1, seqWire)
        assertEquals(0, ackWire)      // (0xffff+1)%0x10000 = 0
    }
}

class MessageBuilderTest {

    @Test
    fun `driveWheels clamps values to valid range`() {
        val payload = MessageBuilder.driveWheels(300f, -300f)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(220f, buf.float, 0.01f)
        assertEquals(-220f, buf.float, 0.01f)
    }

    @Test
    fun `setHeadAngle clamps to valid range`() {
        val payload = MessageBuilder.setHeadAngle(5f) // above max 0.78
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.78f, buf.float, 0.01f)
    }

    @Test
    fun `setLiftHeight clamps to valid range`() {
        val payload = MessageBuilder.setLiftHeight(0f) // below min 32
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32f, buf.float, 0.01f)
    }

    @Test
    fun `stopAllMotors produces empty payload`() {
        assertEquals(0, MessageBuilder.stopAllMotors().size)
    }

    @Test
    fun `enableCamera true encodes stream mode and 320x240 resolution`() {
        val payload = MessageBuilder.enableCamera(true)
        assertEquals(2, payload.size)
        assertEquals(2.toByte(), payload[0]) // mode 2 = stream
        assertEquals(0.toByte(), payload[1]) // resolution 0 = 320x240
    }

    @Test
    fun `enableCamera false encodes off mode`() {
        val payload = MessageBuilder.enableCamera(false)
        assertEquals(2, payload.size)
        assertEquals(0.toByte(), payload[0]) // mode 0 = off
    }

    @Test
    fun `ping produces 17 bytes`() {
        val payload = MessageBuilder.ping(42u)
        assertEquals(17, payload.size)
    }
}

class MockCozmoProtocolTest {

    private lateinit var protocol: MockCozmoProtocol

    @Before
    fun setUp() {
        protocol = MockCozmoProtocol()
    }

    @Test
    fun `initial state is Idle`() {
        assertTrue(protocol.protocolState.value is ProtocolState.Idle)
    }

    @Test
    fun `connect transitions to Connected`() = runTest {
        val wifi = MockCozmoWifi().apply { simulateConnected() }
        protocol.connect(wifi)
        assertTrue(protocol.protocolState.value is ProtocolState.Connected)
    }

    @Test
    fun `driveWheels records command`() {
        protocol.simulateConnected()
        protocol.driveWheels(100f, -100f)
        assertEquals(1, protocol.driveCommands.size)
        assertEquals(100f, protocol.driveCommands[0].first, 0.01f)
        assertEquals(-100f, protocol.driveCommands[0].second, 0.01f)
    }

    @Test
    fun `stopAllMotors increments counter`() {
        protocol.stopAllMotors()
        assertEquals(1, protocol.stopAllMotorsCallCount)
    }

    @Test
    fun `playAnimation records name and returns result`() = runTest {
        protocol.nextActionResult = ActionResult.Success
        val result = protocol.playAnimation("anim_happy_01")
        assertEquals("anim_happy_01", protocol.lastAnimationPlayed)
        assertTrue(result is ActionResult.Success)
    }

    @Test
    fun `simulateRobotState updates state flow`() = runTest {
        val state = RobotState(poseX = 100f, emotion = Emotion.HAPPY)
        protocol.robotState.test {
            awaitItem() // initial
            protocol.simulateRobotState(state)
            val received = awaitItem()
            assertEquals(100f, received.poseX, 0.01f)
            assertEquals(Emotion.HAPPY, received.emotion)
        }
    }

    @Test
    fun `simulateCubeDetected adds cube to list`() = runTest {
        val cube = CubeState(
            cubeId = 1, objectId = 101, isVisible = true,
            isBeingCarried = false,
            lightState = CubeLightState(0xFFFFFF, false, 500, 500),
            signalStrength = 0.9f, lastSeenMs = System.currentTimeMillis(),
        )
        protocol.cubeStates.test {
            awaitItem() // empty initial
            protocol.simulateCubeDetected(cube)
            val cubes = awaitItem()
            assertEquals(1, cubes.size)
            assertEquals(1, cubes[0].cubeId)
        }
    }

    @Test
    fun `enableCamera records state`() {
        protocol.enableCamera(true)
        assertTrue(protocol.cameraEnabled)
        protocol.enableCamera(false)
        assertFalse(protocol.cameraEnabled)
    }

    @Test
    fun `enableFreeplay records state`() {
        protocol.enableFreeplay(true)
        assertTrue(protocol.freeplayEnabled)
    }

    @Test
    fun `reset clears all recorded state`() = runTest {
        protocol.simulateConnected()
        protocol.driveWheels(10f, 10f)
        protocol.enableCamera(true)
        protocol.reset()
        assertTrue(protocol.protocolState.value is ProtocolState.Idle)
        assertTrue(protocol.driveCommands.isEmpty())
        assertFalse(protocol.cameraEnabled)
    }

    @Test
    fun `setCubeLights records objectId and config`() {
        val lights = listOf(CubeLightConfig(color = 0xFF0000))
        protocol.setCubeLights(101, lights)
        assertEquals(101, protocol.lastCubeLightSet?.first)
        assertEquals(lights, protocol.lastCubeLightSet?.second)
    }

    @Test
    fun `availableAnimations has minimum 20 entries`() {
        assertTrue(protocol.availableAnimations.size >= 20)
    }
}
