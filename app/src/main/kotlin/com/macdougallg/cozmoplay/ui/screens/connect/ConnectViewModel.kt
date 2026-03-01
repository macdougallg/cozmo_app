package com.macdougallg.cozmoplay.ui.screens.connect

import androidx.lifecycle.ViewModel
import com.macdougallg.cozmoplay.wifi.ICozmoWifi
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for ConnectScreen.
 * Bridges ICozmoWifi state to the UI. Contains no business logic beyond delegation.
 */
class ConnectViewModel(private val wifi: ICozmoWifi) : ViewModel() {

    val connectionState: StateFlow<com.macdougallg.cozmoplay.types.ConnectionState> =
        wifi.connectionState

    fun connect() {
        wifi.connect()
    }

    fun triggerManualFallback() {
        // The WiFi module handles fallback state internally after a timeout.
        // This call allows the child to manually trigger the fallback guide
        // without waiting for the auto-timeout.
        wifi.connect() // Re-trigger; wifi module will move to FallbackRequired if appropriate
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT call wifi.shutdown() here — the WiFi manager is a singleton
        // that outlives this ViewModel. Shutdown is called from Application.onTerminate().
    }
}
