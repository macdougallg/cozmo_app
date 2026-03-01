package com.macdougallg.cozmoplay.ui.screens.animations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macdougallg.cozmoplay.protocol.ICozmoProtocol
import com.macdougallg.cozmoplay.protocol.messages.AnimationManifest
import com.macdougallg.cozmoplay.types.ActionResult
import com.macdougallg.cozmoplay.ui.components.CozmoTopBar
import com.macdougallg.cozmoplay.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

// ── ViewModel ─────────────────────────────────────────────────────────────────

class AnimationsViewModel(private val protocol: ICozmoProtocol) : ViewModel() {

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _playingAnimation = MutableStateFlow<String?>(null)
    val playingAnimation: StateFlow<String?> = _playingAnimation.asStateFlow()

    val categories = listOf("All") + AnimationManifest.BY_CATEGORY.keys.toList()

    fun animationsFor(category: String): List<String> =
        if (category == "All") AnimationManifest.ALL
        else AnimationManifest.BY_CATEGORY[category] ?: emptyList()

    fun selectCategory(category: String) { _selectedCategory.value = category }

    fun playAnimation(name: String) {
        if (_playingAnimation.value != null) return
        viewModelScope.launch {
            _playingAnimation.value = name
            protocol.playAnimation(name)
            _playingAnimation.value = null
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun AnimationsScreen(
    onBack: () -> Unit,
    viewModel: AnimationsViewModel = koinViewModel(),
) {
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val playingAnimation by viewModel.playingAnimation.collectAsState()
    val animations = viewModel.animationsFor(selectedCategory)

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        CozmoTopBar(title = "Tricks", onBack = onBack)

        if (playingAnimation != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CozmoOrange)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp))
                Text("Now Playing!", color = Color.White,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {

            // Category sidebar
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(140.dp)
                    .background(SurfaceWhite)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewModel.categories.forEach { category ->
                    val isSelected = category == selectedCategory
                    Button(
                        onClick = { viewModel.selectCategory(category) },
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 64.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) PrimaryBlue
                                            else Color.Transparent,
                        ),
                        border = if (!isSelected) ButtonDefaults.outlinedButtonBorder else null,
                    ) {
                        Text(
                            category,
                            color = if (isSelected) Color.White else PrimaryBlue,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // Animation grid
            if (animations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("😔", fontSize = 48.sp)
                        Text("No tricks here yet!",
                            style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(animations) { name ->
                        AnimationTile(
                            name = name,
                            isPlaying = name == playingAnimation,
                            isDisabled = playingAnimation != null && name != playingAnimation,
                            onClick = { viewModel.playAnimation(name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimationTile(
    name: String,
    isPlaying: Boolean,
    isDisabled: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val displayName = name
        .removePrefix("anim_")
        .replace("_", " ")
        .replaceFirstChar { it.uppercase() }
        .take(20)

    Card(
        onClick = {
            if (!isDisabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
        },
        modifier = Modifier
            .size(120.dp)
            .then(if (isPlaying) Modifier.border(3.dp, CozmoOrange, RoundedCornerShape(16.dp))
                  else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isPlaying  -> CozmoOrange.copy(alpha = 0.15f)
                isDisabled -> Color.Gray.copy(alpha = 0.1f)
                else       -> SurfaceWhite
            }
        ),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(if (isPlaying) "⭐" else "🎭", fontSize = 36.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                displayName,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isDisabled) TextSecondary else TextPrimary,
            )
        }
    }
}
