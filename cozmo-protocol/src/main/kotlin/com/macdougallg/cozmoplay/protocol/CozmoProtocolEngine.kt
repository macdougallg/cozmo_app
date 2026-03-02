package com.macdougallg.cozmoplay.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.macdougallg.cozmoplay.protocol.framing.FrameCodec
import com.macdougallg.cozmoplay.protocol.messages.*
import com.macdougallg.cozmoplay.types.*
import com.macdougallg.cozmoplay.wifi.ICozmoWifi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Full implementation of [ICozmoProtocol] against the real Cozmo UDP wire protocol.
 *
 * Protocol reference: PyCozmo frame.py / conn.py / protocol_declaration.py
 *
 * Frame flow:
 *   connect()  → send RESET frame (0x01)
 *              ← receive ROBOT_PACKETS (0x09) containing CONNECT packet (0x02)
 *   steady     → send ENGINE_PACKETS (0x07) for each command
 *   keepalive  → send OOB_PING (0x0b) every 500ms
 *              ← receive ROBOT_PACKETS (0x09) containing EVENT packets (0x05)
 *   disconnect → send DISCONNECT frame (0x03)
 */
class CozmoProtocolEngine : ICozmoProtocol {

    companion object {
        private const val TAG = "CozmoProtocol"
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val HANDSHAKE_MAX_RETRIES = 3
        private const val PING_INTERVAL_MS = 500L
        private const val PONG_MISS_LIMIT = 6     // 3 seconds with no robot frame
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
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val cameraFrames: Flow<Bitmap> = _cameraFrames.asSharedFlow()

    // ── Internal ──────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob())
    private val connectMutex = Mutex()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var socket: DatagramSocket? = null
    private var robotAddress: InetAddress? = null
    private var robotPort: Int = 0

    // Sequence counters
    private val txSeq = AtomicInteger(0)          // next command sequence number
    private val lastRxSeq = AtomicInteger(FrameCodec.OOB_SEQ) // last seq received from robot
    private val pingCounter = AtomicInteger(0)
    private val framesSinceLastRobotPacket = AtomicInteger(0)
    private val robotStateCount = AtomicInteger(0)   // for rate-limited logging
    private val imageChunkCount = AtomicInteger(0)   // for rate-limited logging

    private var receiveJob: Job? = null
    private var pingJob: Job? = null
    private var receiveRestarts = 0

    // Drive rate limiting
    private val lastDriveSendMs = AtomicLong(0)
    private val driveIntervalMs = 1000L / DRIVE_RATE_LIMIT_HZ

    // Camera chunk reassembly — accessed only from receive loop, no locking needed
    private val imageChunkBuffers = LinkedHashMap<Int, ImageFrameBuffer>()
    private val IMAGE_BUFFER_MAX = 4

    // Pending action completions
    private var pendingAnimation: CompletableDeferred<ActionResult>? = null
    private var pendingAction: CompletableDeferred<ActionResult>? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun connect(wifi: ICozmoWifi) {
        if (!connectMutex.tryLock()) {
            Log.d(TAG, "connect() already in progress — ignoring concurrent call")
            return
        }
        try { connectImpl(wifi) } finally { connectMutex.unlock() }
    }

    private suspend fun connectImpl(wifi: ICozmoWifi) {
        emitState(ProtocolState.Connecting)
        Log.i(TAG, "Starting protocol connection to ${wifi.cozmoIpAddress}:${wifi.cozmoPort}")

        val sock = try {
            withContext(Dispatchers.IO) { wifi.createBoundSocket() }
        } catch (e: Exception) {
            Log.w(TAG, "connect() aborted: socket unavailable — ${e.message}")
            emitState(ProtocolState.Disconnected)
            return
        }
        socket = sock
        robotAddress = withContext(Dispatchers.IO) {
            InetAddress.getByName(wifi.cozmoIpAddress)
        }
        robotPort = wifi.cozmoPort
        txSeq.set(0)
        lastRxSeq.set(FrameCodec.OOB_SEQ)

        // Handshake: send Reset frame, wait for robot's Connect packet
        repeat(HANDSHAKE_MAX_RETRIES) { attempt ->
            Log.d(TAG, "Handshake attempt ${attempt + 1}/$HANDSHAKE_MAX_RETRIES")
            sendRaw(FrameCodec.encodeReset())

            val connected = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                waitForConnectPacket()
            }

            if (connected == true) {
                Log.i(TAG, "Handshake complete — starting init sequence")
                emitState(ProtocolState.Connected)
                startReceiveLoop()
                startPingLoop()
                // Post-handshake init (mirrors PyCozmo client.py _on_firmware_signature/_on_body_info):
                // Enable×2 → BodyInfo from robot → SetOrigin + SyncTime → robot starts streaming RobotState
                sendCommand(CommandIds.ENABLE, MessageBuilder.enable())
                sendCommand(CommandIds.ENABLE, MessageBuilder.enable())
                delay(200) // allow robot to process Enable and send BodyInfo
                sendCommand(CommandIds.SET_ORIGIN, MessageBuilder.setOrigin())
                sendCommand(CommandIds.SYNC_TIME, MessageBuilder.syncTime())
                Log.i(TAG, "Init sequence sent — waiting for RobotState stream")
                return
            }
        }

        Log.e(TAG, "Handshake failed after $HANDSHAKE_MAX_RETRIES attempts")
        emitState(ProtocolState.Error(ProtocolError.HANDSHAKE_TIMEOUT))
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Disconnecting")
        try { sendRaw(FrameCodec.encodeFrame(FrameCodec.FRAME_DISCONNECT, 0, 0, lastRxSeq.get())) }
        catch (_: Exception) {}
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
        sendCommand(CommandIds.DRIVE_WHEELS, MessageBuilder.driveWheels(leftMmps, rightMmps))
    }

