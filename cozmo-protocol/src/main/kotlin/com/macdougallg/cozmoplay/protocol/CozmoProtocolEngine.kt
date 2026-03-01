package com.macdougallg.cozmoplay.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.macdougallg.cozmoplay.protocol.framing.FrameCodec
import com.macdougallg.cozmoplay.protocol.messages.*
import com.macdougallg.cozmoplay.types.*
import com.macdougallg.cozmoplay.wifi.ICozmoWifi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Full implementation of [ICozmoProtocol].
 *
 * Architecture:
 * - One coroutine for the receive loop (Dispatchers.IO)
 * - One coroutine for the send queue (single-threaded, guarantees ordering)
 * - One coroutine for the ping loop (500ms interval)
 * - One coroutine for camera JPEG decoding (Dispatchers.Default)
 *
 * All StateFlow emissions are delivered on Dispatchers.Main.
 * All suspend action functions (playAnimation, pickupObject, etc.) use
 * CompletableDeferred to await their completion event from the receive loop.
 */
class CozmoProtocolEngine : ICozmoProtocol {

    companion object {
        private const val TAG = "CozmoProtocol"
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val HANDSHAKE_MAX_RETRIES = 3
        private const val PING_INTERVAL_MS = 500L
        private const val PONG_MISS_LIMIT = 3
        private const val ACTION_TIMEOUT_MS = 15_000L
        private const val DRIVE_RATE_LIMIT_HZ = 60
        private const val RECEIVE_MAX_RESTARTS = 5
        private const val CAMERA_FRAME_MAX_AGE_MS = 200L
        private const val UDP_BUFFER_SIZE = 65_535
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val _protocolState = MutableStateFlow<ProtocolState>(ProtocolState.Idle)
    override val protocolState: StateFlow<ProtocolState> = _protocolState.asStateFlow()

    private val _robotState = MutableStateFlow(RobotState())
    override val robotState: StateFlow<RobotState> = _robotState.asStateFlow()

    private val _cubeStates = MutableStateFlow<List<CubeState>>(emptyList())
    override val cubeStates: StateFlow<List<CubeState>> = _cubeStates.asStateFlow()

    override val availableAnimations: List<String> = AnimationManifest.ALL

    // ── Camera ────────────────────────────────────────────────────────────────

    private val _cameraFrames = MutableSharedFlow<Bitmap>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val cameraFrames: Flow<Bitmap> = _cameraFrames.asSharedFlow()

    // ── Internal ──────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob())

    // Single-threaded send dispatcher — guarantees command ordering (API Contract §8)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var socket: DatagramSocket? = null
    private var robotAddress: InetAddress? = null
    private var robotPort: Int = 0

    private val frameId = AtomicInteger(1)
    private val ackId = AtomicInteger(0)
    private val pingCounter = AtomicInteger(0)
    private val missedPongs = AtomicInteger(0)

    private var receiveJob: Job? = null
    private var pingJob: Job? = null
    private var receiveRestarts = 0

    // Drive rate limiting
    private val lastDriveSendMs = AtomicLong(0)
    private val driveIntervalMs = 1000L / DRIVE_RATE_LIMIT_HZ

    // Pending action completions — keyed by type string
    private var pendingAnimation: CompletableDeferred<ActionResult>? = null
    private var pendingAction: CompletableDeferred<ActionResult>? = null

    // Cube object ID tracking: robotObjectId → cubeId
    private val objectIdToCubeId = mutableMapOf<Int, Int>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun connect(wifi: ICozmoWifi) {
        emitState(ProtocolState.Connecting)
        Log.i(TAG, "Starting protocol connection")

        val sock = withContext(Dispatchers.IO) { wifi.createBoundSocket() }
        socket = sock
        robotAddress = InetAddress.getByName(wifi.cozmoIpAddress)
        robotPort = wifi.cozmoPort

        // Handshake with retries
        repeat(HANDSHAKE_MAX_RETRIES) { attempt ->
            Log.d(TAG, "Handshake attempt ${attempt + 1}/$HANDSHAKE_MAX_RETRIES")
            sendRaw(MessageIds.CONNECT, MessageBuilder.connect())

            val connected = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                waitForConnected()
            }

            if (connected != null) {
                Log.i(TAG, "Handshake complete — robot ID ${connected.robotId}")
                emitState(ProtocolState.Connected)
                startReceiveLoop()
                startPingLoop()
                return
            }
        }

        Log.e(TAG, "Handshake failed after $HANDSHAKE_MAX_RETRIES attempts")
        emitState(ProtocolState.Error(ProtocolError.HANDSHAKE_TIMEOUT))
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Disconnecting")
        try { sendRaw(MessageIds.DISCONNECT, MessageBuilder.disconnect()) } catch (_: Exception) {}
        receiveJob?.cancel()
        pingJob?.cancel()
        socket?.close()
        socket = null
        pendingAnimation?.cancel()
        pendingAction?.cancel()
        emitState(ProtocolState.Disconnected)
    }

