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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macdougallg.cozmoplay.types.ConnectionState
import com.macdougallg.cozmoplay.ui.theme.*
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ConnectViewModel = koinViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            delay(1500)
            onConnected()
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(AppBackground)) {

        // Left panel — illustration + status ring
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(PrimaryBlue.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StatusRing(connectionState = connectionState)
                Spacer(Modifier.height(16.dp))
                Text(text = "🤖", fontSize = 80.sp)
            }
        }

        // Right panel — status + controls
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(32.dp),
        ) {
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
            ) { Text("⚙️", fontSize = 24.sp) }

            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    text = connectionState.toChildString(),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 22.sp, textAlign = TextAlign.Center,
                    ),
                    color = TextPrimary,
                )

                if (connectionState.showConnect) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.connect()
                        },
                        enabled = connectionState is ConnectionState.Idle,
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    ) {
                        Text("🔍  Find Cozmo",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp),
                            color = Color.White)
                    }
                }

                if (connectionState.showRetry) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.connect()
                        },
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CozmoOrange),
                    ) {
                        Text("🔄  Try Again",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp),
                            color = Color.White)
                    }
                }
            }

            TextButton(
                onClick = { viewModel.triggerManualFallback() },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text("❓  Set up WiFi manually",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun StatusRing(connectionState: ConnectionState) {
    val spinning = connectionState is ConnectionState.Scanning
        || connectionState is ConnectionState.Connecting
        || connectionState is ConnectionState.Polling

    val infinite = rememberInfiniteTransition(label = "ring")
    val rotation by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "rotation",
    )
    val color by animateColorAsState(
        targetValue = when (connectionState) {
            is ConnectionState.Connected -> SuccessGreen
            is ConnectionState.Error    -> ErrorRed
            else                        -> PrimaryBlue
        }, label = "ring_color",
    )
    Box(
        modifier = Modifier.size(140.dp)
            .rotate(if (spinning) rotation else 0f)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(AppBackground))
    }
}

private fun ConnectionState.toChildString() = when (this) {
    is ConnectionState.Idle             -> "Ready to connect!"
    is ConnectionState.Scanning         -> "Looking for Cozmo..."
    is ConnectionState.Found            -> "Found Cozmo! Connecting..."
    is ConnectionState.Connecting       -> "Found Cozmo! Connecting..."
    is ConnectionState.Connected        -> "Cozmo is ready! 🎉"
    is ConnectionState.FallbackRequired -> "Let's try another way"
    is ConnectionState.Polling          -> "Waiting for WiFi switch..."
    is ConnectionState.Disconnected     -> "Cozmo wandered off..."
    is ConnectionState.TimedOut         -> "Hmm, can't find Cozmo"
    is ConnectionState.NotFound         -> "Hmm, can't find Cozmo"
    is ConnectionState.Error            -> "Hmm, can't find Cozmo"
}

private val ConnectionState.showConnect get() =
    this is ConnectionState.Idle

private val ConnectionState.showRetry get() =
    this is ConnectionState.Error || this is ConnectionState.FallbackRequired
    || this is ConnectionState.TimedOut || this is ConnectionState.NotFound
    || this is ConnectionState.Disconnected
