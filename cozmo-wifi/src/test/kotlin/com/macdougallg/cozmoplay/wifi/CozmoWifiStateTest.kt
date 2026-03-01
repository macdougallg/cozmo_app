package com.macdougallg.cozmoplay.wifi

import app.cash.turbine.test
import com.macdougallg.cozmoplay.types.ConnectionError
import com.macdougallg.cozmoplay.types.ConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for WiFi module behaviour, exercised via MockCozmoWifi.
 * These tests verify state transitions, socket factory behaviour, and idempotency.
 * All tests run without a physical device or real network.
 */
class CozmoWifiStateTest {

    private lateinit var wifi: MockCozmoWifi

    @Before
    fun setUp() {
        wifi = MockCozmoWifi()
    }

    // ── Initial State ──────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() {
        assertTrue(wifi.connectionState.value is ConnectionState.Idle)
    }

    @Test
    fun `isConnected is false when Idle`() {
        assertFalse(wifi.isConnected)
    }

    // ── State Transitions ──────────────────────────────────────────────────────

    @Test
    fun `simulateConnected emits Connected state`() = runTest {
        wifi.connectionState.test {
            awaitItem() // Idle
            wifi.simulateConnected()
            val state = awaitItem()
            assertTrue(state is ConnectionState.Connected)
        }
    }

    @Test
    fun `simulateDisconnected emits Disconnected state`() = runTest {
        wifi.simulateConnected()
        wifi.connectionState.test {
            awaitItem() // Connected
            wifi.simulateDisconnected()
            val state = awaitItem()
            assertTrue(state is ConnectionState.Disconnected)
        }
    }

    @Test
    fun `simulateFallbackRequired emits FallbackRequired state`() = runTest {
        wifi.connectionState.test {
            awaitItem() // Idle
            wifi.simulateFallbackRequired()
            val state = awaitItem()
            assertTrue(state is ConnectionState.FallbackRequired)
        }
    }

    @Test
    fun `simulateError emits Error state with correct reason`() = runTest {
        wifi.connectionState.test {
            awaitItem() // Idle
            wifi.simulateError(ConnectionError.WIFI_DISABLED)
            val state = awaitItem()
            assertTrue(state is ConnectionState.Error)
            assertEquals(ConnectionError.WIFI_DISABLED, (state as ConnectionState.Error).reason)
        }
    }

    @Test
    fun `simulateFound emits Found state with ssids`() = runTest {
        val ssids = listOf("Cozmo_ABC123", "Cozmo_XYZ789")
        wifi.connectionState.test {
            awaitItem() // Idle
            wifi.simulateFound(ssids)
            val state = awaitItem()
            assertTrue(state is ConnectionState.Found)
            assertEquals(ssids, (state as ConnectionState.Found).ssids)
        }
    }

    // ── Constants ──────────────────────────────────────────────────────────────

    @Test
    fun `cozmoIpAddress is 192_168_1_1`() {
        assertEquals("192.168.1.1", wifi.cozmoIpAddress)
    }

    @Test
    fun `cozmoPort is 5551`() {
        assertEquals(5551, wifi.cozmoPort)
    }

    // ── Socket Factory ─────────────────────────────────────────────────────────

    @Test
    fun `createBoundSocket throws when not connected`() {
        assertFalse(wifi.isConnected)
        assertThrows(CozmoNotConnectedException::class.java) {
            wifi.createBoundSocket()
        }
    }

    @Test
    fun `createBoundSocket returns socket when connected`() {
        wifi.simulateConnected()
        val socket = wifi.createBoundSocket()
        assertNotNull(socket)
        socket.close()
    }

    @Test
    fun `createBoundTcpSocket throws when not connected`() {
        assertThrows(CozmoNotConnectedException::class.java) {
            wifi.createBoundTcpSocket()
        }
    }

    @Test
    fun `createBoundTcpSocket returns socket when connected`() {
        wifi.simulateConnected()
        val socket = wifi.createBoundTcpSocket()
        assertNotNull(socket)
        socket.close()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Test
    fun `disconnect records call and emits Disconnected`() {
        wifi.simulateConnected()
        wifi.disconnect()
        assertEquals(1, wifi.disconnectCallCount)
        assertTrue(wifi.connectionState.value is ConnectionState.Disconnected)
    }

    @Test
    fun `connect is idempotent - call count recorded`() {
        wifi.connect()
        wifi.connect()
        assertEquals(2, wifi.connectCallCount)
    }

    @Test
    fun `shutdown resets to Idle`() {
        wifi.simulateConnected()
        wifi.shutdown()
        assertEquals(1, wifi.shutdownCallCount)
        assertTrue(wifi.connectionState.value is ConnectionState.Idle)
    }

    @Test
    fun `reset clears all recorded calls and returns to Idle`() {
        wifi.connect()
        wifi.connect()
        wifi.simulateConnected()
        wifi.disconnect()
        wifi.reset()
        assertEquals(0, wifi.connectCallCount)
        assertEquals(0, wifi.disconnectCallCount)
        assertTrue(wifi.connectionState.value is ConnectionState.Idle)
    }

    // ── Error Reasons ──────────────────────────────────────────────────────────

    @Test
    fun `all ConnectionError values can be emitted`() {
        ConnectionError.values().forEach { reason ->
            wifi.simulateError(reason)
            val state = wifi.connectionState.value
            assertTrue(state is ConnectionState.Error)
            assertEquals(reason, (state as ConnectionState.Error).reason)
        }
    }
}
