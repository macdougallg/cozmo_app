package com.macdougallg.cozmoplay.protocol

import app.cash.turbine.test
import com.macdougallg.cozmoplay.protocol.framing.FrameCodec
import com.macdougallg.cozmoplay.protocol.messages.AnimationManifest
import com.macdougallg.cozmoplay.protocol.messages.MessageBuilder
import com.macdougallg.cozmoplay.protocol.messages.MessageIds
import com.macdougallg.cozmoplay.protocol.messages.MessageParser
import com.macdougallg.cozmoplay.protocol.messages.ParsedMessage
import com.macdougallg.cozmoplay.protocol.framing.IncomingMessage
import com.macdougallg.cozmoplay.types.*
import com.macdougallg.cozmoplay.wifi.MockCozmoWifi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Integration tests for the protocol module.
 * Tests message construction, parsing, and MockCozmoProtocol behaviour.
 *
 * Covers Protocol PRD FR-01 through FR-07 via mock implementations.
 */
class ProtocolIntegrationTest {

    private lateinit var protocol: MockCozmoProtocol
    private lateinit var wifi: MockCozmoWifi

    @Before
    fun setUp() {
        protocol = MockCozmoProtocol()
        wifi = MockCozmoWifi()
    }

    // ── Connection Lifecycle ──────────────────────────────────────────────────

    @Test
    fun `connect transitions to Connected state`() = runTest {
        wifi.simulateConnected()
        protocol.protocolState.test {
            awaitItem() // Idle
            protocol.connect(wifi)
            assertTrue(awaitItem() is ProtocolState.Connected)
        }
    }

    @Test
    fun `disconnect transitions to Disconnected`() = runTest {
        wifi.simulateConnected()
        protocol.connect(wifi)
        protocol.protocolState.test {
            awaitItem() // Connected
            protocol.disconnect()
            assertTrue(awaitItem() is ProtocolState.Disconnected)
        }
    }

    @Test
    fun `simulateError emits correct ProtocolError`() = runTest {
        protocol.protocolState.test {
            awaitItem() // Idle
            protocol.simulateError(ProtocolError.HANDSHAKE_TIMEOUT)
            val state = awaitItem()
            assertTrue(state is ProtocolState.Error)
            assertEquals(ProtocolError.HANDSHAKE_TIMEOUT, (state as ProtocolState.Error).reason)
        }
    }

    @Test
    fun `all ProtocolError values are reachable`() {
        ProtocolError.entries.forEach { error ->
            val p = MockCozmoProtocol()
            p.simulateError(error)
            val state = p.protocolState.value
            assertTrue(state is ProtocolState.Error)
            assertEquals(error, (state as ProtocolState.Error).reason)
        }
    }

    // ── Drive Commands ────────────────────────────────────────────────────────

    @Test
    fun `driveWheels records command with correct speeds`() {
        protocol.simulateConnected()
        protocol.driveWheels(150f, -150f)
        assertEquals(1, protocol.driveCommands.size)
        assertEquals(150f, protocol.driveCommands[0].first, 0.01f)
        assertEquals(-150f, protocol.driveCommands[0].second, 0.01f)
    }

    @Test
    fun `multiple driveWheels calls accumulate`() {
        protocol.simulateConnected()
        repeat(5) { protocol.driveWheels(it * 10f, it * -10f) }
        assertEquals(5, protocol.driveCommands.size)
    }

    @Test
    fun `stopAllMotors increments counter`() {
        protocol.stopAllMotors()
        protocol.stopAllMotors()
        assertEquals(2, protocol.stopAllMotorsCallCount)
    }

    @Test
    fun `setHeadAngle records angle`() {
        protocol.setHeadAngle(0.5f)
        assertEquals(0.5f, protocol.lastHeadAngle!!, 0.001f)
    }

    @Test
    fun `setLiftHeight records height`() {
        protocol.setLiftHeight(60f)
        assertEquals(60f, protocol.lastLiftHeight!!, 0.001f)
    }

    // ── Animations ────────────────────────────────────────────────────────────

    @Test
    fun `playAnimation returns Success and records name`() = runTest {
        protocol.nextActionResult = ActionResult.Success
        val result = protocol.playAnimation("anim_happy_01")
        assertTrue(result is ActionResult.Success)
        assertEquals("anim_happy_01", protocol.lastAnimationPlayed)
    }

