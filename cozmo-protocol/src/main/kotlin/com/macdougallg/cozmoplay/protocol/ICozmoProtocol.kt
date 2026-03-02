package com.macdougallg.cozmoplay.protocol

import android.graphics.Bitmap
import com.macdougallg.cozmoplay.types.*
import com.macdougallg.cozmoplay.wifi.ICozmoWifi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public interface for the Cozmo protocol engine.
 *
 * All consumers (app UI, cozmo-camera) MUST depend on this interface only.
 * Locked at v1.0 per the API Contract — no changes without Orchestrator sign-off.
 *
 * Threading contract:
 * - All StateFlow emissions arrive on Dispatchers.Main
 * - All suspend functions are cancellable
 * - All fire-and-forget commands return immediately; I/O happens on Dispatchers.IO
 */
interface ICozmoProtocol {

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Perform the Cozmo handshake and start the receive + ping loops.
     * Suspends until CONNECTED or ERROR state is reached.
     * @param wifi The connected WiFi module; sockets are obtained from here.
     */
    suspend fun connect(wifi: ICozmoWifi)

    /**
     * Send DISCONNECT message, stop all loops, release resources.
     * Safe to call from any state.
     */
    suspend fun disconnect()

    // ── Observable State ──────────────────────────────────────────────────────

    /** Protocol connection lifecycle state. Emits on Dispatchers.Main. */
    val protocolState: StateFlow<ProtocolState>

    /** Live robot telemetry, updated at ~60Hz. Emits on Dispatchers.Main. */
    val robotState: StateFlow<RobotState>

    /** Live cube observations. Updated on ObservedObject events. Emits on Dispatchers.Main. */
    val cubeStates: StateFlow<List<CubeState>>

    // ── Drive & Movement ──────────────────────────────────────────────────────

    /**
     * Set left and right wheel speeds directly.
     * @param leftMmps Left wheel speed in mm/s. Range: -220 to 220.
     * @param rightMmps Right wheel speed in mm/s. Range: -220 to 220.
     */
    fun driveWheels(leftMmps: Float, rightMmps: Float)

    /** Immediately stop all motors and cancel pending movement. */
    fun stopAllMotors()

    /**
     * Move head to an absolute angle.
     * @param angleRad Target angle in radians. Range: -0.44 to 0.78.
     */
    fun setHeadAngle(angleRad: Float)

    /**
     * Continuous head movement at a given speed.
     * @param speedRadPerSec Movement speed. Pass 0f to stop.
     */
    fun moveHead(speedRadPerSec: Float)

    /**
     * Move lift to an absolute height.
     * @param heightMm Target height in mm. Range: 32 to 92.
     */
    fun setLiftHeight(heightMm: Float)

    /**
     * Continuous lift movement at a given speed.
     * @param speedRadPerSec Movement speed. Pass 0f to stop.
     */
    fun moveLift(speedRadPerSec: Float)

    // ── Animations ────────────────────────────────────────────────────────────

    /** All animation names known to be available on the robot. */
    val availableAnimations: List<String>

    /**
     * Play a named animation. Suspends until AnimationCompleted is received.
     * @param name Animation name from [availableAnimations].
     * @return [ActionResult.Success], [ActionResult.Failure], or [ActionResult.Timeout] after 15s.
     */
    suspend fun playAnimation(name: String): ActionResult

    /**
     * Play a random animation from a named group.
     * @param groupName Animation group name.
     * @return [ActionResult] when the chosen animation completes.
     */
    suspend fun playAnimationGroup(groupName: String): ActionResult

    // ── Cube Commands ─────────────────────────────────────────────────────────

    /**
     * Set light colour and flash pattern on a cube.
     * @param objectId Robot-internal object ID from [CubeState.objectId].
     * @param lights List of up to 4 [CubeLightConfig] entries (one per light).
     */
    fun setCubeLights(objectId: Int, lights: List<CubeLightConfig>)

    /**
     * Command the robot to drive to and pick up a cube.
     * Suspends until RobotCompletedAction is received.
     * @param objectId Robot-internal object ID from [CubeState.objectId].
     * @return [ActionResult] — times out after 15 seconds.
     */
    suspend fun pickupObject(objectId: Int): ActionResult

    /**
     * Command the robot to place the currently held object on the ground.
     * @return [ActionResult] — times out after 15 seconds.
     */
    suspend fun placeObject(): ActionResult

    /**
     * Command the robot to roll a cube.
     * @param objectId Robot-internal object ID from [CubeState.objectId].
     * @return [ActionResult] — times out after 15 seconds.
     */
    suspend fun rollObject(objectId: Int): ActionResult

    // ── Camera ────────────────────────────────────────────────────────────────

    /**
     * Enable or disable the robot's camera stream.
     * When enabled, color frames are requested; decoded Bitmaps emit on [cameraFrames].
     */
    fun enableCamera(enabled: Boolean)

    /**
     * Enable or disable night vision mode.
     * Turns the forehead LED on/off and adjusts camera gain/exposure accordingly.
     */
    fun setNightVision(enabled: Boolean)

    /**
     * Stream of decoded camera frames. Consumed by cozmo-camera module.
     * Emits Bitmaps decoded from incoming CameraImage protocol messages.
     * Target: 15 fps minimum when enabled.
     */
    val cameraFrames: Flow<Bitmap>

    // ── Freeplay ──────────────────────────────────────────────────────────────

    /**
     * Enable or disable Cozmo's autonomous freeplay behaviour.
     * When enabled, the robot wanders and reacts to its environment independently.
     */
    fun enableFreeplay(enabled: Boolean)
}
