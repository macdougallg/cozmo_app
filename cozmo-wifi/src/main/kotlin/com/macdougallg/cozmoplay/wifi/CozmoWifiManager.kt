package com.macdougallg.cozmoplay.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import com.macdougallg.cozmoplay.types.ConnectionError
import com.macdougallg.cozmoplay.types.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramSocket
import java.net.Socket

/**
 * Concrete implementation of [ICozmoWifi].
 *
 * Handles both API 28 (Fire OS 7) and API 30+ (Fire OS 8) connection paths,
 * chosen at runtime via [Build.VERSION.SDK_INT].
 *
 * Instantiate once per Application lifecycle via Koin or manual DI.
 * Never instantiate per-ViewModel — the network binding is a singleton resource.
 */
class CozmoWifiManager(private val context: Context) : ICozmoWifi {

    companion object {
        private const val TAG = "CozmoWifi"
        const val COZMO_IP = "192.168.1.1"
        const val COZMO_PORT = 5551
        private const val COZMO_SSID_PREFIX = "Cozmo_"
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 2_000L
        private const val POLL_MAX_DURATION_MS = 60_000L
        private const val RECONNECT_MAX_ATTEMPTS = 3
        private const val RECONNECT_INTERVAL_MS = 5_000L
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean
        get() = _connectionState.value is ConnectionState.Connected

    override val cozmoIpAddress: String = COZMO_IP
    override val cozmoPort: Int = COZMO_PORT

    // ── Internal ───────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** The bound Cozmo Network object — null until Connected. */
    @Volatile private var cozmoNetwork: Network? = null

    /** Active network callback for API 30+ path — must be unregistered on cleanup. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var reconnectAttempts = 0

    // ── Public API ─────────────────────────────────────────────────────────────

    override fun connect() {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            Log.d(TAG, "connect() called but already connected/connecting — ignoring")
            return
        }
        connectJob?.cancel()
        connectJob = scope.launch {
            emit(ConnectionState.Scanning)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                connectApi30()
            } else {
                connectApi28()
            }
        }
    }

    override fun disconnect() {
        connectJob?.cancel()
        pollJob?.cancel()
        unregisterNetworkCallback()
        cozmoNetwork = null
        emit(ConnectionState.Disconnected)
        Log.i(TAG, "Disconnected from Cozmo network")
    }

    override fun shutdown() {
        disconnect()
        scope.cancel()
        Log.i(TAG, "CozmoWifiManager shutdown complete")
    }

    override fun createBoundSocket(): DatagramSocket {
        val network = cozmoNetwork
            ?: throw CozmoNotConnectedException("Cannot create socket — not connected to Cozmo network")
        return DatagramSocket().also { socket ->
            network.bindSocket(socket)
            Log.d(TAG, "Created UDP socket bound to Cozmo network")
        }
    }

    override fun createBoundTcpSocket(): Socket {
        val network = cozmoNetwork
            ?: throw CozmoNotConnectedException("Cannot create TCP socket — not connected to Cozmo network")
        return network.socketFactory.createSocket().also {
            Log.d(TAG, "Created TCP socket bound to Cozmo network")
        }
    }

    // ── API 30+ Connection Path ────────────────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun connectApi30() {
        Log.i(TAG, "Using API 30+ WifiNetworkSpecifier path")
        emit(ConnectionState.Connecting)

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(android.os.PatternMatcher(COZMO_SSID_PREFIX, android.os.PatternMatcher.PATTERN_PREFIX))
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val connectionResult = CompletableDeferred<Network?>()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Cozmo network available (API 30+)")
                connectionResult.complete(network)
            }

            override fun onUnavailable() {
                Log.w(TAG, "Cozmo network unavailable (API 30+)")
                connectionResult.complete(null)
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Cozmo network lost")
                handleConnectionLost()
            }
        }

        networkCallback = callback
        connectivityManager.requestNetwork(request, callback)

        val network = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { connectionResult.await() }

        if (network != null) {
            cozmoNetwork = network
            reconnectAttempts = 0
            emit(ConnectionState.Connected)
            Log.i(TAG, "Connected to Cozmo (API 30+)")
        } else {
            unregisterNetworkCallback()
            Log.w(TAG, "Connection timed out — triggering fallback")
            triggerFallback()
        }
    }

    // ── API 28 Connection Path ─────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private suspend fun connectApi28() {
        Log.i(TAG, "Using API 28 WifiManager legacy path")
        emit(ConnectionState.Connecting)

        // Scan for Cozmo SSIDs using the scan results
        val scanResults = withContext(Dispatchers.IO) {
            wifiManager.startScan()
            delay(3000) // Allow scan to complete
            wifiManager.scanResults
        }

        val cozmoSsids = scanResults
            .filter { it.SSID.startsWith(COZMO_SSID_PREFIX, ignoreCase = true) }
            .map { it.SSID }

        if (cozmoSsids.isEmpty()) {
            Log.w(TAG, "No Cozmo SSIDs found in scan")
            emit(ConnectionState.NotFound)
            triggerFallback()
            return
        }

        emit(ConnectionState.Found(cozmoSsids))
        val targetSsid = cozmoSsids.first()
        Log.i(TAG, "Found Cozmo SSID: $targetSsid — attempting connection")

        val config = android.net.wifi.WifiConfiguration().apply {
            SSID = "\"$targetSsid\""
            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
        }

        val networkId = wifiManager.addNetwork(config).also {
            if (it == -1) {
                Log.e(TAG, "Failed to add Cozmo network config")
                emit(ConnectionState.Error(ConnectionError.NETWORK_REJECTED))
                return
            }
        }

        wifiManager.enableNetwork(networkId, true)
        wifiManager.reconnect()

        // Poll ConnectivityManager for the bound Cozmo network
        val network = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
            var found: Network? = null
            while (found == null) {
                delay(500)
                found = connectivityManager.allNetworks.firstOrNull { net ->
                    val info = connectivityManager.getNetworkInfo(net)
                    info?.isConnected == true &&
                        connectivityManager.getNetworkCapabilities(net)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
            }
            found
        }

        if (network != null) {
            // Restore default network to preserve internet access on home WiFi
            connectivityManager.bindProcessToNetwork(null)
            cozmoNetwork = network
            reconnectAttempts = 0
            emit(ConnectionState.Connected)
            Log.i(TAG, "Connected to Cozmo (API 28)")
        } else {
            Log.w(TAG, "API 28 connection timed out — triggering fallback")
            triggerFallback()
        }
    }

    // ── Fallback & Polling ─────────────────────────────────────────────────────

    private fun triggerFallback() {
        emit(ConnectionState.FallbackRequired)
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            emit(ConnectionState.Polling)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < POLL_MAX_DURATION_MS) {
                delay(POLL_INTERVAL_MS)
                if (isCozmoNetworkActive()) {
                    Log.i(TAG, "Polling detected manual Cozmo connection")
                    // Bind socket to detected network then signal connected
                    val network = connectivityManager.allNetworks.firstOrNull { net ->
                        val caps = connectivityManager.getNetworkCapabilities(net)
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    }
                    if (network != null) {
                        cozmoNetwork = network
                        reconnectAttempts = 0
                        emit(ConnectionState.Connected)
                        return@launch
                    }
                }
            }
            Log.w(TAG, "Polling timed out after ${POLL_MAX_DURATION_MS}ms")
            emit(ConnectionState.TimedOut)
        }
    }

    private fun isCozmoNetworkActive(): Boolean {
        return connectivityManager.allNetworks.any { net ->
            val info = connectivityManager.getNetworkInfo(net)
            info?.isConnected == true && info.extraInfo?.contains(COZMO_SSID_PREFIX, ignoreCase = true) == true
        }
    }

    // ── Reconnection ───────────────────────────────────────────────────────────

    private fun handleConnectionLost() {
        if (_connectionState.value !is ConnectionState.Connected) return
        scope.launch {
            emit(ConnectionState.Disconnected)
            if (reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
                reconnectAttempts++
                Log.i(TAG, "Attempting reconnection $reconnectAttempts/$RECONNECT_MAX_ATTEMPTS")
                delay(RECONNECT_INTERVAL_MS)
                connect()
            } else {
                Log.w(TAG, "Max reconnection attempts reached — triggering fallback")
                reconnectAttempts = 0
                triggerFallback()
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun emit(state: ConnectionState) {
        scope.launch(Dispatchers.Main) {
            _connectionState.value = state
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(TAG, "Network callback unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Network callback was already unregistered: ${e.message}")
            }
            networkCallback = null
        }
    }
}
