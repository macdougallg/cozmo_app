package com.macdougallg.cozmoplay.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macdougallg.cozmoplay.types.Emotion
import com.macdougallg.cozmoplay.ui.theme.*
import kotlin.math.hypot
import kotlin.math.min

// ── CozmoTopBar ────────────────────────────────────────────────────────────────

/**
 * Top bar used on all feature screens.
 * Contains back button, title, and optional action icon.
 */
@Composable
fun CozmoTopBar(
    title: String,
    onBack: () -> Unit,
    actionIcon: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(PrimaryBlue)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Text("←", fontSize = 22.sp, color = Color.White)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold, fontSize = 20.sp,
            ),
            color = Color.White,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (actionIcon != null && onAction != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) {
                Text(actionIcon, fontSize = 22.sp, color = Color.White)
            }
        }
    }
}

// ── CozmoButton ────────────────────────────────────────────────────────────────

@Composable
fun CozmoButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = PrimaryBlue,
    icon: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 64.dp, minHeight = 64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
    ) {
        if (icon != null) {
            Text(icon, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

// ── CozmoTile ──────────────────────────────────────────────────────────────────

/**
 * Large navigation tile used on HomeScreen.
 * Shows icon, label, and optional pulse animation when [pulse] is true.
 */
@Composable
fun CozmoTile(
    icon: String,
    label: String,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "tile_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = if (pulse) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Card(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier.sizeIn(minWidth = 64.dp, minHeight = 64.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(icon, fontSize = (48 * scale).sp)
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── EmotionBadge ───────────────────────────────────────────────────────────────

@Composable
fun EmotionBadge(
    emotion: Emotion,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val (icon, label, tint) = emotion.toDisplay()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(icon, fontSize = 22.sp)
        if (showLabel) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = tint,
            )
        }
    }
}

private data class EmotionDisplay(val icon: String, val label: String, val tint: Color)

private fun Emotion.toDisplay() = when (this) {
    Emotion.HAPPY     -> EmotionDisplay("😄", "Happy", CozmoYellow)
    Emotion.EXCITED   -> EmotionDisplay("🤩", "Excited", CozmoOrange)
    Emotion.ANGRY     -> EmotionDisplay("😠", "Angry", ErrorRed)
    Emotion.SAD       -> EmotionDisplay("😢", "Sad", Color(0xFF5B8DEF))
    Emotion.SURPRISED -> EmotionDisplay("😲", "Surprised", Color(0xFF9C27B0))
    Emotion.BORED     -> EmotionDisplay("😑", "Bored", TextSecondary)
    Emotion.SCARED    -> EmotionDisplay("😨", "Scared", Color(0xFF795548))
    Emotion.NEUTRAL   -> EmotionDisplay("🙂", "Curious", PrimaryBlue)
}

// ── CozmoJoystick ──────────────────────────────────────────────────────────────

/**
 * Virtual joystick for DriveScreen.
 * Emits normalised (x, y) values in range -1.0..1.0 via [onJoystickMove].
 * Returns to centre and emits (0, 0) on release.
 *
 * Dead zone: 10% of radius — prevents drift from imprecise thumb placement.
 */
@Composable
fun CozmoJoystick(
    onJoystickMove: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
    outerRadius: Dp = 80.dp,
    thumbRadius: Dp = 30.dp,
    deadZone: Float = 0.10f,
) {
    val haptic = LocalHapticFeedback.current
    var thumbOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(outerRadius * 2)
            .clip(CircleShape)
            .background(PrimaryBlue.copy(alpha = 0.15f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        isDragging = false
                        thumbOffset = Offset.Zero
                        onJoystickMove(0f, 0f)
                    },
                    onDragCancel = {
                        isDragging = false
                        thumbOffset = Offset.Zero
                        onJoystickMove(0f, 0f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val maxPx = outerRadius.toPx() - thumbRadius.toPx()
                        val newOffset = thumbOffset + dragAmount
                        val dist = hypot(newOffset.x, newOffset.y)
                        thumbOffset = if (dist <= maxPx) newOffset
                                      else newOffset * (maxPx / dist)

                        // Normalise to -1..1
                        var nx = (thumbOffset.x / maxPx).coerceIn(-1f, 1f)
                        var ny = -(thumbOffset.y / maxPx).coerceIn(-1f, 1f) // invert Y

                        // Apply dead zone
                        if (kotlin.math.abs(nx) < deadZone) nx = 0f
                        if (kotlin.math.abs(ny) < deadZone) ny = 0f

                        onJoystickMove(nx, ny)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Outer ring indicator
        Box(
            modifier = Modifier
                .size((outerRadius - 4.dp) * 2)
                .clip(CircleShape)
                .background(PrimaryBlue.copy(alpha = if (isDragging) 0.25f else 0.1f)),
        )
        // Thumb
        Box(
            modifier = Modifier
                .size(thumbRadius * 2)
                .offset(
                    x = thumbOffset.x.dp / 3f, // rough dp conversion for preview
                    y = thumbOffset.y.dp / 3f,
                )
                .clip(CircleShape)
                .background(if (isDragging) PrimaryBlue else PrimaryBlue.copy(alpha = 0.7f)),
        )
    }
}

// ── ConnectionBanner ───────────────────────────────────────────────────────────

@Composable
fun ConnectionBanner(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CozmoOrange)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Looking for Cozmo...",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }
    }
}

// ── CozmoErrorState ────────────────────────────────────────────────────────────

@Composable
fun CozmoErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("😟", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(24.dp))
            CozmoButton(label = "Try Again", onClick = onRetry, icon = "🔄")
        }
    }
}

// ── LoadingOverlay ─────────────────────────────────────────────────────────────

@Composable
fun LoadingOverlay(visible: Boolean, label: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                Text(label, color = Color.White,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}

// ── CozmoSnackbar ─────────────────────────────────────────────────────────────

enum class SnackbarKind { SUCCESS, FAILURE, INFO }

@Composable
fun CozmoSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        Snackbar(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = PrimaryBlue,
            contentColor = Color.White,
        ) {
            Text(data.visuals.message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