    override fun stopAllMotors() {
        if (!isConnected()) return
        sendCommand(CommandIds.STOP_ALL_MOTORS, MessageBuilder.stopAllMotors())
    }

    override fun setHeadAngle(angleRad: Float) {
        if (!isConnected()) return
        sendCommand(CommandIds.SET_HEAD_ANGLE, MessageBuilder.setHeadAngle(angleRad))
    }

    override fun moveHead(speedRadPerSec: Float) {
        if (!isConnected()) return
        sendCommand(CommandIds.DRIVE_HEAD, MessageBuilder.driveHead(speedRadPerSec))
    }

    override fun setLiftHeight(heightMm: Float) {
        if (!isConnected()) return
        sendCommand(CommandIds.SET_LIFT_HEIGHT, MessageBuilder.setLiftHeight(heightMm))
    }

    override fun moveLift(speedRadPerSec: Float) {
        if (!isConnected()) return
        sendCommand(CommandIds.DRIVE_LIFT, MessageBuilder.driveLift(speedRadPerSec))
    }

    // ── Animations ────────────────────────────────────────────────────────────
    //
    // The Cozmo robot has no built-in named animations. PyCozmo plays animations by
    // streaming keyframe data (head angles, lift heights, backpack lights, face images,
    // audio) frame-by-frame from FlatBuffers .bin files that ship with the Cozmo app.
    //
    // Since we don't have those asset files, tricks are implemented as hardcoded
    // motor sequences — timed combinations of driveWheels, setHeadAngle, setLiftHeight
    // commands that produce recognisable physical reactions from the robot.

    override suspend fun playAnimation(name: String): ActionResult {
        if (!isConnected()) return ActionResult.Failure("Not connected")
        Log.i(TAG, "playAnimation: $name")
        return try {
            withTimeout(ACTION_TIMEOUT_MS) {
                when (name) {
                    "anim_happy_01", "anim_happy_03"             -> trickNod()
                    "anim_greeting_happy_01"                     -> trickGreeting()
                    "anim_excited_01"                            -> trickSpin()
                    "anim_hiccup_01"                             -> trickHiccup()
                    "anim_dizzy_shakeonce_01"                    -> trickDizzy()
                    "anim_sneeze_01"                             -> trickSneeze()
                    "anim_bored_01", "anim_bored_event_01"       -> trickBored()
                    "anim_angry_01", "anim_angry_frustrated_01"  -> trickAngry()
                    "anim_frustrated_01"                         -> trickFrustrated()
                    "anim_sad_01"                                -> trickSad()
                    "anim_whimper_01"                            -> trickWhimper()
                    "anim_surprised_01"                          -> trickSurprised()
                    "anim_startled_01"                           -> trickStartled()
                    "anim_scared_01"                             -> trickScared()
                    "anim_gotosleep_01"                          -> trickGoToSleep()
                    "anim_sleeping_01"                           -> trickSleeping()
                    "anim_feedback_scanneractivated_01"          -> trickScanner()
                    "anim_feedback_pickup_01"                    -> trickPickup()
                    else                                         -> trickNod()
                }
                ActionResult.Success
            }
        } catch (_: TimeoutCancellationException) {
            ActionResult.Timeout
        }
    }

