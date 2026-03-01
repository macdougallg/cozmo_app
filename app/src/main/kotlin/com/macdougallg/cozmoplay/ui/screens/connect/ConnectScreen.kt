package com.macdougallg.cozmoplay.ui.screens.connect

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macdougallg.cozmoplay.types.ConnectionState
import com.macdougallg.cozmoplay.ui.theme.*
import org.koin.androidx.compose.koinViewModel

/**
 * ConnectScreen — the gating screen shown on every app launch.
 * (UI PRD section 3.1)
 *
 * Landscape split layout:
 * - Left 50%: Cozmo illustration + status ring animation
 * - Right 50%: status label + connect/retry buttons + manual help link
 */
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ConnectViewModel = koinViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val haptic = LocalHapticFeedback.current

    // Auto-navigate to Home when connected
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            kotlinx.coroutines.delay(1500)
            onConnected()
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(AppBackground)) {

        // ── Left Panel: Illustration + Status Ring ─────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(PrimaryBlue.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StatusRing(connectionState = connectionState)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "🤖", // TODO Agent 3: Replace with Lottie Cozmo animation
                    fontSize = 80.sp,
                )
            }
        }

        // ── Right Panel: Status + Controls ────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(32.dp),
        ) {
            // Settings icon (top-right)
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp),
            ) {
                Text("⚙️", fontSize = 24.sp)
            }

            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Status label
                Text(
                    text = connectionState.toChildFacingString(),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                    ),
                    color = TextPrimary,
                )

                // Connect button — hidden in error/retry states
                if (connectionState.showConnectButton) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.connect()
                        },
                        enabled = connectionState.connectButtonEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    ) {
                        Text(
                            "🔍  Find Cozmo",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp),
                            color = Color.White,
                        )
                    }
                }

                // Retry button — shown in error/fallback states
                if (connectionState.showRetryButton) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.connect()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CozmoOrange),
                    ) {
                        Text(
                            "🔄  Try Again",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp),
                            color = Color.White,
                        )
                    }
                }
            }

            // Manual help link (bottom-right)
            TextButton(
                onClick = { viewModel.triggerManualFallback() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text(
                    "❓  Set up WiFi manually",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

// ── Status Ring Composable ─────────────────────────────────────────────────────

@Composable
private fun StatusRing(connectionState: ConnectionState) {
    val isSpinning = connectionState is ConnectionState.Scanning ||
        connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Polling

    val infiniteTransition = rememberInfiniteTransition(label = "ring_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isSpinning) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    val ringColor by animateColorAsState(
        targetValue = when (connectionState) {
            is ConnectionState.Connected -> SuccessGreen
            is ConnectionState.Error -> ErrorRed
            else -> PrimaryBlue
        },
        label = "ring_color",
    )

    Box(
        modifier = Modifier
            .size(140.dp)
            .rotate(if (isSpinning) rotation else 0f)
            .clip(CircleShape)
            .background(ringColor.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(AppBackground),
        )
    }
}

// ── Extension helpers ──────────────────────────────────────────────────────────

private fun ConnectionState.toChildFacingString(): String = when (this) {
    is ConnectionState.Idle -> "Ready to connect!"
    is ConnectionState.Scanning -> "Looking for Cozmo..."
    is ConnectionState.Found -> "Found Cozmo! Connecting..."
    is ConnectionState.Connecting -> "Found Cozmo! Connecting..."
    is ConnectionState.Connected -> "Cozmo is ready! 🎉"
    is ConnectionState.FallbackRequired -> "Let's try another way"
    is ConnectionState.Polling -> "Waiting for WiFi switch..."
    is ConnectionState.Disconnected -> "Cozmo wandered off... finding them again"
    is ConnectionState.TimedOut -> "Hmm, can't find Cozmo"
    is ConnectionState.NotFound -> "Hmm, can't find Cozmo"
    is ConnectionState.Error -> "Hmm, can't find Cozmo"
}

private val ConnectionState.showConnectButton: Boolean
    get() = this is ConnectionState.Idle

private val ConnectionState.showRetryButton: Boolean
    get() = this is ConnectionState.Error ||
        this is ConnectionState.FallbackRequired ||
        this is ConnectionState.TimedOut ||
        this is ConnectionState.NotFound ||
        this is ConnectionState.Disconnected

private val ConnectionState.connectButtonEnabled: Boolean
    get() = this is ConnectionState.Idle
