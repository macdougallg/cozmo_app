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
 * Production implementation of [ICozmoWifi].
 *
 * Connection strategy:
 * - API 30+: WifiNetworkSpecifier peer-to-peer request. The key insight is that
 *   we must NOT call bindProcessToNetwork() — instead we bind individual sockets
 *   to the returned Network object so the rest of the app keeps internet.
 * - API 28: Legacy WifiManager path with manual SSID scan and network selection.
 *
 * Manual fallback polling (API 29+ compatible):
 * - Uses NetworkCapabilities + SSID transport info instead of deprecated extraInfo.
 * - On API 29+, WifiInfo is only accessible via NetworkCapabilities.transportInfo.
 */
class CozmoWifiManager(private val context: Context) : ICozmoWifi {

    companion object {
        private const val TAG = "CozmoWifi"
        private const val COZMO_SSID_PREFIX = "Cozmo_"
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 2_000L
        private const val POLL_MAX_ATTEMPTS = 90 // 180 seconds
        private const val RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 5_000L
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val isConnected: Boolean get() = _connectionState.value is ConnectionState.Connected
    override val cozmoIpAddress: String = "172.31.1.1"
    override val cozmoPort: Int = 5551

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile private var cozmoNetwork: Network? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectAttempts = 0
    private var pollJob: Job? = null
    private var connectJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    override fun connect() {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            Log.d(TAG, "connect() called while already connecting/connected — ignoring")
            return
        }
        reconnectAttempts = 0
        connectJob?.cancel()
        connectJob = scope.launch { doConnect() }
    }

    override fun disconnect() {
        connectJob?.cancel()
        pollJob?.cancel()
        unregisterCallback()
        connectivityManager.bindProcessToNetwork(null)
        cozmoNetwork = null
        emit(ConnectionState.Disconnected)
    }

    override fun shutdown() {
        connectJob?.cancel()
        pollJob?.cancel()
        unregisterCallback()
        connectivityManager.bindProcessToNetwork(null)
        cozmoNetwork = null
        scope.cancel()
        emit(ConnectionState.Idle)
    }

    override fun createBoundSocket(): DatagramSocket {
        // Always try a fresh lookup — the stored reference may be stale if the OS
        // invalidated the Network object (common on Fire OS after ~10s with no internet).
        val fresh = findCozmoNetworkInConnectedNetworks()
        if (fresh != null && fresh != cozmoNetwork) {
            Log.d(TAG, "createBoundSocket: refreshed stale network reference")
            cozmoNetwork = fresh
            connectivityManager.bindProcessToNetwork(fresh)
        }
        val net = cozmoNetwork ?: throw CozmoNotConnectedException(
            "createBoundSocket() called but no Cozmo network available")
        return DatagramSocket().also { socket ->
            net.bindSocket(socket)
            Log.d(TAG, "UDP socket bound to Cozmo network")
        }
    }

    override fun createBoundTcpSocket(): Socket {
        val net = cozmoNetwork ?: throw CozmoNotConnectedException(
            "createBoundTcpSocket() called but cozmoNetwork is null — not connected")
        return net.socketFactory.createSocket().also {
            Log.d(TAG, "TCP socket created via Cozmo network socket factory")
        }
    }

    // ── Connection Logic ──────────────────────────────────────────────────────

    private suspend fun doConnect() {
        emit(ConnectionState.Scanning)
        Log.i(TAG, "Starting connection — SDK ${Build.VERSION.SDK_INT}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectApi30()
        } else {
            connectApi28()
        }
    }

    // ── API 30+ Path ──────────────────────────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun connectApi30() {
        Log.d(TAG, "Using API 30+ WifiNetworkSpecifier path")

        // Unregister any previous callback first
        unregisterCallback()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(android.os.PatternMatcher(
                COZMO_SSID_PREFIX, android.os.PatternMatcher.PATTERN_PREFIX))
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Cozmo has no internet
            .build()