    override suspend fun playAnimationGroup(groupName: String): ActionResult {
        if (!isConnected()) return ActionResult.Failure("Not connected")
        val anims = AnimationManifest.BY_CATEGORY[groupName] ?: AnimationManifest.ALL
        return playAnimation(anims.random())
    }

    // ── Trick Sequences ───────────────────────────────────────────────────────
    // Each suspend function executes a timed motor sequence and returns when done.
    // Head angle range: -0.44 (down) to +0.78 rad (up).
    // Lift height range: 32mm (low) to 92mm (high).
    // Wheel speed range: -220 to +220 mm/s.

    /** Happy — enthusiastic head nod */
    private suspend fun trickNod() {
        setHeadAngle(0.6f);  delay(250)
        setHeadAngle(-0.1f); delay(250)
        setHeadAngle(0.6f);  delay(250)
        setHeadAngle(0.0f);  delay(200)
    }

    /** Greeting — bow and rise */
    private suspend fun trickGreeting() {
        setHeadAngle(0.5f); setLiftHeight(80f); delay(400)
        setHeadAngle(-0.3f); setLiftHeight(32f); delay(600)
        setHeadAngle(0.0f); delay(300)
    }

    /** Excited — spin in place */
    private suspend fun trickSpin() {
        driveWheels(-200f, 200f); delay(600)
        stopAllMotors(); delay(200)
    }

    /** Hiccup — sharp head jerks */
    private suspend fun trickHiccup() {
        setHeadAngle(0.7f); delay(100)
        setHeadAngle(0.0f); delay(300)
        setHeadAngle(0.7f); delay(100)
        setHeadAngle(0.0f); delay(400)
    }

    /** Dizzy — weave side to side */
    private suspend fun trickDizzy() {
        driveWheels(130f, 20f); delay(300)
        driveWheels(20f, 130f); delay(300)
        driveWheels(130f, 20f); delay(300)
        stopAllMotors()
    }

    /** Sneeze — head flings forward then recovers */
    private suspend fun trickSneeze() {
        setHeadAngle(0.4f); delay(600)
        setHeadAngle(-0.4f); delay(150)
        driveWheels(-100f, -100f); delay(200)
        stopAllMotors()
        setHeadAngle(0.0f); delay(300)
    }

    /** Bored — slow aimless drift, head down */
    private suspend fun trickBored() {
        setHeadAngle(-0.3f); delay(400)
        driveWheels(50f, 50f); delay(700)
        stopAllMotors(); delay(500)
        driveWheels(-50f, -50f); delay(300)
        stopAllMotors()
        setHeadAngle(0.0f); delay(300)
    }

    /** Angry — rapid spinning alternation */
    private suspend fun trickAngry() {
        driveWheels(-180f, 180f); delay(300)
        driveWheels(180f, -180f); delay(300)
        driveWheels(-180f, 180f); delay(300)
        stopAllMotors()
        setHeadAngle(-0.2f); delay(300)
        setHeadAngle(0.0f)
    }

    /** Frustrated — charge forward and back */
    private suspend fun trickFrustrated() {
        driveWheels(150f, 150f); delay(250)
        driveWheels(-150f, -150f); delay(250)
        driveWheels(150f, 150f); delay(250)
        stopAllMotors()
        setHeadAngle(-0.2f); delay(400)
        setHeadAngle(0.0f)
    }

    /** Sad — slow head droop, drift back */
    private suspend fun trickSad() {
        setHeadAngle(-0.4f); delay(800)
        driveWheels(-40f, -40f); delay(600)
        stopAllMotors()
        setHeadAngle(0.0f); delay(400)
    }

    /** Whimper — slight tremor, head down */
    private suspend fun trickWhimper() {
        setHeadAngle(-0.3f); delay(300)
        driveWheels(-30f, 30f); delay(150)
        driveWheels(30f, -30f); delay(150)
        driveWheels(-30f, 30f); delay(150)
        stopAllMotors()
        setHeadAngle(0.0f); delay(300)
    }

    /** Surprised — head snaps up, backs away */
    private suspend fun trickSurprised() {
        setHeadAngle(0.78f); delay(200)
        driveWheels(-120f, -120f); delay(300)
        stopAllMotors()
        setHeadAngle(0.0f); delay(400)
    }

