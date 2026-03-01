package com.macdougallg.cozmoplay.camera

import app.cash.turbine.test
import com.macdougallg.cozmoplay.types.CameraState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the camera module.
 * All tests use MockCozmoCamera — no real hardware or JPEG decode required.
 *
 * Covers Camera PRD FR-04 (enable/disable), FR-06 (state machine),
 * and NFR-01/02 (performance and memory contracts via mock simulation).
 */
class CameraIntegrationTest {

    private lateinit var camera: MockCozmoCamera

    @Before
    fun setUp() {
        camera = MockCozmoCamera()
    }

    // ── Initial State ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is Disabled`() {
        assertTrue(camera.cameraState.value is CameraState.Disabled)
        assertFalse(camera.isEnabled.value)
        assertEquals(0f, camera.currentFps.value, 0.01f)
    }

    // ── Enable / Disable Lifecycle ────────────────────────────────────────────

    @Test
    fun `setEnabled true transitions to Streaming`() = runTest {
        camera.cameraState.test {
            awaitItem() // Disabled
            camera.setEnabled(true)
            assertTrue(awaitItem() is CameraState.Streaming)
            assertTrue(camera.isEnabled.value)
        }
    }

    @Test
    fun `setEnabled false transitions to Disabled`() = runTest {
        camera.setEnabled(true)
        camera.cameraState.test {
            awaitItem() // Streaming
            camera.setEnabled(false)
            assertTrue(awaitItem() is CameraState.Disabled)
            assertFalse(camera.isEnabled.value)
        }
    }

    @Test
    fun `setEnabled is idempotent — second call still records`() {
        camera.setEnabled(true)
        camera.setEnabled(true)
        assertEquals(2, camera.setEnabledCallCount)
        assertTrue(camera.isEnabled.value)
    }

    @Test
    fun `fps is zero when disabled`() {
        camera.setEnabled(false)
        assertEquals(0f, camera.currentFps.value, 0.01f)
    }

    @Test
    fun `fps is 15 when streaming`() {
        camera.setEnabled(true)
        assertEquals(15f, camera.currentFps.value, 0.01f)
    }

    // ── Full State Machine ────────────────────────────────────────────────────

    @Test
    fun `all CameraState variants are reachable in sequence`() = runTest {
        val observed = mutableListOf<String>()
        camera.cameraState.test {
            observed.add(awaitItem()::class.simpleName!!) // Disabled
            camera.simulateEnabling(); observed.add(awaitItem()::class.simpleName!!)
            camera.setEnabled(true); observed.add(awaitItem()::class.simpleName!!)
            camera.simulateError("test"); observed.add(awaitItem()::class.simpleName!!)
            camera.simulatePaused(); observed.add(awaitItem()::class.simpleName!!)
        }
        assertTrue(observed.contains("Disabled"))
        assertTrue(observed.contains("Enabling"))
        assertTrue(observed.contains("Streaming"))
        assertTrue(observed.contains("StreamError"))
        assertTrue(observed.contains("Paused"))
    }

    @Test
    fun `StreamError carries reason string`() = runTest {
        camera.cameraState.test {
            awaitItem()
            camera.simulateError("No frames received after 5000ms")
            val state = awaitItem()
            assertTrue(state is CameraState.StreamError)
            assertEquals("No frames received after 5000ms",
                (state as CameraState.StreamError).reason)
        }
    }

    // ── Display Size ──────────────────────────────────────────────────────────

    @Test
    fun `setDisplaySize is recorded`() {
        camera.setDisplaySize(480, 360)
        assertEquals(Pair(480, 360), camera.lastDisplaySize)
    }

    @Test
    fun `setDisplaySize to native resolution is recorded`() {
        camera.setDisplaySize(320, 240)
        assertEquals(Pair(320, 240), camera.lastDisplaySize)
    }

    // ── App Lifecycle ─────────────────────────────────────────────────────────

    @Test
    fun `pause transitions to Paused`() = runTest {
        camera.setEnabled(true)
        camera.cameraState.test {
            awaitItem() // Streaming
            camera.simulatePaused()
            assertTrue(awaitItem() is CameraState.Paused)
        }
    }

    @Test
    fun `resume after pause returns to Streaming`() = runTest {
        camera.simulatePaused()
        camera.cameraState.test {
            awaitItem() // Paused
            camera.setEnabled(true)
            assertTrue(awaitItem() is CameraState.Streaming)
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset returns to clean initial state`() {
        camera.setEnabled(true)
        camera.setDisplaySize(640, 480)
        camera.reset()

        assertFalse(camera.isEnabled.value)
        assertTrue(camera.cameraState.value is CameraState.Disabled)
        assertEquals(0f, camera.currentFps.value, 0.01f)
        assertEquals(0, camera.setEnabledCallCount)
        assertNull(camera.lastDisplaySize)
        assertNull(camera.lastEnabledValue)
    }

    // ── Streaming State FPS ───────────────────────────────────────────────────

    @Test
    fun `Streaming state carries fps value`() = runTest {
        camera.cameraState.test {
            awaitItem() // Disabled
            camera.setEnabled(true)
            val state = awaitItem()
            assertTrue(state is CameraState.Streaming)
            assertTrue((state as CameraState.Streaming).fps > 0f)
        }
    }
}

/**
 * Cross-module integration: camera consuming protocol's frame flow.
 */
class CameraProtocolIntegrationTest {

    @Test
    fun `MockCozmoCamera and MockCozmoProtocol work together`() = runTest {
        val protocol = com.macdougallg.cozmoplay.protocol.MockCozmoProtocol()
        val camera = MockCozmoCamera()

        // Simulate the wiring: camera enable → protocol enable
        camera.setEnabled(true)
        protocol.enableCamera(true)

        assertTrue(camera.isEnabled.value)
        assertTrue(protocol.cameraEnabled)

        // Disable both
        camera.setEnabled(false)
        protocol.enableCamera(false)

        assertFalse(camera.isEnabled.value)
        assertFalse(protocol.cameraEnabled)
    }
}
