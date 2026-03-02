package com.macdougallg.cozmoplay.protocol

import android.graphics.Bitmap
import com.macdougallg.cozmoplay.types.*
import com.macdougallg.cozmoplay.wifi.ICozmoWifi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/**
 * Test double for [ICozmoProtocol].
 *
 * Used by Agent 3 (UI), Agent 4 (camera), and Agent 5 (QA) during parallel development.
 * Provides full simulation control over all protocol state without real hardware.
 *
 * Also records all commands received — use the recorded* properties to assert
 * that the UI sent the right commands in tests.
 */
class MockCozmoProtocol : ICozmoProtocol {

    // ── Observable State ──────────────────────────────────────────────────────

    private val _protocolState = MutableStateFlow<ProtocolState>(ProtocolState.Idle)
    override val protocolState: StateFlow<ProtocolState> = _protocolState.asStateFlow()

    private val _robotState = MutableStateFlow(RobotState())
    override val robotState: StateFlow<RobotState> = _robotState.asStateFlow()

    private val _cubeStates = MutableStateFlow<List<CubeState>>(emptyList())
    override val cubeStates: StateFlow<List<CubeState>> = _cubeStates.asStateFlow()

    private val _cameraFrames = MutableSharedFlow<Bitmap>(
        replay = 1,
        extraBufferCapacity = 8,
    )
    override val cameraFrames: Flow<Bitmap> = _cameraFrames.asSharedFlow()

    override val availableAnimations: List<String> = com.macdougallg.cozmoplay.protocol.messages.AnimationManifest.ALL

    // ── Recorded Commands (for test assertions) ───────────────────────────────

    private val _driveCommands = mutableListOf<Pair<Float, Float>>()
    val driveCommands: List<Pair<Float, Float>> get() = _driveCommands.toList()

    var stopAllMotorsCallCount = 0
        private set
    var lastAnimationPlayed: String? = null
        private set
    var lastCubeLightSet: Pair<Int, List<CubeLightConfig>>? = null
        private set
    var lastHeadAngle: Float? = null
        private set
    var lastLiftHeight: Float? = null
        private set
    var cameraEnabled: Boolean = false
        private set
    var freeplayEnabled: Boolean = false
        private set
    var connectCallCount = 0
        private set
    var disconnectCallCount = 0
        private set

    // Pending action result — set before calling action commands in tests
    var nextActionResult: ActionResult = ActionResult.Success
    var nextActionDelayMs: Long = 100L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun connect(wifi: ICozmoWifi) {
        connectCallCount++
        _protocolState.value = ProtocolState.Connected
    }

    override suspend fun disconnect() {
        disconnectCallCount++
        _protocolState.value = ProtocolState.Disconnected
    }

    // ── Drive ─────────────────────────────────────────────────────────────────

    override fun driveWheels(leftMmps: Float, rightMmps: Float) {
        _driveCommands.add(Pair(leftMmps, rightMmps))
    }

    override fun stopAllMotors() {
        stopAllMotorsCallCount++
    }

    override fun setHeadAngle(angleRad: Float) { lastHeadAngle = angleRad }
    override fun moveHead(speedRadPerSec: Float) {}
    override fun setLiftHeight(heightMm: Float) { lastLiftHeight = heightMm }
    override fun moveLift(speedRadPerSec: Float) {}

    // ── Animations ────────────────────────────────────────────────────────────

    override suspend fun playAnimation(name: String): ActionResult {
        lastAnimationPlayed = name
        delay(nextActionDelayMs)
        return nextActionResult
    }

    override suspend fun playAnimationGroup(groupName: String): ActionResult {
        lastAnimationPlayed = groupName
        delay(nextActionDelayMs)
        return nextActionResult
    }

    // ── Cube Commands ─────────────────────────────────────────────────────────

    override fun setCubeLights(objectId: Int, lights: List<CubeLightConfig>) {
        lastCubeLightSet = Pair(objectId, lights)
    }

    override suspend fun pickupObject(objectId: Int): ActionResult {
        delay(nextActionDelayMs)
        return nextActionResult
    }

    override suspend fun placeObject(): ActionResult {
        delay(nextActionDelayMs)
        return nextActionResult
    }

    override suspend fun rollObject(objectId: Int): ActionResult {
        delay(nextActionDelayMs)
        return nextActionResult
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    override fun enableCamera(enabled: Boolean) { cameraEnabled = enabled }

    var nightVisionEnabled: Boolean = false
        private set
    override fun setNightVision(enabled: Boolean) { nightVisionEnabled = enabled }

    // ── Freeplay ──────────────────────────────────────────────────────────────

    override fun enableFreeplay(enabled: Boolean) { freeplayEnabled = enabled }

    // ── Simulation Methods ────────────────────────────────────────────────────

    fun simulateConnected() {
        _protocolState.value = ProtocolState.Connected
    }

    fun simulateDisconnected() {
        _protocolState.value = ProtocolState.Disconnected
    }

    fun simulateError(error: ProtocolError) {
        _protocolState.value = ProtocolState.Error(error)
    }

    fun simulateRobotState(state: RobotState) {
        _robotState.value = state
    }

    fun simulateEmotionChange(emotion: Emotion) {
        _robotState.value = _robotState.value.copy(emotion = emotion)
    }

    fun simulateCubeDetected(cube: CubeState) {
        val current = _cubeStates.value.toMutableList()
        val idx = current.indexOfFirst { it.cubeId == cube.cubeId }
        if (idx >= 0) current[idx] = cube else current.add(cube)
        _cubeStates.value = current
    }

    fun simulateCubeHidden(cubeId: Int) {
        val current = _cubeStates.value.toMutableList()
        val idx = current.indexOfFirst { it.cubeId == cubeId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(isVisible = false)
            _cubeStates.value = current
        }
    }

    suspend fun simulateCameraFrame(bitmap: Bitmap) {
        _cameraFrames.emit(bitmap)
    }

    fun simulateAnimationComplete(name: String, result: ActionResult = ActionResult.Success) {
        // Directly completes the animation — use when you need synchronous test control
        lastAnimationPlayed = name
        nextActionResult = result
    }

    fun simulateIsMoving(moving: Boolean) {
        _robotState.value = _robotState.value.copy(isMoving = moving)
    }

    fun simulateIsAnimating(animating: Boolean) {
        _robotState.value = _robotState.value.copy(isAnimating = animating)
    }

    fun simulateBatteryLow() {
        _robotState.value = _robotState.value.copy(batteryVoltage = 3.4f)
    }

    fun reset() {
        _protocolState.value = ProtocolState.Idle
        _robotState.value = RobotState()
        _cubeStates.value = emptyList()
        _driveCommands.clear()
        stopAllMotorsCallCount = 0
        lastAnimationPlayed = null
        lastCubeLightSet = null
        lastHeadAngle = null
        lastLiftHeight = null
        cameraEnabled = false
        nightVisionEnabled = false
        freeplayEnabled = false
        connectCallCount = 0
        disconnectCallCount = 0
        nextActionResult = ActionResult.Success
        nextActionDelayMs = 100L
    }
}
