package com.macdougallg.cozmoplay.camera

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.macdougallg.cozmoplay.types.CameraState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public interface for the Cozmo camera module.
 *
 * Locked at v1.0 per the API Contract (section 6).
 * Agent 3 (UI) depends on this interface and [CozmoCamera] composable only —
 * never on the concrete implementation or raw Bitmap types.
 *
 * Threading: all StateFlow emissions arrive on Dispatchers.Main.
 */
interface ICozmoCamera {

    /** Whether the camera stream is currently active. */
    val isEnabled: StateFlow<Boolean>

    /**
     * Display-ready Bitmap frames, scaled and rotation-corrected.
     * Target: 15 fps minimum when enabled.
     * Collect on Dispatchers.Main in UI.
     */
    val displayFrames: Flow<Bitmap>

    /** Live achieved frame rate. 0f when disabled. */
    val currentFps: StateFlow<Float>

    /** Full camera pipeline state for UI rendering decisions. */
    val cameraState: StateFlow<CameraState>

    /**
     * Start or stop the camera stream.
     * Idempotent — safe to call in same state.
     */
    fun setEnabled(enabled: Boolean)

    /**
     * Set target display resolution for frame scaling.
     * Default: 320x240 (Cozmo native resolution).
     */
    fun setDisplaySize(widthPx: Int, heightPx: Int)

    /** Whether night vision mode (head LED + boosted exposure) is active. */
    val isNightVision: StateFlow<Boolean>

    /**
     * Enable or disable night vision mode.
     * Turns the forehead LED on/off and adjusts camera gain/exposure.
     */
    fun setNightVision(enabled: Boolean)
}
