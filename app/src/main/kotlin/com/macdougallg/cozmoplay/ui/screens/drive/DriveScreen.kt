package com.macdougallg.cozmoplay.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macdougallg.cozmoplay.camera.CameraViewModel
import com.macdougallg.cozmoplay.camera.CozmoCamera
import com.macdougallg.cozmoplay.camera.ICozmoCamera
import com.macdougallg.cozmoplay.protocol.ICozmoProtocol
import com.macdougallg.cozmoplay.types.Emotion
import com.macdougallg.cozmoplay.ui.components.*
import com.macdougallg.cozmoplay.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

// ── ViewModel ─────────────────────────────────────────────────────────────────

class DriveViewModel(private val protocol: ICozmoProtocol) : ViewModel() {

    val robotState = protocol.robotState

    private val _fastMode = MutableStateFlow(false)
    val fastMode: StateFlow<Boolean> = _fastMode.asStateFlow()

    fun driveJoystick(x: Float, y: Float) {
        val maxSpeed = if (_fastMode.value) 220f else 110f
        // Tank-drive mapping (UI PRD section 3.4.2)
        val left  = (y + x) * maxSpeed
        val right = (y - x) * maxSpeed
        protocol.driveWheels(left, right)
    }

    fun stop() { protocol.stopAllMotors() }

    fun setHeadAngle(normalised: Float) {
        // Map 0..1 slider to -0.44..0.78 rad
        val angle = -0.44f + normalised * (0.78f - (-0.44f))
        protocol.setHeadAngle(angle)
    }

    fun setLiftHeight(normalised: Float) {
        // Map 0..1 slider to 32..92 mm
        val height = 32f + normalised * (92f - 32f)
        protocol.setLiftHeight(height)
    }

    fun toggleSpeed() { _fastMode.value = !_fastMode.value }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun DriveScreen(
    onBack: () -> Unit,
    driveViewModel: DriveViewModel = koinViewModel(),
    cameraViewModel: CameraViewModel = koinViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val robotState by driveViewModel.robotState.collectAsState()
    val fastMode by driveViewModel.fastMode.collectAsState()

    // Stop motors when leaving screen
    DisposableEffect(Unit) {
        onDispose { driveViewModel.stop() }
    }

    Row(modifier = Modifier.fillMaxSize().background(AppBackground)) {

        // ── Left Panel: Joystick + Speed ─────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(220.dp)
                .background(SurfaceWhite)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            CozmoTopBar(title = "Drive", onBack = {
                driveViewModel.stop()
                onBack()
            })

            Spacer(Modifier.height(16.dp))

            CozmoJoystick(
                onJoystickMove = { x, y -> driveViewModel.driveJoystick(x, y) },
                outerRadius = 80.dp,
                thumbRadius = 30.dp,
            )

            Spacer(Modifier.height(16.dp))

            // Speed toggle
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeedButton(
                    icon = "🐢", label = "Slow",
                    selected = !fastMode,
                    onClick = { if (fastMode) driveViewModel.toggleSpeed() },
                )
                SpeedButton(
                    icon = "🐇", label = "Fast",
                    selected = fastMode,
                    onClick = { if (!fastMode) driveViewModel.toggleSpeed() },
                )
            }

            // Status pill
            EmotionBadge(
                emotion = robotState.emotion,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ── Centre Panel: Camera ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            CozmoCamera(
                modifier = Modifier.fillMaxSize(),
                viewModel = cameraViewModel,
            )

            // Camera toggle button (top-right of camera panel)
            val cameraEnabled by cameraViewModel.isEnabled.collectAsState()
            IconButton(
                onClick = { cameraViewModel.setEnabled(!cameraEnabled) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp),
            ) {
                Text(if (cameraEnabled) "📷" else "🚫", fontSize = 22.sp)
            }
        }

        // ── Right Panel: Head + Lift + Stop ───────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(160.dp)
                .background(SurfaceWhite)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Head slider
            SliderControl(
                icon = "🤖", topLabel = "⬆", bottomLabel = "⬇",
                label = "Head",
                onValueChange = { driveViewModel.setHeadAngle(it) },
            )

            // Lift slider
            SliderControl(
                icon = "💪", topLabel = "⬆", bottomLabel = "⬇",
                label = "Lift",
                onValueChange = { driveViewModel.setLiftHeight(it) },
            )

            // Emergency stop
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    driveViewModel.stop()
                },
                modifier = Modifier.size(80.dp).clip(CircleShape),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
            ) {
                Text("⛔", fontSize = 28.sp)
            }
        }
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun SpeedButton(
    icon: String, label: String, selected: Boolean, onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 64.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) PrimaryBlue else PrimaryBlue.copy(alpha = 0.2f),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 20.sp)
            Text(label, fontSize = 11.sp, color = if (selected) Color.White else PrimaryBlue)
        }
    }
}

@Composable
private fun SliderControl(
    icon: String, topLabel: String, bottomLabel: String,
    label: String, onValueChange: (Float) -> Unit,
) {
    var value by remember { mutableStateOf(0.5f) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(icon, fontSize = 20.sp)
        Text(topLabel, fontSize = 16.sp, color = PrimaryBlue)
        Slider(
            value = value,
            onValueChange = { value = it; onValueChange(it) },
            modifier = Modifier.height(120.dp),
            colors = SliderDefaults.colors(thumbColor = PrimaryBlue, activeTrackColor = PrimaryBlue),
        )
        Text(bottomLabel, fontSize = 16.sp, color = PrimaryBlue)
    }
}
