package com.macdougallg.cozmoplay.types

/**
 * Live state of a single Cozmo light cube.
 * Updated from RobotObservedObject and related protocol events.
 */
data class CubeState(
    /** Logical cube ID (1–3). Stable across sessions for the same physical cube. */
    val cubeId: Int,
    /** Robot-internal object ID used in command messages (e.g. pickupObject). */
    val objectId: Int,
    /** True when the robot can currently see this cube. */
    val isVisible: Boolean,
    /** True when this cube is currently on the robot's lift. */
    val isBeingCarried: Boolean,
    /** Current light state of this cube. */
    val lightState: CubeLightState,
    /** Signal strength 0.0 (weakest) to 1.0 (strongest). */
    val signalStrength: Float,
    /** System.currentTimeMillis() at last observation event. */
    val lastSeenMs: Long,
)

/**
 * Current light pattern being displayed on a cube.
 */
data class CubeLightState(
    /** Packed ARGB colour currently shown. */
    val color: Int,
    val isFlashing: Boolean,
    val flashOnMs: Int,
    val flashOffMs: Int,
)

/**
 * Configuration to send to a cube via setCubeLights().
 * Four lights per cube; pass a list of up to 4 CubeLightConfig objects.
 */
data class CubeLightConfig(
    /** Packed ARGB colour. Alpha is ignored by the hardware. */
    val color: Int,
    /** Duration the light stays on per cycle, in milliseconds. */
    val onPeriodMs: Int = 500,
    /** Duration the light stays off per cycle, in milliseconds. */
    val offPeriodMs: Int = 500,
    /** Crossfade transition time in milliseconds. */
    val transitionMs: Int = 100,
)
