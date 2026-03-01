package com.macdougallg.cozmoplay.ui.screens.onboarding

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.macdougallg.cozmoplay.ui.components.CozmoButton
import com.macdougallg.cozmoplay.ui.theme.*
import org.koin.androidx.compose.koinViewModel

// ── ViewModel ─────────────────────────────────────────────────────────────────

class OnboardingViewModel(context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("cozmoplay_settings", Context.MODE_PRIVATE)

    fun markComplete() {
        prefs.edit().putBoolean("onboarding_complete", true).apply()
    }

    fun isComplete(): Boolean = prefs.getBoolean("onboarding_complete", false)
}

// ── Data ──────────────────────────────────────────────────────────────────────

private data class OnboardingPage(
    val emoji: String,
    val title: String,
    val instruction: String,
)

private val pages = listOf(
    OnboardingPage("🔋", "Wake Up Cozmo!", "Take Cozmo off the charger and put him on the floor"),
    OnboardingPage("🔘", "Press the Button!", "Press the button on Cozmo's back once.\nHis eyes will light up!"),
    OnboardingPage("⏳", "Wait a Moment", "Wait for the light on Cozmo's back to stop flashing.\nThis takes a few seconds."),
    OnboardingPage("🎉", "You're Ready!", "Now tap 'Connect' to find Cozmo!"),
)

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    var currentPage by remember { mutableStateOf(0) }
    val page = pages[currentPage]
    val isLast = currentPage == pages.size - 1

    Row(modifier = Modifier.fillMaxSize().background(AppBackground)) {

        // Left — large illustration
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(PrimaryBlue.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(page.emoji, fontSize = 120.sp)
        }

        // Right — instruction
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Skip button
            TextButton(
                onClick = { viewModel.markComplete(); onDone() },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Skip", color = TextSecondary)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "${currentPage + 1} of ${pages.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Text(
                    page.title,
                    style = MaterialTheme.typography.displayMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    page.instruction,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Page dots
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.indices.forEach { i ->
                        Box(
                            modifier = Modifier
                                .size(if (i == currentPage) 12.dp else 8.dp)
                                .clip(CircleShape)
                                .background(if (i == currentPage) PrimaryBlue else PrimaryBlue.copy(alpha = 0.3f))
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (currentPage > 0) {
                        OutlinedButton(
                            onClick = { currentPage-- },
                            modifier = Modifier.height(64.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("← Back", color = PrimaryBlue) }
                    }

                    CozmoButton(
                        label = if (isLast) "Connect!" else "Next →",
                        onClick = {
                            if (isLast) {
                                viewModel.markComplete()
                                onDone()
                            } else {
                                currentPage++
                            }
                        },
                        modifier = Modifier.weight(1f).height(64.dp),
                    )
                }
            }
        }
    }
}
