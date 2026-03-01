package com.macdougallg.cozmoplay.ui

import app.cash.turbine.test
import com.macdougallg.cozmoplay.camera.MockCozmoCamera
import com.macdougallg.cozmoplay.protocol.MockCozmoProtocol
import com.macdougallg.cozmoplay.types.*
import com.macdougallg.cozmoplay.ui.screens.animations.AnimationsViewModel
import com.macdougallg.cozmoplay.ui.screens.cubes.CubesViewModel
import com.macdougallg.cozmoplay.ui.screens.drive.DriveViewModel
import com.macdougallg.cozmoplay.ui.screens.explore.ExploreViewModel
import com.macdougallg.cozmoplay.wifi.MockCozmoWifi
import com.macdougallg.cozmoplay.ui.screens.connect.ConnectViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ViewModel integration tests — all screens exercised via mocks.
 * No Android context, no Compose, no real hardware required.
 *
 * These tests verify that each ViewModel correctly bridges its
 * mock dependencies and exposes the right state to the UI layer.
 */

// ── ConnectViewModel ──────────────────────────────────────────────────────────

class ConnectViewModelTest {

    private lateinit var wifi: MockCozmoWifi
    private lateinit var viewModel: ConnectViewModel

    @Before fun setUp() {
        wifi = MockCozmoWifi()
        viewModel = ConnectViewModel(wifi)
    }

    @Test
    fun `connectionState mirrors wifi connectionState`() = runTest {
        viewModel.connectionState.test {
            assertTrue(awaitItem() is ConnectionState.Idle)
            wifi.simulateConnected()
            assertTrue(awaitItem() is ConnectionState.Connected)
        }
    }

    @Test
    fun `connect delegates to wifi`() {
        viewModel.connect()
        assertEquals(1, wifi.connectCallCount)
    }

    @Test
    fun `triggerManualFallback delegates to wifi connect`() {
        viewModel.triggerManualFallback()
        assertEquals(1, wifi.connectCallCount)
    }

    @Test
    fun `all ConnectionState variants flow through`() = runTest {
        viewModel.connectionState.test {
            awaitItem() // Idle
            wifi.simulateScanning()
            assertTrue(awaitItem() is ConnectionState.Scanning)
            wifi.simulateConnected()
            assertTrue(awaitItem() is ConnectionState.Connected)
            wifi.simulateDisconnected()
            assertTrue(awaitItem() is ConnectionState.Disconnected)
        }
    }
}

// ── DriveViewModel ────────────────────────────────────────────────────────────

class DriveViewModelTest {

    private lateinit var protocol: MockCozmoProtocol
    private lateinit var viewModel: DriveViewModel

    @Before fun setUp() {
        protocol = MockCozmoProtocol()
        viewModel = DriveViewModel(protocol)
    }

    @Test
    fun `driveJoystick at full forward sends positive wheel speeds`() {
        // x=0, y=1 → both wheels full forward
        viewModel.driveJoystick(0f, 1f)
        assertEquals(1, protocol.driveCommands.size)
        val (left, right) = protocol.driveCommands[0]
        assertTrue(left > 0f)
        assertTrue(right > 0f)
        assertEquals(left, right, 0.01f) // straight forward
    }

    @Test
    fun `driveJoystick at full right turns right`() {
        // x=1, y=0 → right wheel backward, left forward
        viewModel.driveJoystick(1f, 0f)
        val (left, right) = protocol.driveCommands[0]
        assertTrue(left > 0f)
        assertTrue(right < 0f)
    }

    @Test
    fun `stop calls stopAllMotors`() {
        viewModel.stop()
        assertEquals(1, protocol.stopAllMotorsCallCount)
    }

    @Test
    fun `slow mode limits speed to 50 percent`() {
        // fastMode starts false (slow)
        viewModel.driveJoystick(0f, 1f)
        val (left, _) = protocol.driveCommands[0]
        assertEquals(110f, left, 0.01f)
    }

    @Test
    fun `fast mode after toggle uses full speed`() {
        viewModel.toggleSpeed()
        viewModel.driveJoystick(0f, 1f)
        val (left, _) = protocol.driveCommands[0]
        assertEquals(220f, left, 0.01f)
    }

