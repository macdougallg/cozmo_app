package com.macdougallg.cozmoplay.types

/**
 * Represents all possible states of the WiFi connection to the Cozmo robot.
 * Owned by cozmo-wifi; consumed by cozmo-protocol and app (UI).
 *
 * State machine:
 *   IDLE → SCANNING → FOUND / NOT_FOUND
 *   FOUND → CONNECTING → CONNECTED / FALLBACK_REQUIRED
 *   NOT_FOUND → FALLBACK_REQUIRED
 *   FALLBACK_REQUIRED → POLLING → CONNECTED / TIMED_OUT
 *   CONNECTED → DISCONNECTED → SCANNING / IDLE
 *   Any state → ERROR (unrecoverable)
 */
sealed class ConnectionState {
    /** No connection attempt in progress. */
    object Idle : ConnectionState()

    /** Scanning for Cozmo_XXXXXX WiFi networks. */
    object Scanning : ConnectionState()

    /** One or more Cozmo SSIDs found; auto-connect in progress. */
    data class Found(val ssids: List<String>) : ConnectionState()

    /** Scan completed; no Cozmo_* SSID detected. */
    object NotFound : ConnectionState()

    /** Network request submitted; awaiting onAvailable() callback. */
    object Connecting : ConnectionState()

    /** Network available and sockets ready. */
    object Connected : ConnectionState()

    /**
     * Auto-connect failed; UI should show manual WiFi switching guide.
     * Module will begin polling via pollForCozmoConnection().
     */
    object FallbackRequired : ConnectionState()

    /** Polling every 2 seconds for manual user connection (max 60s). */
    object Polling : ConnectionState()

    /** Previously connected; link lost. Reconnection may be attempted. */
    object Disconnected : ConnectionState()

    /** Connection window expired without success. */
    object TimedOut : ConnectionState()

    /** Unrecoverable error (e.g. permission denied, WiFi disabled). */
    data class Error(val reason: ConnectionError) : ConnectionState()
}

enum class ConnectionError {
    WIFI_DISABLED,
    PERMISSION_DENIED,
    NETWORK_REJECTED,
    UNKNOWN
}
