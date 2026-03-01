package com.macdougallg.cozmoplay.ui.screens.connect

import androidx.lifecycle.ViewModel
import com.macdougallg.cozmoplay.types.ConnectionState
import com.macdougallg.cozmoplay.wifi.ICozmoWifi
import kotlinx.coroutines.flow.StateFlow

class ConnectViewModel(private val wifi: ICozmoWifi) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = wifi.connectionState

    fun connect() { wifi.connect() }

    fun triggerManualFallback() { wifi.connect() }
}
