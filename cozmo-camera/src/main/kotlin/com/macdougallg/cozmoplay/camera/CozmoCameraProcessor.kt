package com.macdougallg.cozmoplay.camera

import android.graphics.Bitmap
import android.util.Log
import com.macdougallg.cozmoplay.protocol.ICozmoProtocol
import com.macdougallg.cozmoplay.types.CameraState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Concrete implementation of [ICozmoCamera].
 *
 * Pipeline:
 *   ICozmoProtocol.cameraFrames (Flow<Bitmap>, decoded JPEGs)
 *     → CozmoCameraProcessor (scale, FPS tracking, state machine)
 *       → displayFrames (Flow<Bitmap>, display-ready)
 *         → CozmoCamera composable (UI render)
 *
 * Threading:
 * - Frame collection and scaling: Dispatchers.Default
 * - StateFlow emissions: Dispatchers.Main
 * - All public methods: callable from any thread
 *
 * Memory:
 * - Double-buffer: one Bitmap being displayed, one being processed
 * - Previous display Bitmap recycled when next frame is ready
 * - All buffers released within 500ms of setEnabled(false)
 */
class CozmoCameraProcessor(
    private val protocol: ICozmoProtocol,
) : ICozmoCamera {

    companion object {
        private const val TAG = "CozmoCamera"
        private const val NO_FRAME_TIMEOUT_MS = 5_000L
        private const val FPS_WINDOW_SIZE = 30
        private const val DEFAULT_WIDTH = 320
        private const val DEFAULT_HEIGHT = 240
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val _isEnabled = MutableStateFlow(false)
    override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _currentFps = MutableStateFlow(0f)
    override val currentFps: StateFlow<Float> = _currentFps.asStateFlow()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Disabled)
    override val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _displayFrames = MutableSharedFlow<Bitmap>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val displayFrames: Flow<Bitmap> = _displayFrames.asSharedFlow()

    // ── Internal ──────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var displayWidthPx = DEFAULT_WIDTH
    private var displayHeightPx = DEFAULT_HEIGHT

    private var pipelineJob: Job? = null
    private var timeoutJob: Job? = null

    // FPS tracking — rolling window of frame timestamps
    private val frameTimestamps = ArrayDeque<Long>(FPS_WINDOW_SIZE + 1)

    // Double-buffer — previous frame held for recycling
    @Volatile private var previousDisplayBitmap: Bitmap? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    override fun setEnabled(enabled: Boolean) {
        if (_isEnabled.value == enabled) {
            Log.d(TAG, "setEnabled($enabled) called in same state — ignoring (idempotent)")
            return
        }
        _isEnabled.value = enabled
        if (enabled) startPipeline() else stopPipeline()
    }

    override fun setDisplaySize(widthPx: Int, heightPx: Int) {
        displayWidthPx = widthPx
        displayHeightPx = heightPx
        Log.d(TAG, "Display size set to ${widthPx}x${heightPx}")
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private fun startPipeline() {
        pipelineJob?.cancel()
        emitState(CameraState.Enabling)
        Log.i(TAG, "Camera pipeline starting")

        // Tell the protocol module to begin streaming
        protocol.enableCamera(true)
        startNoFrameTimeout()

        pipelineJob = scope.launch {
            try {
                protocol.cameraFrames
                    .onEach { bitmap ->
                        cancelNoFrameTimeout()
                        val scaled = scaleBitmap(bitmap)
                        recyclePrevious()
                        previousDisplayBitmap = scaled
                        _displayFrames.emit(scaled)
                        updateFps()
                        emitState(CameraState.Streaming(_currentFps.value))
                    }
                    .catch { e ->
                        Log.e(TAG, "Camera stream error: ${e.message}")
                        emitState(CameraState.StreamError(e.message ?: "Unknown stream error"))
                    }
                    .onCompletion {
                        if (_isEnabled.value) {
                            // Upstream ended unexpectedly while we were enabled
                            emitState(CameraState.StreamError("Stream ended unexpectedly"))
                        }
                    }
                    .collect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error: ${e.message}")
                emitState(CameraState.StreamError(e.message ?: "Pipeline error"))
            }
        }
    }

    private fun stopPipeline() {
        Log.i(TAG, "Camera pipeline stopping")
        cancelNoFrameTimeout()
        pipelineJob?.cancel()
        pipelineJob = null
        protocol.enableCamera(false)

        // Release bitmap buffers within 500ms (Camera PRD NFR-02)
        scope.launch {
            delay(100)
            recyclePrevious()
            frameTimestamps.clear()
            withContext(Dispatchers.Main) {
                _currentFps.value = 0f
            }
            emitState(CameraState.Disabled)
            Log.d(TAG, "Camera pipeline stopped, buffers released")
        }
    }

    fun pause() {
        pipelineJob?.cancel()
        emitState(CameraState.Paused)
        Log.d(TAG, "Camera paused (app backgrounded)")
    }

    fun resume() {
        if (_isEnabled.value) {
            Log.d(TAG, "Camera resuming")
            startPipeline()
        }
    }

    // ── Frame Processing ──────────────────────────────────────────────────────

    /**
     * Scale bitmap to target display size while maintaining 4:3 aspect ratio.
     * Returns a new Bitmap; caller is responsible for recycling the previous one.
     */
    private fun scaleBitmap(source: Bitmap): Bitmap {
        if (source.width == displayWidthPx && source.height == displayHeightPx) {
            return source
        }
        // Maintain 4:3 aspect ratio — fit within target bounds
        val srcRatio = source.width.toFloat() / source.height.toFloat()
        val dstRatio = displayWidthPx.toFloat() / displayHeightPx.toFloat()

        val (scaledW, scaledH) = if (srcRatio > dstRatio) {
            displayWidthPx to (displayWidthPx / srcRatio).toInt()
        } else {
            (displayHeightPx * srcRatio).toInt() to displayHeightPx
        }

        return Bitmap.createScaledBitmap(source, scaledW, scaledH, false)
    }

    private fun recyclePrevious() {
        previousDisplayBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
                Log.v(TAG, "Recycled previous display bitmap")
            }
        }
        previousDisplayBitmap = null
    }

    // ── FPS Tracking ──────────────────────────────────────────────────────────

    private fun updateFps() {
        val now = System.currentTimeMillis()
        frameTimestamps.addLast(now)
        if (frameTimestamps.size > FPS_WINDOW_SIZE) frameTimestamps.removeFirst()

        if (frameTimestamps.size >= 2) {
            val windowMs = frameTimestamps.last() - frameTimestamps.first()
            val fps = if (windowMs > 0) {
                (frameTimestamps.size - 1) * 1000f / windowMs
            } else 0f

            scope.launch(Dispatchers.Main) {
                _currentFps.value = fps
            }
        }
    }

    // ── No-Frame Timeout ──────────────────────────────────────────────────────

    private fun startNoFrameTimeout() {
        cancelNoFrameTimeout()
        timeoutJob = scope.launch {
            delay(NO_FRAME_TIMEOUT_MS)
            if (_isEnabled.value && _cameraState.value is CameraState.Enabling) {
                Log.w(TAG, "No frames received within ${NO_FRAME_TIMEOUT_MS}ms — STREAM_ERROR")
                emitState(CameraState.StreamError("No frames received after ${NO_FRAME_TIMEOUT_MS}ms"))
            }
        }
    }

    private fun cancelNoFrameTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun emitState(state: CameraState) {
        scope.launch(Dispatchers.Main) {
            _cameraState.value = state
        }
    }

    fun shutdown() {
        stopPipeline()
        scope.cancel()
    }
}
