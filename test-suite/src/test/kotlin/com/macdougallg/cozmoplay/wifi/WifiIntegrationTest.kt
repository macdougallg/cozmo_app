package com.macdougallg.cozmoplay.wifi

import app.cash.turbine.test
import com.macdougallg.cozmoplay.types.ConnectionError
import com.macdougallg.cozmoplay.types.ConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the WiFi module.
 * All tests run against MockCozmoWifi — no real device or network required.
 *
 * Covers WiFi PRD FR-06 (state machine) and FR-04 (socket factory).
 */
class WifiIntegrationTest {

    private lateinit var wifi: MockCozmoWifi

    @Before
    fun setUp() {
        wifi = MockCozmoWifi()
    }

    // ── Initial State ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle and not connected`() {
        assertTrue(wifi.connectionState.value is ConnectionState.Idle)
        assertFalse(wifi.isConnected)
    }

    @Test
    fun `constants are correct`() {
        assertEquals("192.168.1.1", wifi.cozmoIpAddress)
        assertEquals(5551, wifi.cozmoPort)
    }

    // ── Happy Path ────────────────────────────────────────────────────────────

    @Test
    fun `simulateConnected transitions to Connected and sets isConnected`() = runTest {
        wifi.connectionState.test {
            awaitItem() // Idle
            wifi.simulateConnected()
            assertTrue(awaitItem() is ConnectionState.Connected)
            assertTrue(wifi.isConnected)
        }
    }

    @Test
    fun `simulateDisconnected transitions to Disconnected`() = runTest {
        wifi.simulateConnected()
        wifi.connectionState.test {
            awaitItem() // Connected
            wifi.simulateDisconnected()
            assertTrue(awaitItem() is ConnectionState.Disconnected)
            assertFalse(wifi.isConnected)
        }
    }

    @Test
    fun `full happy path state sequence`() = runTest {
        wifi.connectionState.test {
            assertTrue(awaitItem() is ConnectionState.Idle)
            wifi.simulateScanning()
            assertTrue(awaitItem() is ConnectionState.Scanning)
            wifi.simulateFound(listOf("Cozmo_ABC123"))
            val found = awaitItem()
            assertTrue(found is ConnectionState.Found)
            assertEquals(listOf("Cozmo_ABC123"), (found as ConnectionState.Found).ssids)
            wifi.simulateConnected()
            assertTrue(awaitItem() is ConnectionState.Connected)
        }
    }

    // ── Fallback Path ─────────────────────────────────────────────────────────

    @Test
    fun `simulateFallbackRequired emits FallbackRequired`() = runTest {
        wifi.connectionState.test {
            awaitItem()
            wifi.simulateFallbackRequired()
            assertTrue(awaitItem() is ConnectionState.FallbackRequired)
        }
    }

    @Test
    fun `simulatePolling emits Polling`() = runTest {
        wifi.connectionState.test {
            awaitItem()
            wifi.simulatePolling()
            assertTrue(awaitItem() is ConnectionState.Polling)
        }
    }

    @Test
    fun `simulateTimedOut emits TimedOut`() = runTest {
        wifi.connectionState.test {
            awaitItem()
            wifi.simulateTimedOut()
            assertTrue(awaitItem() is ConnectionState.TimedOut)
        }
    }

    // ── Error States ──────────────────────────────────────────────────────────

    @Test
    fun `simulateError with WIFI_DISABLED emits Error`() = runTest {
        wifi.connectionState.test {
            awaitItem()
            wifi.simulateError(ConnectionError.WIFI_DISABLED)
            val state = awaitItem()
            assertTrue(state is ConnectionState.Error)
            assertEquals(ConnectionError.WIFI_DISABLED, (state as ConnectionState.Error).reason)
        }
    }

    @Test
    fun `simulateError with PERMISSION_DENIED emits Error`() = runTest {
        wifi.connectionState.test {
            awaitItem()
            wifi.simulateError(ConnectionError.PERMISSION_DENIED)
            val state = awaitItem()
            assertTrue(state is ConnectionState.Error)
            assertEquals(ConnectionError.PERMISSION_DENIED, (state as ConnectionState.Error).reason)
        }
    }

    @Test
    fun `simulateError with NETWORK_REJECTED emits Error`() = runTest {
        wifi.connectionState.test {
            awaitItem()
            wifi.simulateError(ConnectionError.NETWORK_REJECTED)
            val state = awaitItem()
            assertTrue(state is ConnectionState.Error)
            assertEquals(ConnectionError.NETWORK_REJECTED, (state as ConnectionState.Error).reason)
        }
    }

    @Test
    fun `all ConnectionError enum values are handled`() {
        ConnectionError.entries.forEach { error ->
            val wifi2 = MockCozmoWifi()
            wifi2.simulateError(error)
            val state = wifi2.connectionState.value
            assertTrue("Expected Error for $error", state is ConnectionState.Error)
            assertEquals(error, (state as ConnectionState.Error).reason)
        }
    }

    // ── Socket Factory ────────────────────────────────────────────────────────

    @Test(expected = CozmoNotConnectedException::class)
    fun `createBoundSocket throws when not connected`() {
        wifi.createBoundSocket()
    }

    @Test(expected = CozmoNotConnectedException::class)
    fun `createBoundTcpSocket throws when not connected`() {
        wifi.createBoundTcpSocket()
    }

    @Test
    fun `createBoundSocket succeeds when connected`() {
        wifi.simulateConnected()
        val socket = wifi.createBoundSocket()
        assertNotNull(socket)
        socket.close()
    }

    @Test
    fun `createBoundTcpSocket succeeds when connected`() {
        wifi.simulateConnected()
        val socket = wifi.createBoundTcpSocket()
        assertNotNull(socket)
        socket.close()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    fun `connect increments call count`() {
        wifi.connect()
        wifi.connect()
        assertEquals(2, wifi.connectCallCount)
    }

    @Test
    fun `disconnect emits Disconnected`() = runTest {
        wifi.simulateConnected()
        wifi.connectionState.test {
            awaitItem() // Connected
            wifi.disconnect()
            assertTrue(awaitItem() is ConnectionState.Disconnected)
        }
    }

    @Test
    fun `shutdown resets to Idle`() = runTest {
        wifi.simulateConnected()
        wifi.connectionState.test {
            awaitItem() // Connected
            wifi.shutdown()
            assertTrue(awaitItem() is ConnectionState.Idle)
        }
    }

    @Test
    fun `reset clears all state`() {
        wifi.simulateConnected()
        wifi.connect()
        wifi.reset()
        assertTrue(wifi.connectionState.value is ConnectionState.Idle)
        assertFalse(wifi.isConnected)
        assertEquals(0, wifi.connectCallCount)
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `connect while already connected is tracked`() {
        wifi.simulateConnected()
        wifi.connect() // Should be a no-op on real impl; mock just records
        assertTrue(wifi.isConnected)
    }
}