    // ── Drive Commands ────────────────────────────────────────────────────────

    override fun driveWheels(leftMmps: Float, rightMmps: Float) {
        if (!isConnected()) return
        val now = System.currentTimeMillis()
        if (now - lastDriveSendMs.get() < driveIntervalMs) return
        lastDriveSendMs.set(now)
        send(MessageIds.DRIVE_WHEELS, MessageBuilder.driveWheels(leftMmps, rightMmps))
    }

    override fun stopAllMotors() {
        if (!isConnected()) return
        send(MessageIds.STOP_ALL_MOTORS, MessageBuilder.stopAllMotors())
    }

    override fun setHeadAngle(angleRad: Float) {
        if (!isConnected()) return
        send(MessageIds.SET_HEAD_ANGLE, MessageBuilder.setHeadAngle(angleRad))
    }

    override fun moveHead(speedRadPerSec: Float) {
        if (!isConnected()) return
        send(MessageIds.MOVE_HEAD, MessageBuilder.moveHead(speedRadPerSec))
    }

    override fun setLiftHeight(heightMm: Float) {
        if (!isConnected()) return
        send(MessageIds.SET_LIFT_HEIGHT, MessageBuilder.setLiftHeight(heightMm))
    }

    override fun moveLift(speedRadPerSec: Float) {
        if (!isConnected()) return
        send(MessageIds.MOVE_LIFT, MessageBuilder.moveLift(speedRadPerSec))
    }

    // ── Animations ────────────────────────────────────────────────────────────

