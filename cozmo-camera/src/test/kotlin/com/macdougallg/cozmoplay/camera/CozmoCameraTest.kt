package com.macdougallg.cozmoplay.camera

import app.cash.turbine.test
import com.macdougallg.cozmoplay.types.CameraState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MockCozmoCameraTest {

    private lateinit var camera: MockCozmoCamera

    @Before
    fun setUp() {
        camera = MockCozmoCamera()
    }

    @Test
    fun `initial state is Disabled`() {
        assertTrue(camera.cameraState.value is CameraState.Disabled)
        assertFalse(camera.isEnabled.value)
        assertEquals(0f, camera.currentFps.value, 0.01f)
    }

    @Test
    fun `setEnabled true transitions to Streaming`() = runTest {
        camera.cameraState.test {
            awaitItem() // Disabled
            camera.setEnabled(true)
            val state = awaitItem()
            assertTrue(state is CameraState.Streaming)
        }
    }

    @Test
    fun `setEnabled false transitions to Disabled`() = runTest {
        camera.setEnabled(true)
        camera.cameraState.test {
            awaitItem() // Streaming
            camera.setEnabled(false)
            val state = awaitItem()
            assertTrue(state is CameraState.Disabled)
        }
    }

    @Test
    fun `setEnabled is idempotent via MockCozmoCamera`() {
        camera.setEnabled(true)
        camera.setEnabled(true)
        assertEquals(2, camera.setEnabledCallCount)
        assertTrue(camera.isEnabled.value)
    }

    @Test
    fun `simulateError transitions to StreamError`() = runTest {
        camera.cameraState.test {
            awaitItem() // Disabled
            camera.simulateError("Test error")
            val state = awaitItem()
            assertTrue(state is CameraState.StreamError)
            assertEquals("Test error", (state as CameraState.StreamError).reason)
        }
    }

    @Test
    fun `simulateEnabling transitions to Enabling`() = runTest {
        camera.cameraState.test {
            awaitItem() // Disabled
            camera.simulateEnabling()
            val state = awaitItem()
            assertTrue(state is CameraState.Enabling)
        }
    }

    @Test
    fun `setDisplaySize records dimensions`() {
        camera.setDisplaySize(640, 480)
        assertEquals(Pair(640, 480), camera.lastDisplaySize)
    }

    @Test
    fun `currentFps is 0 when disabled`() {
        camera.setEnabled(false)
        assertEquals(0f, camera.currentFps.value, 0.01f)
    }

    @Test
    fun `currentFps is non-zero when streaming`() {
        camera.setEnabled(true)
        assertTrue(camera.currentFps.value > 0f)
    }

    @Test
    fun `reset returns to initial state`() {
        camera.setEnabled(true)
        camera.setDisplaySize(480, 360)
        camera.reset()
        assertFalse(camera.isEnabled.value)
        assertTrue(camera.cameraState.value is CameraState.Disabled)
        assertEquals(0, camera.setEnabledCallCount)
        assertNull(camera.lastDisplaySize)
    }

    @Test
    fun `simulatePaused emits Paused state`() = runTest {
        camera.cameraState.test {
            awaitItem() // Disabled
            camera.simulatePaused()
            val state = awaitItem()
            assertTrue(state is CameraState.Paused)
        }
    }

    @Test
    fun `all CameraState variants are reachable`() = runTest {
        val states = mutableListOf<CameraState>()
        camera.cameraState.test {
            states.add(awaitItem()) // Disabled
            camera.simulateEnabling(); states.add(awaitItem())
            camera.setEnabled(true); states.add(awaitItem())
            camera.simulateError("err"); states.add(awaitItem())
            camera.simulatePaused(); states.add(awaitItem())
        }
        assertTrue(states.any { it is CameraState.Disabled })
        assertTrue(states.any { it is CameraState.Enabling })
        assertTrue(states.any { it is CameraState.Streaming })
        assertTrue(states.any { it is CameraState.StreamError })
        assertTrue(states.any { it is CameraState.Paused })
    }
}