    /** Startled — quick spin then head up */
    private suspend fun trickStartled() {
        driveWheels(-150f, 150f); delay(200)
        stopAllMotors()
        setHeadAngle(0.78f); delay(300)
        setHeadAngle(0.0f); delay(400)
    }

    /** Scared — rapid vibrate in place */
    private suspend fun trickScared() {
        repeat(3) {
            driveWheels(-60f, 60f); delay(100)
            driveWheels(60f, -60f); delay(100)
        }
        stopAllMotors()
        setHeadAngle(-0.2f); delay(500)
        setHeadAngle(0.0f)
    }

    /** Go to sleep — head and lift drop */
    private suspend fun trickGoToSleep() {
        setHeadAngle(-0.4f); setLiftHeight(32f); delay(800)
        delay(800)
        setHeadAngle(0.0f); delay(500)
    }

    /** Sleeping — gentle head bobs (breathing) */
    private suspend fun trickSleeping() {
        setHeadAngle(-0.35f); delay(600)
        setHeadAngle(-0.28f); delay(600)
        setHeadAngle(-0.35f); delay(600)
        setHeadAngle(0.0f); delay(400)
    }

    /** Scanner — look around left and right */
    private suspend fun trickScanner() {
        setHeadAngle(0.5f); delay(400)
        setHeadAngle(-0.2f); delay(400)
        driveWheels(-100f, 100f); delay(400)
        stopAllMotors()
        setHeadAngle(0.3f); delay(400)
        driveWheels(100f, -100f); delay(400)
        stopAllMotors()
        setHeadAngle(0.0f)
    }

    /** Pickup feedback — lift raises and lowers */
    private suspend fun trickPickup() {
        setLiftHeight(90f); delay(600)
        setLiftHeight(32f); delay(500)
    }

    // ── Cube Commands ─────────────────────────────────────────────────────────

    override fun setCubeLights(objectId: Int, lights: List<CubeLightConfig>) {
        // Cube light command not yet mapped — stub
        Log.w(TAG, "setCubeLights not yet implemented for real hardware")
    }

    override suspend fun pickupObject(objectId: Int): ActionResult {
        Log.w(TAG, "pickupObject not yet implemented for real hardware")
        return ActionResult.Failure("Not implemented")
    }

    override suspend fun placeObject(): ActionResult {
        Log.w(TAG, "placeObject not yet implemented for real hardware")
        return ActionResult.Failure("Not implemented")
    }

