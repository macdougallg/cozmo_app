package com.macdougallg.cozmoplay.camera

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macdougallg.cozmoplay.types.CameraState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for any screen that embeds [CozmoCamera].
 *
 * Bridges [ICozmoCamera] state to the composable layer.
 * Consumed by DriveScreen and ExploreScreen via Koin injection.
 */
class CameraViewModel(private val camera: ICozmoCamera) : ViewModel() {

    val cameraState: StateFlow<CameraState> = camera.cameraState

    val isEnabled: StateFlow<Boolean> = camera.isEnabled

    val currentFps: StateFlow<Float> = camera.currentFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    /** Display-ready frames — collect in Composable with collectAsState(). */
    val displayFrames: Flow<Bitmap> = camera.displayFrames

    fun setEnabled(enabled: Boolean) {
        camera.setEnabled(enabled)
    }

    fun setDisplaySize(widthPx: Int, heightPx: Int) {
        camera.setDisplaySize(widthPx, heightPx)
    }

    override fun onCleared() {
        super.onCleared()
        // Do not call shutdown() — camera is a singleton that may outlive this ViewModel
        // (DriveScreen and ExploreScreen both share the same ICozmoCamera instance)
    }
}
