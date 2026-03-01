package com.macdougallg.cozmoplay.ui.screens.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.macdougallg.cozmoplay.camera.CameraViewModel
import com.macdougallg.cozmoplay.camera.CozmoCamera
import com.macdougallg.cozmoplay.protocol.ICozmoProtocol
import com.macdougallg.cozmoplay.types.RobotState
import com.macdougallg.cozmoplay.ui.components.CozmoTopBar
import com.macdougallg.cozmoplay.ui.components.EmotionBadge
import com.macdougallg.cozmoplay.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.androidx.compose.koinViewModel

// ── ViewModel ─────────────────────────────────────────────────────────────────

class ExploreViewModel(private val protocol: ICozmoProtocol) : ViewModel() {

    val robotState: StateFlow<RobotState> = protocol.robotState
    val cubeStates = protocol.cubeStates

    private val _freeplayActive = MutableStateFlow(false)
    val freeplayActive: StateFlow<Boolean> = _freeplayActive.asStateFlow()

    fun toggleFreeplay() {
        val next = !_freeplayActive.value
        _freeplayActive.value = next
        protocol.enableFreeplay(next)
    }

    override fun onCleared() {
        super.onCleared()
        protocol.enableFreeplay(false)
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    viewModel: ExploreViewModel = koinViewModel(),
    cameraViewModel: CameraViewModel = koinViewModel(),
) {
    val robotState by viewModel.robotState.collectAsState()
    val cubes by viewModel.cubeStates.collectAsState()
    val freeplayActive by viewModel.freeplayActive.collectAsState()

    // Auto-enable camera on enter
    LaunchedEffect(Unit) { cameraViewModel.setEnabled(true) }

    val caption = liveCaptionFor(robotState, cubes.any { it.isVisible })

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        CozmoTopBar(title = "Explore", onBack = {
            viewModel.toggleFreeplay().also { /* ensure off */ }
            cameraViewModel.setEnabled(false)
            onBack()
        })

        Row(modifier = Modifier.weight(1f)) {

            // Camera (60% width)
            Box(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight()
                    .background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center,
            ) {
                CozmoCamera(modifier = Modifier.fillMaxSize(), viewModel = cameraViewModel)

                // Freeplay ring overlay
                if (freeplayActive) {
                    FreeplayRing(modifier = Modifier.fillMaxSize())
                }
            }

            // Right panel — mood + captions (40% width)
            Column(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .background(SurfaceWhite)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Text(
                    "Cozmo's Mood",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                EmotionBadge(
                    emotion = robotState.emotion,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                )
            }
        }

        // Start/Stop button
        Button(
            onClick = { viewModel.toggleFreeplay() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(80.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (freeplayActive) ErrorRed else SuccessGreen,
            ),
        ) {
            Text(
                if (freeplayActive) "⏹  Stop" else "▶  Start Exploring!",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 20.sp),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun FreeplayRing(modifier: Modifier) {
    // Animated pulsing ring overlay to indicate freeplay is active
    val infiniteTransition = rememberInfiniteTransition(label = "freeplay_ring")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            androidx.compose.animation.core.tween(1000),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "ring_alpha",
    )
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .background(CozmoOrange.copy(alpha = alpha * 0.15f), RoundedCornerShape(8.dp))
        )
    }
}

private fun liveCaptionFor(state: RobotState, cubeVisible: Boolean): String = when {
    cubeVisible           -> "Cozmo found a cube!"
    state.isAnimating     -> "Cozmo is doing something silly!"
    state.isMoving        -> "Cozmo is exploring!"
    else                  -> when (state.emotion) {
        com.macdougallg.cozmoplay.types.Emotion.HAPPY     -> "Cozmo is having fun!"
        com.macdougallg.cozmoplay.types.Emotion.EXCITED   -> "Cozmo is super excited!"
        com.macdougallg.cozmoplay.types.Emotion.BORED     -> "Cozmo needs something to do..."
        com.macdougallg.cozmoplay.types.Emotion.NEUTRAL   -> "Cozmo is curious about something!"
        else                                               -> "Cozmo is thinking..."
    }
}
