package com.macdougallg.cozmoplay.camera

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macdougallg.cozmoplay.types.CameraState

/**
 * Camera feed composable for embedding in DriveScreen and ExploreScreen.
 *
 * Camera PRD FR-03 requirements:
 * - Maintains 4:3 aspect ratio — never stretches
 * - Shows placeholder when ENABLING (dark grey + spinner)
 * - Shows off-state panel when DISABLED (dark background + camera-off icon)
 * - Shows error panel with context when STREAM_ERROR
 * - Frame updates use derivedStateOf — only the Image element recomposes per frame
 * - Remains in composition tree in all states (never removes itself)
 *
 * Debug FPS overlay shown only in debug builds (FR-05).
 */
@Composable
fun CozmoCamera(
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel,
    showFpsOverlay: Boolean = false, // set true only in debug builds via BuildConfig
) {
    val cameraState by viewModel.cameraState.collectAsState()
    val fps by viewModel.currentFps.collectAsState()

    // Collect latest frame — derivedStateOf ensures only Image recomposes, not parent
    val latestFrame by viewModel.displayFrames.collectAsState(initial = null)
    val displayBitmap by remember { derivedStateOf { latestFrame } }

    Box(
        modifier = modifier
            .aspectRatio(4f / 3f) // Always maintain 4:3 — Camera PRD FR-03
            .background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        when (cameraState) {
            is CameraState.Disabled -> DisabledPanel()
            is CameraState.Enabling -> EnablingPanel()
            is CameraState.Streaming -> StreamingPanel(bitmap = displayBitmap)
            is CameraState.StreamError -> ErrorPanel(
                reason = (cameraState as CameraState.StreamError).reason
            )
            is CameraState.Paused -> StreamingPanel(bitmap = displayBitmap) // show last frame
        }

        // Debug FPS overlay — top-right corner, debug builds only (FR-05)
        if (showFpsOverlay && cameraState is CameraState.Streaming) {
            FpsOverlay(fps = fps, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

// ── Panel Composables ──────────────────────────────────────────────────────────

@Composable
private fun StreamingPanel(bitmap: Bitmap?) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Cozmo camera feed",
            contentScale = ContentScale.Fit, // Maintains aspect ratio — never stretches
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // Frame not yet available — show placeholder briefly
        EnablingPanel()
    }
}

@Composable
private fun EnablingPanel() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            color = Color(0xFF1A6FD4),
            strokeWidth = 3.dp,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = "Starting camera...",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DisabledPanel() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "📷",
            fontSize = 36.sp,
            color = Color.White.copy(alpha = 0.3f),
        )
        Text(
            text = "Camera Off",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ErrorPanel(reason: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        Text(text = "⚠️", fontSize = 32.sp)
        Text(
            text = "Camera unavailable",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // Debug info only — not shown to child in production
        // In production, parent UI handles retry via CameraState.StreamError
    }
}

@Composable
private fun FpsOverlay(fps: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "${"%.1f".format(fps)} fps",
            color = Color.White,
            fontSize = 10.sp,
        )
    }
}
