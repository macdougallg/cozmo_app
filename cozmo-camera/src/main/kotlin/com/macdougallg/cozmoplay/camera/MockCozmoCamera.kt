package com.macdougallg.cozmoplay.camera

import android.graphics.Bitmap
import com.macdougallg.cozmoplay.types.CameraState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Test double for [ICozmoCamera].
 *
 * Used by Agent 3 (UI) and Agent 5 (QA) to test camera-dependent screens
 * without a physical robot or real JPEG decode pipeline.
 */
class MockCozmoCamera : ICozmoCamera {

    private val _isEnabled = MutableStateFlow(false)
    override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _currentFps = MutableStateFlow(0f)
    override val currentFps: StateFlow<Float> = _currentFps.asStateFlow()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Disabled)
    override val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _isNightVision = MutableStateFlow(false)
    override val isNightVision: StateFlow<Boolean> = _isNightVision.asStateFlow()

    private val _displayFrames = MutableSharedFlow<Bitmap>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    override val displayFrames: Flow<Bitmap> = _displayFrames.asSharedFlow()

    // Recorded calls
    var setEnabledCallCount = 0
        private set
    var lastEnabledValue: Boolean? = null
        private set
    var lastDisplaySize: Pair<Int, Int>? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var frameStreamJob: Job? = null

    override fun setEnabled(enabled: Boolean) {
        setEnabledCallCount++
        lastEnabledValue = enabled
        _isEnabled.value = enabled
        _cameraState.value = if (enabled) CameraState.Streaming(15f) else CameraState.Disabled
        _currentFps.value = if (enabled) 15f else 0f
    }

    override fun setDisplaySize(widthPx: Int, heightPx: Int) {
        lastDisplaySize = Pair(widthPx, heightPx)
    }

    override fun setNightVision(enabled: Boolean) {
        _isNightVision.value = enabled
    }

    // ── Simulation ────────────────────────────────────────────────────────────

    /**
     * Starts emitting synthetic 1x1 pixel Bitmaps at [fps] rate.
     * Simulates a live stream for UI and integration tests.
     */
    fun simulateFrameStream(fps: Float = 15f) {
        frameStreamJob?.cancel()
        _cameraState.value = CameraState.Streaming(fps)
        _currentFps.value = fps
        val intervalMs = (1000f / fps).toLong()
        frameStreamJob = scope.launch {
            while (isActive) {
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.RGB_565)
                _displayFrames.emit(bitmap)
                delay(intervalMs)
            }
        }
    }

    fun stopFrameStream() {
        frameStreamJob?.cancel()
        frameStreamJob = null
        _cameraState.value = CameraState.Disabled
        _currentFps.value = 0f
    }

    suspend fun injectFrame(bitmap: Bitmap) {
        _displayFrames.emit(bitmap)
    }

    fun simulateError(reason: String = "Simulated stream error") {
        _cameraState.value = CameraState.StreamError(reason)
    }

    fun simulateEnabling() {
        _cameraState.value = CameraState.Enabling
    }

    fun simulatePaused() {
        _cameraState.value = CameraState.Paused
    }

    fun reset() {
        stopFrameStream()
        _isEnabled.value = false
        _cameraState.value = CameraState.Disabled
        _currentFps.value = 0f
        setEnabledCallCount = 0
        lastEnabledValue = null
        lastDisplaySize = null
    }
}
