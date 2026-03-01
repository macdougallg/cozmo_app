package com.macdougallg.cozmoplay.wifi

import com.macdougallg.cozmoplay.types.ConnectionState
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramSocket
import java.net.Socket

/**
 * Public interface for the Cozmo WiFi connectivity module.
 *
 * All consumers (cozmo-protocol, app UI) MUST depend on this interface,
 * never on the concrete CozmoWifiManager implementation.
 *
 * Threading: all StateFlow emissions arrive on Dispatchers.Main.
 * Socket creation: always via this interface; never create raw sockets elsewhere.
 */
interface ICozmoWifi {

    // ── Observable State ─────────────────────────────────────────────────────

    /** Live connection state. Always emits on Dispatchers.Main. Never null. */
    val connectionState: StateFlow<ConnectionState>

    /** Convenience shorthand. True only when state is ConnectionState.Connected. */
    val isConnected: Boolean

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Fixed IP address of all Cozmo robots. */
    val cozmoIpAddress: String

    /** Fixed UDP port for all Cozmo robot communication. */
    val cozmoPort: Int

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Begin the scan → auto-connect → socket-bind flow.
     * Idempotent — safe to call when already connected or connecting.
     * Call this from the UI after permissions are granted.
     */
    fun connect()

    /**
     * Release the Cozmo network connection.
     * Emits [ConnectionState.Disconnected] and cleans up network callbacks.
     */
    fun disconnect()

    /**
     * Full resource cleanup. Must be called from Application.onTerminate()
     * or the app's ViewModel onCleared(). Unregisters all Android system callbacks.
     */
    fun shutdown()

    // ── Socket Factory ────────────────────────────────────────────────────────

    /**
     * Creates a UDP [DatagramSocket] already bound to the Cozmo network.
     * All packets sent via this socket will route to 192.168.1.1 regardless
     * of which network Android considers the default.
     *
     * @throws CozmoNotConnectedException if [connectionState] is not [ConnectionState.Connected].
     */
    fun createBoundSocket(): DatagramSocket

    /**
     * Creates a TCP [Socket] already bound to the Cozmo network.
     *
     * @throws CozmoNotConnectedException if [connectionState] is not [ConnectionState.Connected].
     */
    fun createBoundTcpSocket(): Socket
}

/**
 * Thrown when a socket is requested but the WiFi module is not in Connected state.
 */
class CozmoNotConnectedException(msg: String) : IllegalStateException(msg)
