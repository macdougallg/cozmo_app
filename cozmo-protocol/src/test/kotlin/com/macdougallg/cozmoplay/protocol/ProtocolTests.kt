package com.macdougallg.cozmoplay.protocol

import app.cash.turbine.test
import com.macdougallg.cozmoplay.protocol.framing.FrameCodec
import com.macdougallg.cozmoplay.protocol.messages.MessageBuilder
import com.macdougallg.cozmoplay.protocol.messages.MessageIds
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
    fun `encode produces correct frame header`() {
        val frame = FrameCodec.encode(
            frameId = 1u,
            ackId = 0u,
            messageId = MessageIds.CONNECT,
            payload = ByteArray(0),
        )
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, buf.int)           // frameId
        assertEquals(0, buf.int)           // ackId
        assertEquals(1, buf.get().toInt()) // message count
        assertEquals(MessageIds.CONNECT.toShort(), buf.short) // messageId
        assertEquals(0, buf.short.toInt()) // payload length
    }

    @Test
    fun `decode round-trips a CONNECT frame`() {
        val payload = MessageBuilder.connect()
        val frame = FrameCodec.encode(42u, 7u, MessageIds.CONNECT, payload)
        val result = FrameCodec.decode(frame)
        assertNotNull(result)
        assertEquals(42u, result!!.frameId)
        assertEquals(7u, result.ackId)
        assertEquals(1, result.messages.size)
        assertEquals(MessageIds.CONNECT, result.messages[0].messageId)
        assertTrue(result.messages[0].payload.contentEquals(payload))
    }

    @Test
    fun `decode returns null for frame too short`() {
        val result = FrameCodec.decode(ByteArray(5))
        assertNull(result)
    }

    @Test
    fun `decode handles empty frame gracefully`() {
        val result = FrameCodec.decode(ByteArray(0))
        assertNull(result)
    }

    @Test
    fun `encode is little-endian`() {
        val frame = FrameCodec.encode(0x01020304u, 0u, 0x0001u, ByteArray(0))
        // First byte of frameId should be 0x04 in little-endian
        assertEquals(0x04.toByte(), frame[0])
        assertEquals(0x03.toByte(), frame[1])
        assertEquals(0x02.toByte(), frame[2])
        assertEquals(0x01.toByte(), frame[3])
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
        val payload = MessageBuilder.setHeadAngle(5f) // way over max 0.78
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
    fun `playAnimation encodes name as null-terminated string`() {
        val name = "anim_happy_01"
        val payload = MessageBuilder.playAnimation(name)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        assertEquals(nameBytes.size + 1, payload.size)
        assertEquals(0.toByte(), payload.last()) // null terminator
        assertTrue(payload.copyOf(nameBytes.size).contentEquals(nameBytes))
    }

    @Test
    fun `disconnect produces empty payload`() {
        assertEquals(0, MessageBuilder.disconnect().size)
    }

    @Test
    fun `stopAllMotors produces empty payload`() {
        assertEquals(0, MessageBuilder.stopAllMotors().size)
    }

    @Test
    fun `enableCamera true encodes 1`() {
        val payload = MessageBuilder.enableCamera(true)
        assertEquals(1, payload.size)
        assertEquals(1.toByte(), payload[0])
    }

    @Test
    fun `enableCamera false encodes 0`() {
        val payload = MessageBuilder.enableCamera(false)
        assertEquals(0.toByte(), payload[0])
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