    override suspend fun playAnimation(name: String): ActionResult {
        if (!isConnected()) return ActionResult.Failure("Not connected")
        val deferred = CompletableDeferred<ActionResult>()
        pendingAnimation = deferred
        send(MessageIds.PLAY_ANIMATION, MessageBuilder.playAnimation(name))
        return try {
            withTimeout(ACTION_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            pendingAnimation = null
            ActionResult.Timeout
        }
    }

    override suspend fun playAnimationGroup(groupName: String): ActionResult {
        if (!isConnected()) return ActionResult.Failure("Not connected")
        val deferred = CompletableDeferred<ActionResult>()
        pendingAnimation = deferred
        send(MessageIds.PLAY_ANIMATION_GROUP, MessageBuilder.playAnimationGroup(groupName))
        return try {
            withTimeout(ACTION_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            pendingAnimation = null
            ActionResult.Timeout
        }
    }

    // ── Cube Commands ─────────────────────────────────────────────────────────

    override fun setCubeLights(objectId: Int, lights: List<CubeLightConfig>) {
        if (!isConnected()) return
        send(MessageIds.SET_CUBE_LIGHTS, MessageBuilder.setCubeLights(objectId, lights))
    }

    override suspend fun pickupObject(objectId: Int): ActionResult {
        if (!isConnected()) return ActionResult.Failure("Not connected")
        val deferred = CompletableDeferred<ActionResult>()
        pendingAction = deferred
        send(MessageIds.PICKUP_OBJECT, MessageBuilder.pickupObject(objectId))
        return awaitAction(deferred)
    }

    override suspend fun placeObject(): ActionResult {
        if (!isConnected()) return ActionResult.Failure("Not connected")
        val deferred = CompletableDeferred<ActionResult>()
        pendingAction = deferred
        send(MessageIds.PLACE_OBJECT, MessageBuilder.placeObject())
        return awaitAction(deferred)
    }

    override suspend fun rollObject(objectId: Int): ActionResult {
        if (!isConnected()) return ActionResult.Failure("Not connected")
        val deferred = CompletableDeferred<ActionResult>()
        pendingAction = deferred
        send(MessageIds.ROLL_OBJECT, MessageBuilder.rollObject(objectId))
        return awaitAction(deferred)
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    override fun enableCamera(enabled: Boolean) {
        if (!isConnected()) return
        send(MessageIds.ENABLE_CAMERA, MessageBuilder.enableCamera(enabled))
    }

    // ── Freeplay ──────────────────────────────────────────────────────────────

    override fun enableFreeplay(enabled: Boolean) {
        if (!isConnected()) return
        send(MessageIds.ENABLE_FREEPLAY, MessageBuilder.enableFreeplay(enabled))
    }

    // ── Receive Loop ──────────────────────────────────────────────────────────

    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(UDP_BUFFER_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            Log.d(TAG, "Receive loop started")

            while (isActive) {
                try {
                    socket?.receive(packet) ?: break
                    val data = buf.copyOf(packet.length)
                    val decoded = FrameCodec.decode(data) ?: continue

                    ackId.set(decoded.frameId.toInt())

                    for (msg in decoded.messages) {
                        val parsed = MessageParser.parse(msg) ?: continue
                        handleParsedMessage(parsed)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Receive loop error: ${e.message}")
                    receiveRestarts++
                    if (receiveRestarts >= RECEIVE_MAX_RESTARTS) {
                        Log.e(TAG, "Receive loop failed $RECEIVE_MAX_RESTARTS times — entering ERROR")
                        emitState(ProtocolState.Error(ProtocolError.RECEIVE_LOOP_FAILED))
                        break
                    }
                    delay(200)
                }
            }
            Log.d(TAG, "Receive loop ended")
        }
    }

    private fun handleParsedMessage(msg: ParsedMessage) {
        when (msg) {
            is ParsedMessage.Pong -> {
                missedPongs.set(0)
            }
            is ParsedMessage.RobotStateMsg -> {
                scope.launch(Dispatchers.Main) { _robotState.value = msg.state }
            }
            is ParsedMessage.ObservedObject -> {
                objectIdToCubeId[msg.objectId] = msg.cubeId
                updateCubeState(
                    objectId = msg.objectId,
                    cubeId = msg.cubeId,
                    isVisible = msg.isVisible,
                    signalStrength = msg.signalStrength,
                    lastSeenMs = msg.lastSeenMs,
                    isBeingCarried = false,
                )
            }
            is ParsedMessage.PickedUpObject -> {
                val cubeId = objectIdToCubeId[msg.objectId] ?: return
                updateCubeCarryState(msg.objectId, cubeId, isBeingCarried = true)
            }
            is ParsedMessage.PlacedObject -> {
                val cubeId = objectIdToCubeId[msg.objectId] ?: return
                updateCubeCarryState(msg.objectId, cubeId, isBeingCarried = false)
            }
            is ParsedMessage.AnimationCompleted -> {
                pendingAnimation?.complete(msg.result)
                pendingAnimation = null
            }
            is ParsedMessage.CompletedAction -> {
                pendingAction?.complete(msg.result)
                pendingAction = null
            }
            is ParsedMessage.CameraImage -> {
                if (msg.jpegData.isNotEmpty()) {
                    val receivedAt = System.currentTimeMillis()
                    scope.launch(Dispatchers.Default) {
                        decodeCameraFrame(msg.jpegData, receivedAt)
                    }
                }
            }
            is ParsedMessage.Connected -> { /* handled during handshake */ }
        }
    }

    // ── Ping Loop ─────────────────────────────────────────────────────────────

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Ping loop started")
            while (isActive) {
                delay(PING_INTERVAL_MS)
                send(MessageIds.PING, MessageBuilder.ping(
                    counter = pingCounter.incrementAndGet().toUInt(),
                    timestamp = System.currentTimeMillis(),
                ))
                val missed = missedPongs.incrementAndGet()
                if (missed >= PONG_MISS_LIMIT) {
                    Log.w(TAG, "Missed $missed pongs — disconnecting")
                    emitState(ProtocolState.Disconnected)
                    break
                }
            }
        }
    }

    // ── Camera Decode ─────────────────────────────────────────────────────────

    private suspend fun decodeCameraFrame(jpegData: ByteArray, receivedAt: Long) {
        val age = System.currentTimeMillis() - receivedAt
        if (age > CAMERA_FRAME_MAX_AGE_MS) {
            Log.v(TAG, "Dropping stale camera frame (age ${age}ms)")
            return
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, options)
        if (bitmap == null) {
            Log.w(TAG, "Failed to decode JPEG — dropping frame")
            return
        }
        _cameraFrames.emit(bitmap)
    }

    // ── Cube State Helpers ────────────────────────────────────────────────────

    private fun updateCubeState(
        objectId: Int, cubeId: Int, isVisible: Boolean,
        signalStrength: Float, lastSeenMs: Long, isBeingCarried: Boolean,
    ) {
        scope.launch(Dispatchers.Main) {
            val current = _cubeStates.value.toMutableList()
            val existing = current.indexOfFirst { it.objectId == objectId }
            val updated = CubeState(
                cubeId = cubeId,
                objectId = objectId,
                isVisible = isVisible,
                isBeingCarried = isBeingCarried,
                lightState = if (existing >= 0) current[existing].lightState
                             else CubeLightState(0xFFFFFF, false, 500, 500),
                signalStrength = signalStrength,
                lastSeenMs = lastSeenMs,
            )
            if (existing >= 0) current[existing] = updated else current.add(updated)
            _cubeStates.value = current
        }
    }

    private fun updateCubeCarryState(objectId: Int, cubeId: Int, isBeingCarried: Boolean) {
        scope.launch(Dispatchers.Main) {
            val current = _cubeStates.value.toMutableList()
            val idx = current.indexOfFirst { it.objectId == objectId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(isBeingCarried = isBeingCarried)
                _cubeStates.value = current
            }
        }
    }

    // ── Send Helpers ──────────────────────────────────────────────────────────

    /** Non-blocking fire-and-forget send. Queued through single-threaded dispatcher. */
    private fun send(messageId: UShort, payload: ByteArray) {
        scope.launch(sendDispatcher) {
            sendRaw(messageId, payload)
        }
    }

    /** Synchronous send — must be called from sendDispatcher or IO context. */
    private fun sendRaw(messageId: UShort, payload: ByteArray) {
        val sock = socket ?: run {
            Log.w(TAG, "Send attempted with no socket — dropping message 0x${messageId.toString(16)}")
            return
        }
        val addr = robotAddress ?: return
        val frame = FrameCodec.encode(
            frameId = frameId.getAndIncrement().toUInt(),
            ackId = ackId.get().toUInt(),
            messageId = messageId,
            payload = payload,
        )
        try {
            sock.send(DatagramPacket(frame, frame.size, addr, robotPort))
        } catch (e: Exception) {
            Log.w(TAG, "Send failed: ${e.message}")
        }
    }

    // ── Handshake Helpers ─────────────────────────────────────────────────────

    /**
     * Temporarily receives packets to find the CONNECTED message during handshake.
     * Runs on IO; returns on first CONNECTED received.
     */
    private suspend fun waitForConnected(): ParsedMessage.Connected? {
        return withContext(Dispatchers.IO) {
            val buf = ByteArray(UDP_BUFFER_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            while (isActive) {
                try {
                    socket?.receive(packet) ?: return@withContext null
                    val decoded = FrameCodec.decode(buf.copyOf(packet.length)) ?: continue
                    for (msg in decoded.messages) {
                        val parsed = MessageParser.parse(msg)
                        if (parsed is ParsedMessage.Connected) return@withContext parsed
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { /* ignore during handshake */ }
            }
            null
        }
    }

    private suspend fun awaitAction(deferred: CompletableDeferred<ActionResult>): ActionResult {
        return try {
            withTimeout(ACTION_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            pendingAction = null
            ActionResult.Timeout
        }
    }

    private fun isConnected(): Boolean {
        val connected = _protocolState.value is ProtocolState.Connected
        if (!connected) Log.w(TAG, "Command dropped — not in Connected state")
        return connected
    }

    private fun emitState(state: ProtocolState) {
        scope.launch(Dispatchers.Main) { _protocolState.value = state }
    }
}
