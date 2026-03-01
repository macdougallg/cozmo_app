package com.macdougallg.cozmoplay.types

/**
 * State of the cozmo-camera module's decode pipeline.
 * Exposed as StateFlow<CameraState> by ICozmoCamera.
 */
sealed class CameraState {
    /** Camera is switched off. Show 'Camera Off' panel in UI. */
    object Disabled : CameraState()

    /** Enable command sent; waiting for first decoded frame. Show spinner. */
    object Enabling : CameraState()

    /**
     * Frames are being received and decoded successfully.
     * @param fps Rolling average frame rate over the last 30 frames.
     */
    data class Streaming(val fps: Float) : CameraState()

    /**
     * Stream was interrupted or a fatal decode error occurred.
     * @param reason Human-readable debug description (not shown to child).
     */
    data class StreamError(val reason: String) : CameraState()

    /** App is backgrounded; decode pipeline is suspended. Internal state. */
    object Paused : CameraState()
}
