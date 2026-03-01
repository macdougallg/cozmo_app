package com.macdougallg.cozmoplay.ui.screens.cubes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macdougallg.cozmoplay.protocol.ICozmoProtocol
import com.macdougallg.cozmoplay.types.ActionResult
import com.macdougallg.cozmoplay.types.CubeLightConfig
import com.macdougallg.cozmoplay.types.CubeState
import com.macdougallg.cozmoplay.ui.components.*
import com.macdougallg.cozmoplay.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

// ── ViewModel ─────────────────────────────────────────────────────────────────

class CubesViewModel(private val protocol: ICozmoProtocol) : ViewModel() {

    val cubeStates: StateFlow<List<CubeState>> = protocol.cubeStates

    private val _selectedCubeId = MutableStateFlow<Int?>(null)
    val selectedCubeId: StateFlow<Int?> = _selectedCubeId.asStateFlow()

    private val _actionInProgress = MutableStateFlow(false)
    val actionInProgress: StateFlow<Boolean> = _actionInProgress.asStateFlow()

    private val _lastActionMessage = MutableStateFlow<String?>(null)
    val lastActionMessage: StateFlow<String?> = _lastActionMessage.asStateFlow()

    fun selectCube(objectId: Int) { _selectedCubeId.value = objectId }

    fun setLightColor(color: Int) {
        val objectId = _selectedCubeId.value ?: return
        val config = CubeLightConfig(color = color, onPeriodMs = 500, offPeriodMs = 0)
        protocol.setCubeLights(objectId, listOf(config, config, config, config))
    }

    fun pickUp() = runAction("Cozmo is picking it up!") {
        val id = _selectedCubeId.value ?: return@runAction ActionResult.Failure("No cube selected")
        protocol.pickupObject(id)
    }

    fun roll() = runAction("Cozmo is rolling it!") {
        val id = _selectedCubeId.value ?: return@runAction ActionResult.Failure("No cube selected")
        protocol.rollObject(id)
    }

    fun putDown() = runAction("Cozmo is putting it down!") {
        protocol.placeObject()
    }

    fun clearActionMessage() { _lastActionMessage.value = null }

    private fun runAction(progressLabel: String, action: suspend () -> ActionResult) {
        if (_actionInProgress.value) return
        viewModelScope.launch {
            _actionInProgress.value = true
            val result = action()
            _actionInProgress.value = false
            _lastActionMessage.value = when (result) {
                is ActionResult.Success -> "Got it! ✅"
                is ActionResult.Timeout -> "Oops, Cozmo couldn't reach it"
                is ActionResult.Failure -> "Oops, Cozmo couldn't reach it"
                is ActionResult.Abandoned -> null
            }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun CubesScreen(
    onBack: () -> Unit,
    viewModel: CubesViewModel = koinViewModel(),
) {
    val cubes by viewModel.cubeStates.collectAsState()
    val selectedId by viewModel.selectedCubeId.collectAsState()
    val actionInProgress by viewModel.actionInProgress.collectAsState()
    val actionMessage by viewModel.lastActionMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearActionMessage()
        }
    }

    Scaffold(
        snackbarHost = { CozmoSnackbarHost(snackbarHostState) },
        containerColor = AppBackground,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            CozmoTopBar(title = "Cubes", onBack = onBack)

            if (cubes.isEmpty()) {
                // No cubes state
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🧊", fontSize = 64.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No cubes found.\nPut a cube near Cozmo!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Cube grid
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        cubes.forEach { cube ->
                            CubeTile(
                                cube = cube,
                                isSelected = cube.objectId == selectedId,
                                onClick = { viewModel.selectCube(cube.objectId) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Empty slots up to 3
                        repeat(3 - cubes.size) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.Gray.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("No Signal", style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary)
                            }
                        }
                    }

                    // Controls (only when a cube is selected)
                    if (selectedId != null) {
                        // Colour picker
                        Text("Light Colour",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val colours = listOf(
                                0xFFFF0000.toInt() to "🔴",
                                0xFF0000FF.toInt() to "🔵",
                                0xFF00FF00.toInt() to "🟢",
                                0xFFFFFF00.toInt() to "🟡",
                                0xFF800080.toInt() to "🟣",
                                0xFFFFFFFF.toInt() to "⚪",
                            )
                            colours.forEach { (argb, emoji) ->
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.setLightColor(argb)
                                    },
                                    modifier = Modifier.size(72.dp),
                                ) { Text(emoji, fontSize = 36.sp) }
                            }
                        }

                        // Action buttons
                        Text("Actions",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CozmoButton(
                                label = "Pick Up", icon = "💪",
                                onClick = { viewModel.pickUp() },
                                enabled = !actionInProgress,
                                modifier = Modifier.weight(1f).height(80.dp),
                            )
                            CozmoButton(
                                label = "Roll", icon = "🎯",
                                onClick = { viewModel.roll() },
                                enabled = !actionInProgress,
                                modifier = Modifier.weight(1f).height(80.dp),
                                containerColor = CozmoOrange,
                            )
                            CozmoButton(
                                label = "Put Down", icon = "⬇️",
                                onClick = { viewModel.putDown() },
                                enabled = !actionInProgress,
                                modifier = Modifier.weight(1f).height(80.dp),
                                containerColor = SuccessGreen,
                            )
                        }
                    }
                }
            }
        }

        // Action progress overlay
        LoadingOverlay(
            visible = actionInProgress,
            label = "Cozmo is on it!",
        )
    }
}

@Composable
private fun CubeTile(
    cube: CubeState,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Card(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .height(120.dp)
            .then(if (isSelected) Modifier.border(3.dp, PrimaryBlue, RoundedCornerShape(16.dp))
                  else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) PrimaryBlue.copy(alpha = 0.1f) else SurfaceWhite,
        ),
        elevation = CardDefaults.cardElevation(if (isSelected) 6.dp else 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🧊", fontSize = 32.sp)
            Text(
                "Cube ${cube.cubeId}",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary,
            )
            // Signal strength dots
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(5) { i ->
                    val filled = (cube.signalStrength * 5) > i
                    Box(
                        modifier = Modifier.size(6.dp).clip(CircleShape)
                            .background(if (filled) SuccessGreen else Color.Gray.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}
