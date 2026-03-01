package com.macdougallg.cozmoplay.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.macdougallg.cozmoplay.protocol.ICozmoProtocol
import com.macdougallg.cozmoplay.types.ConnectionState
import com.macdougallg.cozmoplay.types.Emotion
import com.macdougallg.cozmoplay.types.ProtocolState
import com.macdougallg.cozmoplay.ui.components.*
import com.macdougallg.cozmoplay.ui.theme.*
import com.macdougallg.cozmoplay.wifi.ICozmoWifi
import kotlinx.coroutines.flow.StateFlow
import org.koin.androidx.compose.koinViewModel

// ── ViewModel ─────────────────────────────────────────────────────────────────

class HomeViewModel(
    private val wifi: ICozmoWifi,
    private val protocol: ICozmoProtocol,
) : ViewModel() {
    val connectionState: StateFlow<ConnectionState> = wifi.connectionState
    val emotion: StateFlow<com.macdougallg.cozmoplay.types.RobotState> = protocol.robotState
    val cubeStates = protocol.cubeStates
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    onNavigateToDrive: () -> Unit,
    onNavigateToAnimations: () -> Unit,
    onNavigateToExplore: () -> Unit,
    onNavigateToCubes: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val robotState by viewModel.emotion.collectAsState()
    val cubes by viewModel.cubeStates.collectAsState()
    val cubesDetected = cubes.any { it.isVisible }
    val isDisconnected = connectionState is ConnectionState.Disconnected
        || connectionState is ConnectionState.Scanning

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {

        // Connection status bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(PrimaryBlue)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🤖", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Connected to Cozmo!",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(48.dp)) {
                Text("⚙️", fontSize = 20.sp)
            }
        }

        // Disconnection banner
        ConnectionBanner(visible = isDisconnected)

        Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            // Left mood panel (20% width)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(120.dp)
                    .background(SurfaceWhite, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Mood", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                EmotionBadge(emotion = robotState.emotion, showLabel = true)
            }

            Spacer(Modifier.width(16.dp))

            // 2x2 tile grid
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CozmoTile(
                        icon = "🕹️", label = "Drive",
                        backgroundColor = PrimaryBlue,
                        onClick = onNavigateToDrive,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    CozmoTile(
                        icon = "✨", label = "Tricks",
                        backgroundColor = CozmoOrange,
                        onClick = onNavigateToAnimations,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CozmoTile(
                        icon = "🧭", label = "Explore",
                        backgroundColor = CozmoYellow,
                        onClick = onNavigateToExplore,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    CozmoTile(
                        icon = "🧊", label = "Cubes",
                        backgroundColor = Color(0xFF7B1FA2),
                        onClick = onNavigateToCubes,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        pulse = cubesDetected,
                    )
                }
            }
        }
    }
}