    override suspend fun rollObject(objectId: Int): ActionResult {
        Log.w(TAG, "rollObject not yet implemented for real hardware")
        return ActionResult.Failure("Not implemented")
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    override fun enableCamera(enabled: Boolean) {
        if (!isConnected()) return
        sendCommand(CommandIds.ENABLE_CAMERA, MessageBuilder.enableCamera(enabled))
        sendCommand(CommandIds.ENABLE_COLOR_IMAGES, MessageBuilder.enableColorImages(enabled))
    }

    override fun setNightVision(enabled: Boolean) {
        if (!isConnected()) { Log.w(TAG, "setNightVision($enabled): not connected — skipping"); return }
        Log.d(TAG, "setNightVision($enabled): sending SetHeadLight")
        sendCommand(CommandIds.SET_HEAD_LIGHT, MessageBuilder.setHeadLight(enabled))
    }

    // ── Freeplay ──────────────────────────────────────────────────────────────

    override fun enableFreeplay(enabled: Boolean) {
        // Freeplay command not yet mapped — stub
        Log.w(TAG, "enableFreeplay not yet implemented for real hardware")
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

                    // Track robot's sequence for ack
                    if (decoded.seq != FrameCodec.OOB_SEQ) {
                        lastRxSeq.set(decoded.seq)
                    }
                    framesSinceLastRobotPacket.set(0)

                    // Frame-level logging only when not in steady-state streaming
                    if (decoded.frameType != FrameCodec.FRAME_ROBOT_PACKETS) {
                        Log.v(TAG, "Rx frame 0x${decoded.frameType.toInt().and(0xff).toString(16)}" +
                            " seq=${decoded.seq} ack=${decoded.ack} pkts=${decoded.packets.size}")
                    }

                    if (decoded.frameType == FrameCodec.FRAME_ROBOT_PACKETS) {
                        for (pkt in decoded.packets) {
                            handleIncomingPacket(pkt)
                        }
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

    private fun handleIncomingPacket(pkt: com.macdougallg.cozmoplay.protocol.framing.IncomingPacket) {
        // Per-packet verbose logging — only for non-streaming packets (avoids 30Hz log flood)
        val isStreamingEvent = pkt.type == FrameCodec.PACKET_EVENT &&
            (pkt.id == EventIds.ROBOT_STATE || pkt.id == EventIds.IMAGE_CHUNK)
        if (!isStreamingEvent) {
            val typeHex = "0x${pkt.type.toInt().and(0xff).toString(16)}"
            val idHex   = pkt.id?.let { "0x${it.toInt().and(0xff).toString(16)}" } ?: "none"
            Log.v(TAG, "  pkt type=$typeHex id=$idHex payload=${pkt.payload.size}B")
        }

        val id = pkt.id ?: return // only COMMAND and EVENT packets carry an id

        when (pkt.type) {
            // Robot → engine events (standard path)
            FrameCodec.PACKET_EVENT   -> handleEvent(id, pkt.payload)
            // Some firmware versions send telemetry as PACKET_COMMAND (0x04) toward engine
            FrameCodec.PACKET_COMMAND -> handleEvent(id, pkt.payload)
            else -> Log.v(TAG, "  packet type 0x${pkt.type.toInt().and(0xff).toString(16)} unhandled")
        }
    }

    private fun handleEvent(id: Byte, payload: ByteArray) {
        when (id) {
            EventIds.ROBOT_STATE           -> parseRobotState(payload)
            EventIds.IMAGE_CHUNK           -> parseImageChunk(payload)
            EventIds.ANIMATION_STATE       -> Log.d(TAG, "AnimationState (0xf1) received (${payload.size} bytes)")
            EventIds.ANIMATION_STARTED     -> Log.d(TAG, "AnimationStarted: id=${payload.firstOrNull()?.toInt()?.and(0xFF)}")
            EventIds.ANIMATION_ENDED       -> Log.d(TAG, "AnimationEnded:   id=${payload.firstOrNull()?.toInt()?.and(0xFF)}")
            EventIds.OBJECT_AVAILABLE      -> Log.d(TAG, "ObjectAvailable received")
            EventIds.OBJECT_CONNECTION_STATE -> Log.d(TAG, "ObjectConnectionState received")
            EventIds.ACKNOWLEDGE_ACTION    -> Log.d(TAG, "AcknowledgeAction received")
            // Initial-burst info packets (robot sends these right after RESET)
            EventIds.HARDWARE_INFO         -> Log.d(TAG, "HardwareInfo received (${payload.size}B)")
            EventIds.FIRMWARE_SIGNATURE    -> Log.d(TAG, "FirmwareSignature received (${payload.size}B)")
            EventIds.DEBUG_DATA            -> Log.d(TAG, "DebugData received (${payload.size}B)")
            EventIds.BODY_INFO             -> Log.d(TAG, "BodyInfo received (${payload.size}B)")
            else -> {
                val hex = payload.take(8).joinToString(" ") { "%02x".format(it) }
                Log.v(TAG, "Unknown event ID 0x${id.toInt().and(0xff).toString(16)} (${payload.size}B): $hex")
            }
        }
    }

    // ── Ping Loop ─────────────────────────────────────────────────────────────

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Ping loop started")
            while (isActive) {
                delay(PING_INTERVAL_MS)
                val counter = pingCounter.incrementAndGet().toUInt()
                val pingFrame = FrameCodec.encodePing(
                    pingPayload = MessageBuilder.ping(counter),
                    ack = lastRxSeq.get(),
                )
                sendRaw(pingFrame)

                // Check if robot is still responding (via any received frame)
                val missed = framesSinceLastRobotPacket.incrementAndGet()
                if (missed >= PONG_MISS_LIMIT) {
                    Log.w(TAG, "No robot frames for ${missed * PING_INTERVAL_MS}ms — disconnecting")
                    emitState(ProtocolState.Disconnected)
                    break
                }
            }
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    /**
     * Accumulates ImageChunk fragments and emits a Bitmap once a full frame is assembled.
     *
     * Wire format (PyCozmo protocol_declaration.py):
     *   frame_timestamp (uint32, 4B)
     *   image_id        (uint32, 4B)  ← key for reassembly
     *   chunk_debug     (uint32, 4B)
     *   image_encoding  (uint8,  1B)  — 2 = JPEG
     *   image_resolution(uint8,  1B)
     *   image_chunk_count(uint8, 1B)  ← total chunks in this frame
     *   chunk_id        (uint8,  1B)  ← 0-based index of this chunk
     *   status          (uint16, 2B)
     *   data_len        (uint16, 2B)  ← VArray length prefix
     *   data            (bytes, data_len B)
     * Total fixed header before data: 18 + 2 = 20 bytes.
     *
     * Called only from the receive loop (single coroutine) — no synchronization needed.
     */
    private fun parseImageChunk(payload: ByteArray) {
        val pktNum = imageChunkCount.incrementAndGet()
        if (pktNum == 1) Log.d(TAG, "ImageChunk: first packet arrived (${payload.size}B)")

        if (payload.size < 20) {
            if (pktNum <= 3) Log.w(TAG, "ImageChunk #$pktNum: payload too short (${payload.size}B)")
            return
        }

        val buf = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.getInt()                                          // frame_timestamp — skip
        val imageId    = buf.getInt()
        buf.getInt()                                          // chunk_debug — skip
        val encoding   = buf.get().toInt().and(0xFF)
        val resolution = buf.get().toInt().and(0xFF)          // IMAGE_RESOLUTION enum
        val chunkCount = buf.get().toInt().and(0xFF)
        val chunkId    = buf.get().toInt().and(0xFF)
        buf.getShort()                                        // status — skip
        val dataLen    = buf.getShort().toInt().and(0xFFFF)   // VArray uint16 length prefix

        if (pktNum <= 3) Log.d(TAG, "ImageChunk #$pktNum: id=$imageId enc=$encoding count=$chunkCount chunk=$chunkId dataLen=$dataLen payloadSize=${payload.size}")

        // Allow all JPEG-family encodings: 5=JPEGGray, 6=JPEGColor, 7=JPEGColorHalfWidth,
        // 8=JPEGMinimizedGray, 9=JPEGMinimizedColor. Skip non-JPEG (RawGray=1, RawRGB=2, etc.)
        if (encoding < 5) { if (pktNum <= 3) Log.w(TAG, "ImageChunk: non-JPEG encoding $encoding — skipping"); return }
        if (chunkId > 32) { Log.w(TAG, "ImageChunk: chunkId $chunkId out of range"); return }
        if (buf.remaining() < dataLen) { Log.w(TAG, "ImageChunk: buf.remaining=${buf.remaining()} < dataLen=$dataLen"); return }

        val chunkData = ByteArray(dataLen).also { buf.get(it) }

        // Evict oldest incomplete frame if buffer is full
        if (imageChunkBuffers.size >= IMAGE_BUFFER_MAX && !imageChunkBuffers.containsKey(imageId)) {
            imageChunkBuffers.remove(imageChunkBuffers.keys.first())
        }

        val frame = imageChunkBuffers.getOrPut(imageId) { ImageFrameBuffer() }
        frame.chunks.putIfAbsent(chunkId, chunkData)

        // count=0 on intermediate chunks; the LAST chunk carries the real total count
        if (chunkCount > 0) frame.totalChunks = chunkCount

        if (frame.totalChunks > 0 && frame.chunks.size >= frame.totalChunks) {
            imageChunkBuffers.remove(imageId)
            val total = frame.totalChunks
            val allChunks = (0 until total).mapNotNull { frame.chunks[it] }
            if (allChunks.size != total) return  // gap — shouldn't happen

            val totalSize = allChunks.sumOf { it.size }
            val jpegBytes = ByteArray(totalSize)
            var pos = 0
            for (chunk in allChunks) { chunk.copyInto(jpegBytes, pos); pos += chunk.size }
            val (imgW, imgH) = CozmoImageDecoder.resolutionSize(resolution)

            // data[0] is the color flag byte: 0 = gray, non-zero = color (YCbCr).
            // The enc field stays 8 regardless — this byte is the real signal.
            // Per PyCozmo _process_completed_image: color frames use imgW/2 in the JPEG header.
            val isColor = jpegBytes.isNotEmpty() && jpegBytes[0] != 0.toByte()
            if (pktNum <= 5) Log.d(TAG, "ImageChunk: frame $imageId complete ($total chunks, ${totalSize}B enc=$encoding color=$isColor)")

            scope.launch(Dispatchers.Default) {
                val decodable = when {
                    encoding == 9 -> CozmoImageDecoder.miniColorToJpeg(jpegBytes, imgW / 2, imgH)
                    isColor       -> CozmoImageDecoder.miniColorToJpeg(jpegBytes, imgW / 2, imgH)
                    else          -> CozmoImageDecoder.miniGrayToJpeg(jpegBytes, imgW, imgH)
                }
                var bitmap = BitmapFactory.decodeByteArray(decodable, 0, decodable.size) ?: run {
                    Log.w(TAG, "ImageChunk: decode failed for frame $imageId (enc=$encoding color=$isColor ${imgW}x${imgH} ${totalSize}B)")
                    return@launch
                }
                // Color frames decode as half-width (160×240) due to 4:2:2 YCbCr packing.
                // Stretch back to full camera width so downstream scaling sees correct 4:3 ratio.
                if (isColor && bitmap.width < imgW) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, imgW, imgH, true)
                }
                _cameraFrames.emit(bitmap)
            }
        }
    }

    private class ImageFrameBuffer {
        val chunks = HashMap<Int, ByteArray>()
        var totalChunks: Int = 0  // 0 = unknown; set when the last chunk (count > 0) arrives
    }

    private fun parseRobotState(payload: ByteArray) {
        if (payload.size < 91) {
            Log.w(TAG, "RobotState payload too short: ${payload.size} bytes")
            return
        }
        // Rate-limited log: first packet, then every 300 (~10s at 30Hz)
        val count = robotStateCount.incrementAndGet()
        if (count == 1 || count % 300 == 0) {
            Log.d(TAG, "RobotState streaming ✓ (packet #$count)")
        }
        // TODO: decode all 91 bytes into RobotState fields
        scope.launch(Dispatchers.Main) {
            _robotState.value = _robotState.value // trigger observers
        }
    }

    // ── Send Helpers ──────────────────────────────────────────────────────────

    /** Fire-and-forget command send — queued through single-threaded dispatcher. */
    private fun sendCommand(commandId: Byte, payload: ByteArray) {
        scope.launch(sendDispatcher) {
            val seq = txSeq.incrementAndGet()
            val frame = FrameCodec.encodeCommand(commandId, payload, seq, lastRxSeq.get())
            sendRaw(frame)
        }
    }

    /** Synchronous raw send — must be called from IO or sendDispatcher context. */
    private fun sendRaw(frame: ByteArray) {
        val sock = socket ?: run {
            Log.w(TAG, "sendRaw: no socket")
            return
        }
        val addr = robotAddress ?: return
        try {
            sock.send(DatagramPacket(frame, frame.size, addr, robotPort))
        } catch (e: Exception) {
            Log.w(TAG, "Send failed [${e.javaClass.simpleName}]: ${e.message}")
        }
    }

    // ── Handshake ─────────────────────────────────────────────────────────────

    /**
     * Waits on the socket for a ROBOT_PACKETS frame containing a CONNECT packet (0x02).
     * Uses 200ms socket timeout so coroutine cancellation from [withTimeoutOrNull] takes effect.
     */
    private suspend fun waitForConnectPacket(): Boolean {
        return withContext(Dispatchers.IO) {
            socket?.soTimeout = 200
            try {
                val buf = ByteArray(UDP_BUFFER_SIZE)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive) {
                    try {
                        socket?.receive(packet) ?: return@withContext false
                        val decoded = FrameCodec.decode(buf.copyOf(packet.length)) ?: continue
                        Log.d(TAG, "Handshake rx: frame type 0x${decoded.frameType.toInt().and(0xff).toString(16)}, ${decoded.packets.size} packets")
                        if (decoded.frameType == FrameCodec.FRAME_ROBOT_PACKETS) {
                            val hasConnect = decoded.packets.any {
                                it.type == FrameCodec.PACKET_CONNECT
                            }
                            if (hasConnect) {
                                lastRxSeq.set(decoded.seq)
                                return@withContext true
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: java.net.SocketTimeoutException) {
                        // Poll interval — check isActive
                    } catch (_: Exception) { /* ignore during handshake */ }
                }
                false
            } finally {
                socket?.soTimeout = 0
            }
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
        if (!connected) Log.v(TAG, "Command dropped — not in Connected state")
        return connected
    }

    private fun emitState(state: ProtocolState) {
        scope.launch(Dispatchers.Main) { _protocolState.value = state }
    }
}