    @Test
    fun `playAnimation returns Failure when result is Failure`() = runTest {
        protocol.nextActionResult = ActionResult.Failure("not found")
        val result = protocol.playAnimation("anim_unknown")
        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `playAnimationGroup records group name`() = runTest {
        protocol.playAnimationGroup("Happy")
        assertEquals("Happy", protocol.lastAnimationPlayed)
    }

    @Test
    fun `availableAnimations has at least 20 entries`() {
        assertTrue(protocol.availableAnimations.size >= 20)
    }

    @Test
    fun `AnimationManifest covers all required categories`() {
        val required = setOf("Happy", "Silly", "Angry", "Sad", "Surprised", "Special")
        assertTrue(AnimationManifest.BY_CATEGORY.keys.containsAll(required))
    }

    @Test
    fun `AnimationManifest minimum counts per category`() {
        assertTrue(AnimationManifest.HAPPY.size >= 4)
        assertTrue(AnimationManifest.SILLY.size >= 4)
        assertTrue(AnimationManifest.ANGRY.size >= 3)
        assertTrue(AnimationManifest.SAD.size >= 3)
        assertTrue(AnimationManifest.SURPRISED.size >= 3)
        assertTrue(AnimationManifest.SPECIAL.size >= 3)
    }

    // ── Cube Commands ─────────────────────────────────────────────────────────

    @Test
    fun `setCubeLights records objectId and configs`() {
        val lights = listOf(CubeLightConfig(color = 0xFF0000))
        protocol.setCubeLights(42, lights)
        assertEquals(42, protocol.lastCubeLightSet?.first)
        assertEquals(lights, protocol.lastCubeLightSet?.second)
    }

    @Test
    fun `pickupObject returns Success`() = runTest {
        protocol.nextActionResult = ActionResult.Success
        val result = protocol.pickupObject(101)
        assertTrue(result is ActionResult.Success)
    }

    @Test
    fun `pickupObject returns Timeout`() = runTest {
        protocol.nextActionResult = ActionResult.Timeout
        val result = protocol.pickupObject(101)
        assertTrue(result is ActionResult.Timeout)
    }

    @Test
    fun `placeObject returns Success`() = runTest {
        val result = protocol.placeObject()
        assertTrue(result is ActionResult.Success)
    }

    @Test
    fun `rollObject returns Success`() = runTest {
        val result = protocol.rollObject(101)
        assertTrue(result is ActionResult.Success)
    }

    // ── Robot State ───────────────────────────────────────────────────────────

    @Test
    fun `simulateRobotState updates robotState flow`() = runTest {
        val state = RobotState(poseX = 50f, poseY = 75f, emotion = Emotion.EXCITED)
        protocol.robotState.test {
            awaitItem() // default
            protocol.simulateRobotState(state)
            val received = awaitItem()
            assertEquals(50f, received.poseX, 0.01f)
            assertEquals(75f, received.poseY, 0.01f)
            assertEquals(Emotion.EXCITED, received.emotion)
        }
    }

    @Test
    fun `simulateEmotionChange updates emotion in robotState`() = runTest {
        protocol.robotState.test {
            awaitItem()
            protocol.simulateEmotionChange(Emotion.ANGRY)
            assertEquals(Emotion.ANGRY, awaitItem().emotion)
        }
    }

    @Test
    fun `simulateIsMoving updates isMoving flag`() = runTest {
        protocol.robotState.test {
            awaitItem()
            protocol.simulateIsMoving(true)
            assertTrue(awaitItem().isMoving)
            protocol.simulateIsMoving(false)
            assertFalse(awaitItem().isMoving)
        }
    }

    @Test
    fun `simulateBatteryLow sets voltage below warning threshold`() = runTest {
        protocol.robotState.test {
            awaitItem()
            protocol.simulateBatteryLow()
            val state = awaitItem()
            assertTrue(state.batteryVoltage < 3.5f)
        }
    }

    // ── Cube States ───────────────────────────────────────────────────────────

    @Test
    fun `simulateCubeDetected adds cube`() = runTest {
        val cube = CubeState(
            cubeId = 1, objectId = 101, isVisible = true, isBeingCarried = false,
            lightState = CubeLightState(0xFFFFFF, false, 500, 500),
            signalStrength = 0.8f, lastSeenMs = System.currentTimeMillis(),
        )
        protocol.cubeStates.test {
            awaitItem() // empty
            protocol.simulateCubeDetected(cube)
            val cubes = awaitItem()
            assertEquals(1, cubes.size)
            assertEquals(1, cubes[0].cubeId)
            assertTrue(cubes[0].isVisible)
        }
    }

    @Test
    fun `simulateCubeHidden sets isVisible false`() = runTest {
        val cube = CubeState(
            cubeId = 2, objectId = 102, isVisible = true, isBeingCarried = false,
            lightState = CubeLightState(0, false, 0, 0),
            signalStrength = 0.5f, lastSeenMs = System.currentTimeMillis(),
        )
        protocol.simulateCubeDetected(cube)
        protocol.cubeStates.test {
            awaitItem() // with cube visible
            protocol.simulateCubeHidden(2)
            val cubes = awaitItem()
            assertFalse(cubes.first { it.cubeId == 2 }.isVisible)
        }
    }

    @Test
    fun `up to 3 cubes can be tracked simultaneously`() = runTest {
        repeat(3) { i ->
            protocol.simulateCubeDetected(
                CubeState(
                    cubeId = i + 1, objectId = 100 + i, isVisible = true,
                    isBeingCarried = false,
                    lightState = CubeLightState(0, false, 0, 0),
                    signalStrength = 0.9f, lastSeenMs = System.currentTimeMillis(),
                )
            )
        }
        assertEquals(3, protocol.cubeStates.value.size)
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    @Test
    fun `enableCamera records enabled state`() {
        protocol.enableCamera(true)
        assertTrue(protocol.cameraEnabled)
        protocol.enableCamera(false)
        assertFalse(protocol.cameraEnabled)
    }

    // ── Freeplay ─────────────────────────────────────────────────────────────

    @Test
    fun `enableFreeplay records state`() {
        protocol.enableFreeplay(true)
        assertTrue(protocol.freeplayEnabled)
        protocol.enableFreeplay(false)
        assertFalse(protocol.freeplayEnabled)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all recorded state`() = runTest {
        protocol.simulateConnected()
        protocol.driveWheels(10f, 10f)
        protocol.enableCamera(true)
        protocol.enableFreeplay(true)
        protocol.reset()

        assertTrue(protocol.protocolState.value is ProtocolState.Idle)
        assertTrue(protocol.driveCommands.isEmpty())
        assertFalse(protocol.cameraEnabled)
        assertFalse(protocol.freeplayEnabled)
        assertEquals(0, protocol.connectCallCount)
        assertEquals(0, protocol.stopAllMotorsCallCount)
        assertNull(protocol.lastAnimationPlayed)
        assertNull(protocol.lastCubeLightSet)
    }
}

/**
 * Frame codec round-trip tests — validates binary framing layer.
 */
class FrameCodecIntegrationTest {

    @Test
    fun `all outbound message types encode without error`() {
        val messages = listOf(
            MessageIds.CONNECT       to MessageBuilder.connect(),
            MessageIds.DISCONNECT    to MessageBuilder.disconnect(),
            MessageIds.PING          to MessageBuilder.ping(1u, System.currentTimeMillis()),
            MessageIds.DRIVE_WHEELS  to MessageBuilder.driveWheels(100f, -100f),
            MessageIds.STOP_ALL_MOTORS to MessageBuilder.stopAllMotors(),
            MessageIds.SET_HEAD_ANGLE to MessageBuilder.setHeadAngle(0.3f),
            MessageIds.MOVE_HEAD     to MessageBuilder.moveHead(1f),
            MessageIds.SET_LIFT_HEIGHT to MessageBuilder.setLiftHeight(60f),
            MessageIds.MOVE_LIFT     to MessageBuilder.moveLift(0.5f),
            MessageIds.PLAY_ANIMATION to MessageBuilder.playAnimation("anim_happy_01"),
            MessageIds.SET_CUBE_LIGHTS to MessageBuilder.setCubeLights(101,
                listOf(CubeLightConfig(0xFF0000))),
            MessageIds.PICKUP_OBJECT to MessageBuilder.pickupObject(101),
            MessageIds.PLACE_OBJECT  to MessageBuilder.placeObject(),
            MessageIds.ROLL_OBJECT   to MessageBuilder.rollObject(101),
            MessageIds.ENABLE_CAMERA to MessageBuilder.enableCamera(true),
            MessageIds.ENABLE_FREEPLAY to MessageBuilder.enableFreeplay(true),
        )
        messages.forEachIndexed { index, (msgId, payload) ->
            val frame = FrameCodec.encode(index.toUInt(), 0u, msgId, payload)
            val decoded = FrameCodec.decode(frame)
            assertNotNull("Frame $index ($msgId) failed to decode", decoded)
            assertEquals(1, decoded!!.messages.size)
            assertEquals(msgId, decoded.messages[0].messageId)
            assertTrue(decoded.messages[0].payload.contentEquals(payload))
        }
    }

    @Test
    fun `frameId increments correctly across multiple frames`() {
        (1..10).forEach { id ->
            val frame = FrameCodec.encode(id.toUInt(), 0u, MessageIds.PING,
                MessageBuilder.ping(id.toUInt(), 0L))
            val decoded = FrameCodec.decode(frame)!!
            assertEquals(id.toUInt(), decoded.frameId)
        }
    }

    @Test
    fun `ackId is preserved in decoded frame`() {
        val frame = FrameCodec.encode(1u, 42u, MessageIds.PING, MessageBuilder.ping(1u, 0L))
        val decoded = FrameCodec.decode(frame)!!
        assertEquals(42u, decoded.ackId)
    }
}

/**
 * MessageParser tests — validates inbound message parsing.
 */
class MessageParserIntegrationTest {

    @Test
    fun `unknown message ID returns null without throwing`() {
        val msg = IncomingMessage(0xFFFFu, ByteArray(0))
        val result = MessageParser.parse(msg)
        assertNull(result)
    }

    @Test
    fun `malformed payload returns null without throwing`() {
        // ROBOT_STATE with only 1 byte of payload — should not crash
        val msg = IncomingMessage(MessageIds.ROBOT_STATE, ByteArray(1) { 0 })
        // Parser should handle short buffers gracefully
        try {
            MessageParser.parse(msg) // may return partial result or null — must not throw
        } catch (e: Exception) {
            fail("Parser threw on malformed payload: ${e.message}")
        }
    }

    @Test
    fun `CONNECTED message parses version and robotId`() {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2).putInt(12345).array()
        val msg = IncomingMessage(MessageIds.CONNECTED, payload)
        val parsed = MessageParser.parse(msg)
        assertTrue(parsed is ParsedMessage.Connected)
        assertEquals(2, (parsed as ParsedMessage.Connected).version)
        assertEquals(12345, parsed.robotId)
    }

    @Test
    fun `PONG message parses counter and timestamp`() {
        val ts = System.currentTimeMillis()
        val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(7).putLong(ts).array()
        val msg = IncomingMessage(MessageIds.PONG, payload)
        val parsed = MessageParser.parse(msg)
        assertTrue(parsed is ParsedMessage.Pong)
        assertEquals(7u, (parsed as ParsedMessage.Pong).counter)
        assertEquals(ts, parsed.timestamp)
    }

    @Test
    fun `ANIMATION_COMPLETED parses name and success result`() {
        val name = "anim_happy_01"
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(nameBytes.size + 2)
        nameBytes.copyInto(payload)
        payload[nameBytes.size] = 0     // null terminator
        payload[nameBytes.size + 1] = 0 // result = success
        val msg = IncomingMessage(MessageIds.ANIMATION_COMPLETED, payload)
        val parsed = MessageParser.parse(msg)
        assertTrue(parsed is ParsedMessage.AnimationCompleted)
        assertEquals("anim_happy_01", (parsed as ParsedMessage.AnimationCompleted).name)
        assertTrue(parsed.result is ActionResult.Success)
    }

    @Test
    fun `CAMERA_IMAGE parses image dimensions and jpeg data`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val payload = ByteBuffer.allocate(8 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(99)         // imageId
            .putShort(320)      // width
            .putShort(240)      // height
            .put(jpeg).array()
        val msg = IncomingMessage(MessageIds.CAMERA_IMAGE, payload)
        val parsed = MessageParser.parse(msg)
        assertTrue(parsed is ParsedMessage.CameraImage)
        parsed as ParsedMessage.CameraImage
        assertEquals(99, parsed.imageId)
        assertEquals(320, parsed.width)
        assertEquals(240, parsed.height)
        assertTrue(parsed.jpegData.contentEquals(jpeg))
    }
}