        val deferred = CompletableDeferred<Network?>()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "onAvailable: Cozmo network found — $network")
                cozmoNetwork = network
                if (!deferred.isCompleted) deferred.complete(network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                // Network is ready once we receive capabilities — update reference
                cozmoNetwork = network
                Log.d(TAG, "onCapabilitiesChanged: network ready")
            }

            override fun onUnavailable() {
                Log.w(TAG, "onUnavailable: no matching Cozmo network found")
                if (!deferred.isCompleted) deferred.complete(null)
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "onLost: Cozmo network dropped")
                cozmoNetwork = null
                handleConnectionLost()
            }
        }

        networkCallback = callback
        emit(ConnectionState.Connecting)

        try {
            // IMPORTANT: requestNetwork with NetworkCallback — not requestNetwork with Handler.
            // This is the peer-to-peer local network request pattern for hotspot devices.
            connectivityManager.requestNetwork(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork failed: ${e.message}")
            triggerFallback()
            return
        }

        val network = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { deferred.await() }

        if (network != null) {
            Log.i(TAG, "Connected to Cozmo network")
            emit(ConnectionState.Connected)
        } else {
            Log.w(TAG, "Connection timed out — unregistering specifier and triggering fallback")
            unregisterCallback()
            triggerFallback()
        }
    }

    // ── API 28 Path ───────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private suspend fun connectApi28() {
        Log.d(TAG, "Using API 28 legacy WifiManager path")
        emit(ConnectionState.Connecting)

        withContext(Dispatchers.IO) {
            // Scan for Cozmo networks
            wifiManager.startScan()
            delay(3000) // give scan time to complete

            val cozmoResult = wifiManager.scanResults
                .firstOrNull { it.SSID.startsWith(COZMO_SSID_PREFIX) }

            if (cozmoResult == null) {
                Log.w(TAG, "No Cozmo SSID found in scan results")
                triggerFallback()
                return@withContext
            }

            Log.i(TAG, "Found Cozmo SSID: ${cozmoResult.SSID}")
            emit(ConnectionState.Found(listOf(cozmoResult.SSID)))

            val config = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"${cozmoResult.SSID}\""
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            }

            val netId = wifiManager.addNetwork(config).takeIf { it != -1 }
                ?: run { triggerFallback(); return@withContext }

            wifiManager.disconnect()
            wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()

            // Wait for the network to appear in ConnectivityManager
            var found: Network? = null
            repeat(10) {
                delay(1500)
                found = connectivityManager.allNetworks.firstOrNull { net ->
                    val caps = connectivityManager.getNetworkCapabilities(net) ?: return@firstOrNull false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        getCozmoSsidFromCaps(caps) != null
                }
                if (found != null) return@repeat
            }

            if (found != null) {
                // Restore default network to null so internet still works
                connectivityManager.bindProcessToNetwork(null)
                cozmoNetwork = found
                emit(ConnectionState.Connected)
            } else {
                triggerFallback()
            }
        }
    }

    // ── Fallback / Manual Polling ─────────────────────────────────────────────

    private fun triggerFallback() {
        Log.i(TAG, "Entering fallback — awaiting manual WiFi switch")
        emit(ConnectionState.FallbackRequired)
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        emit(ConnectionState.Polling)
        pollJob = scope.launch {
            repeat(POLL_MAX_ATTEMPTS) { attempt ->
                delay(POLL_INTERVAL_MS)
                Log.d(TAG, "Polling attempt ${attempt + 1}/$POLL_MAX_ATTEMPTS")

                val cozmoNet = findCozmoNetworkInConnectedNetworks()
                if (cozmoNet != null) {
                    Log.i(TAG, "Manual connection detected via poll")
                    cozmoNetwork = cozmoNet
                    // Bind the process so Fire OS doesn't reroute new connections.
                    connectivityManager.bindProcessToNetwork(cozmoNet)
                    Log.d(TAG, "Process bound to Cozmo network")
                    // Register a requestNetwork callback so the OS keeps this network alive.
                    // Without this, Fire OS aggressively drops WiFi with no internet after ~10s.
                    registerKeepAliveCallback()
                    emit(ConnectionState.Connected)
                    return@launch
                }
            }
            Log.w(TAG, "Polling timed out")
            emit(ConnectionState.TimedOut)
        }
    }

    /**
     * Finds a connected WiFi network whose SSID matches Cozmo_*.
     *
     * API 29+ compatible: reads SSID from NetworkCapabilities.transportInfo
     * (WifiInfo) rather than the deprecated ConnectivityManager.getNetworkInfo()
     * or NetworkInfo.extraInfo which are always blank on API 29+.
     */
    @Suppress("DEPRECATION")
    private fun findCozmoNetworkInConnectedNetworks(): Network? {
        return connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
            val ssid = getCozmoSsidFromCaps(caps) ?: return@firstOrNull false
            Log.d(TAG, "Poll found WiFi network with SSID: $ssid")
            ssid.startsWith(COZMO_SSID_PREFIX, ignoreCase = true)
        }
    }

    /**
     * Extracts the connected WiFi SSID from NetworkCapabilities.
     *
     * On API 29+, tries transportInfo (WifiInfo) first. Fire OS 8 may redact
     * the SSID as "<unknown ssid>" even with ACCESS_FINE_LOCATION granted, so
     * we fall back to WifiManager.connectionInfo which reads the real SSID.
     */
    @Suppress("DEPRECATION")
    private fun getCozmoSsidFromCaps(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps.transportInfo as? android.net.wifi.WifiInfo
            val ssid = wifiInfo?.ssid?.removeSurrounding("\"")
                ?.takeIf { it != "<unknown ssid>" }
            if (ssid != null) return ssid
            // Fire OS redacts transportInfo SSID — fall back to WifiManager
        }
        return wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }

    // ── Keep-alive ────────────────────────────────────────────────────────────

    /**
     * Registers a [ConnectivityManager.requestNetwork] callback targeting any WiFi
     * network without internet. This signals to the OS "don't drop this network" and
     * receives [onLost] if the robot's hotspot disappears.
     *
     * Must be called AFTER the Cozmo network is already found via polling, so the
     * OS can match the request to the already-connected Cozmo hotspot.
     */
    private fun registerKeepAliveCallback() {
        unregisterCallback() // clean up any previous (e.g. from prior attempt)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Keep-alive onAvailable: updating cozmoNetwork → $network")
                cozmoNetwork = network
                connectivityManager.bindProcessToNetwork(network)
                // Fire OS sometimes does a quick onLost+onAvailable cycle (OS re-grants the network
                // within a few seconds). If we're already in a reconnect flow, cancel it and recover.
                if (_connectionState.value is ConnectionState.Scanning) {
                    Log.i(TAG, "Keep-alive re-granted — recovering to Connected, cancelling reconnect")
                    connectJob?.cancel()
                    emit(ConnectionState.Connected)
                }
            }
            override fun onLost(network: Network) {
                Log.w(TAG, "Keep-alive onLost: Cozmo network dropped by OS")
                cozmoNetwork = null
                handleConnectionLost()
            }
        }

        networkCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback)
            Log.d(TAG, "Keep-alive network request registered")
        } catch (e: Exception) {
            Log.w(TAG, "registerKeepAliveCallback failed: ${e.message}")
        }
    }

    // ── Reconnection ──────────────────────────────────────────────────────────

    private fun handleConnectionLost() {
        if (reconnectAttempts >= RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached — falling back")
            triggerFallback()
            return
        }
        reconnectAttempts++
        Log.i(TAG, "Reconnecting (attempt $reconnectAttempts/$RECONNECT_ATTEMPTS)")
        emit(ConnectionState.Scanning)
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            doConnect()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun unregisterCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(TAG, "NetworkCallback unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "unregisterNetworkCallback failed (already unregistered?): ${e.message}")
            }
        }
        networkCallback = null
    }

    private fun emit(state: ConnectionState) {
        scope.launch(Dispatchers.Main) {
            _connectionState.value = state
            Log.d(TAG, "ConnectionState → $state")
        }
    }
}
