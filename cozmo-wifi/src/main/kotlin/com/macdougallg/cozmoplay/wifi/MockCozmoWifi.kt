package com.macdougallg.cozmoplay.wifi

import com.macdougallg.cozmoplay.types.ConnectionError
import com.macdougallg.cozmoplay.types.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Test double for [ICozmoWifi].
 *
 * Used by Agent 3 (UI), Agent 4 (camera), and Agent 5 (QA) during parallel development
 * and in all automated tests. Provides simulation methods to drive state transitions
 * without real network hardware.
 *
 * Usage:
 * ```kotlin
 * val wifi = MockCozmoWifi()
 * wifi.simulateConnected()
 * // ... assert UI updates
 * wifi.simulateError(ConnectionError.WIFI_DISABLED)
 * ```
 */
class MockCozmoWifi : ICozmoWifi {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean
        get() = _connectionState.value is ConnectionState.Connected

    override val cozmoIpAddress: String = "192.168.1.1"
    override val cozmoPort: Int = 5551

    // Recorded calls for assertion in tests
    var connectCallCount = 0
        private set
    var disconnectCallCount = 0
        private set
    var shutdownCallCount = 0
        private set

    override fun connect() {
        connectCallCount++
    }

    override fun disconnect() {
        disconnectCallCount++
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun shutdown() {
        shutdownCallCount++
        _connectionState.value = ConnectionState.Idle
    }

    /**
     * Returns a loopback-bound UDP socket suitable for use in tests.
     * Throws [CozmoNotConnectedException] if not in Connected state (matching real behaviour).
     */
    override fun createBoundSocket(): DatagramSocket {
        if (!isConnected) throw CozmoNotConnectedException("MockCozmoWifi: not connected")
        return DatagramSocket(0, InetAddress.getLoopbackAddress())
    }

    /**
     * Returns a loopback TCP socket.
     * Throws [CozmoNotConnectedException] if not in Connected state.
     */
    override fun createBoundTcpSocket(): Socket {
        if (!isConnected) throw CozmoNotConnectedException("MockCozmoWifi: not connected")
        return Socket()
    }

    // ── Simulation Methods ────────────────────────────────────────────────────

    fun simulateConnected() {
        _connectionState.value = ConnectionState.Connected
    }

    fun simulateDisconnected() {
        _connectionState.value = ConnectionState.Disconnected
    }

    fun simulateScanning() {
        _connectionState.value = ConnectionState.Scanning
    }

    fun simulateFound(ssids: List<String> = listOf("Cozmo_ABC123")) {
        _connectionState.value = ConnectionState.Found(ssids)
    }

    fun simulateFallbackRequired() {
        _connectionState.value = ConnectionState.FallbackRequired
    }

    fun simulatePolling() {
        _connectionState.value = ConnectionState.Polling
    }

    fun simulateTimedOut() {
        _connectionState.value = ConnectionState.TimedOut
    }

    fun simulateError(reason: ConnectionError) {
        _connectionState.value = ConnectionState.Error(reason)
    }

    fun reset() {
        _connectionState.value = ConnectionState.Idle
        connectCallCount = 0
        disconnectCallCount = 0
        shutdownCallCount = 0
    }
}