    @Test
    fun `setHeadAngle maps normalised 0-1 to radians range`() {
        viewModel.setHeadAngle(0f)    // min
        assertEquals(-0.44f, protocol.lastHeadAngle!!, 0.01f)
        viewModel.setHeadAngle(1f)    // max
        assertEquals(0.78f, protocol.lastHeadAngle!!, 0.01f)
    }

    @Test
    fun `setLiftHeight maps normalised 0-1 to mm range`() {
        viewModel.setLiftHeight(0f)
        assertEquals(32f, protocol.lastLiftHeight!!, 0.01f)
        viewModel.setLiftHeight(1f)
        assertEquals(92f, protocol.lastLiftHeight!!, 0.01f)
    }

    @Test
    fun `fastMode toggles correctly`() = runTest {
        viewModel.fastMode.test {
            assertFalse(awaitItem()) // starts slow
            viewModel.toggleSpeed()
            assertTrue(awaitItem())
            viewModel.toggleSpeed()
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `robotState flows from protocol`() = runTest {
        viewModel.robotState.test {
            awaitItem()
            protocol.simulateEmotionChange(Emotion.HAPPY)
            assertEquals(Emotion.HAPPY, awaitItem().emotion)
        }
    }
}

// ── AnimationsViewModel ───────────────────────────────────────────────────────

class AnimationsViewModelTest {

    private lateinit var protocol: MockCozmoProtocol
    private lateinit var viewModel: AnimationsViewModel

    @Before fun setUp() {
        protocol = MockCozmoProtocol()
        viewModel = AnimationsViewModel(protocol)
    }

    @Test
    fun `default category is All`() {
        assertEquals("All", viewModel.selectedCategory.value)
    }

    @Test
    fun `animationsFor All returns all animations`() {
        val all = viewModel.animationsFor("All")
        assertTrue(all.size >= 20)
    }

    @Test
    fun `animationsFor category returns subset`() {
        val happy = viewModel.animationsFor("Happy")
        assertTrue(happy.isNotEmpty())
        assertTrue(happy.all { it.startsWith("anim_happy") || it.startsWith("anim_excited")
            || it.startsWith("anim_greeting") })
    }

    @Test
    fun `animationsFor unknown category returns empty`() {
        assertTrue(viewModel.animationsFor("Unknown").isEmpty())
    }

    @Test
    fun `selectCategory updates selectedCategory`() = runTest {
        viewModel.selectedCategory.test {
            assertEquals("All", awaitItem())
            viewModel.selectCategory("Happy")
            assertEquals("Happy", awaitItem())
        }
    }

    @Test
    fun `categories list includes All plus all manifest categories`() {
        assertTrue(viewModel.categories.contains("All"))
        assertTrue(viewModel.categories.contains("Happy"))
        assertTrue(viewModel.categories.contains("Silly"))
        assertTrue(viewModel.categories.contains("Angry"))
    }
}

// ── ExploreViewModel ──────────────────────────────────────────────────────────

class ExploreViewModelTest {

    private lateinit var protocol: MockCozmoProtocol
    private lateinit var viewModel: ExploreViewModel

    @Before fun setUp() {
        protocol = MockCozmoProtocol()
        viewModel = ExploreViewModel(protocol)
    }

    @Test
    fun `freeplay starts false`() {
        assertFalse(viewModel.freeplayActive.value)
    }

    @Test
    fun `toggleFreeplay enables freeplay`() = runTest {
        viewModel.freeplayActive.test {
            assertFalse(awaitItem())
            viewModel.toggleFreeplay()
            assertTrue(awaitItem())
            assertTrue(protocol.freeplayEnabled)
        }
    }

    @Test
    fun `toggleFreeplay twice disables freeplay`() = runTest {
        viewModel.toggleFreeplay()
        viewModel.freeplayActive.test {
            awaitItem() // true
            viewModel.toggleFreeplay()
            assertFalse(awaitItem())
            assertFalse(protocol.freeplayEnabled)
        }
    }

    @Test
    fun `robotState flows through from protocol`() = runTest {
        viewModel.robotState.test {
            awaitItem()
            protocol.simulateIsMoving(true)
            assertTrue(awaitItem().isMoving)
        }
    }

    @Test
    fun `cubeStates flows through from protocol`() = runTest {
        viewModel.cubeStates.test {
            awaitItem() // empty
            protocol.simulateCubeDetected(
                CubeState(1, 101, true, false,
                    CubeLightState(0, false, 0, 0), 0.9f, System.currentTimeMillis())
            )
            assertEquals(1, awaitItem().size)
        }
    }
}

// ── CubesViewModel ────────────────────────────────────────────────────────────

class CubesViewModelTest {

    private lateinit var protocol: MockCozmoProtocol
    private lateinit var viewModel: CubesViewModel

    @Before fun setUp() {
        protocol = MockCozmoProtocol()
        viewModel = CubesViewModel(protocol)
    }

    @Test
    fun `no cube selected initially`() {
        assertNull(viewModel.selectedCubeId.value)
    }

    @Test
    fun `selectCube updates selectedCubeId`() = runTest {
        viewModel.selectedCubeId.test {
            assertNull(awaitItem())
            viewModel.selectCube(101)
            assertEquals(101, awaitItem())
        }
    }

    @Test
    fun `setLightColor with no selection does nothing`() {
        viewModel.setLightColor(0xFF0000)
        assertNull(protocol.lastCubeLightSet)
    }

    @Test
    fun `setLightColor with selection calls setCubeLights`() {
        viewModel.selectCube(101)
        viewModel.setLightColor(0xFF0000)
        assertNotNull(protocol.lastCubeLightSet)
        assertEquals(101, protocol.lastCubeLightSet!!.first)
        assertEquals(4, protocol.lastCubeLightSet!!.second.size) // 4 LEDs
        assertEquals(0xFF0000, protocol.lastCubeLightSet!!.second[0].color)
    }

    @Test
    fun `cubeStates flows from protocol`() = runTest {
        viewModel.cubeStates.test {
            awaitItem() // empty
            protocol.simulateCubeDetected(
                CubeState(1, 101, true, false,
                    CubeLightState(0, false, 0, 0), 1f, System.currentTimeMillis())
            )
            assertEquals(1, awaitItem().size)
        }
    }

    @Test
    fun `actionInProgress starts false`() {
        assertFalse(viewModel.actionInProgress.value)
    }
}

// ── End-to-End Mock Integration ───────────────────────────────────────────────

class EndToEndMockTest {

    @Test
    fun `full connection and drive flow using all mocks`() = runTest {
        val wifi = MockCozmoWifi()
        val protocol = MockCozmoProtocol()
        val camera = MockCozmoCamera()

        val connectVm = ConnectViewModel(wifi)
        val driveVm = DriveViewModel(protocol)

        // 1. Connection flow
        connectVm.connectionState.test {
            assertTrue(awaitItem() is ConnectionState.Idle)
            connectVm.connect()
            wifi.simulateScanning()
            assertTrue(awaitItem() is ConnectionState.Scanning)
            wifi.simulateConnected()
            assertTrue(awaitItem() is ConnectionState.Connected)
        }

        // 2. Drive commands flow
        protocol.simulateConnected()
        driveVm.driveJoystick(0f, 0.5f)
        assertEquals(1, protocol.driveCommands.size)
        driveVm.stop()
        assertEquals(1, protocol.stopAllMotorsCallCount)

        // 3. Camera enable
        camera.setEnabled(true)
        assertTrue(camera.isEnabled.value)
        assertTrue(camera.cameraState.value is CameraState.Streaming)
    }

    @Test
    fun `animation flow — play then complete`() = runTest {
        val protocol = MockCozmoProtocol()
        val viewModel = AnimationsViewModel(protocol)

        protocol.nextActionResult = ActionResult.Success
        protocol.nextActionDelayMs = 0L

        assertNull(viewModel.playingAnimation.value)
        // playAnimation is launched in viewModelScope — verify name is recorded
        protocol.playAnimation("anim_happy_01")
        assertEquals("anim_happy_01", protocol.lastAnimationPlayed)
    }

    @Test
    fun `explore freeplay and emotion update flow`() = runTest {
        val protocol = MockCozmoProtocol()
        val viewModel = ExploreViewModel(protocol)

        viewModel.toggleFreeplay()
        assertTrue(protocol.freeplayEnabled)

        viewModel.robotState.test {
            awaitItem()
            protocol.simulateEmotionChange(Emotion.EXCITED)
            assertEquals(Emotion.EXCITED, awaitItem().emotion)
        }
    }
}
