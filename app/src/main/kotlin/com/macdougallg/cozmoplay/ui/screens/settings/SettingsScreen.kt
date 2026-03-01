package com.macdougallg.cozmoplay.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.macdougallg.cozmoplay.ui.components.CozmoTopBar
import com.macdougallg.cozmoplay.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.androidx.compose.koinViewModel

// ── ViewModel ─────────────────────────────────────────────────────────────────

class SettingsViewModel(context: Context) : ViewModel() {

    private val prefs = context.getSharedPreferences("cozmoplay_settings", Context.MODE_PRIVATE)

    private val _soundEnabled = MutableStateFlow(prefs.getBoolean("sound_enabled", true))
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _hapticEnabled = MutableStateFlow(prefs.getBoolean("haptic_enabled", true))
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled.asStateFlow()

    private val _cameraAutoEnable = MutableStateFlow(prefs.getBoolean("camera_auto_enable", false))
    val cameraAutoEnable: StateFlow<Boolean> = _cameraAutoEnable.asStateFlow()

    fun setSoundEnabled(enabled: Boolean) {
        _soundEnabled.value = enabled
        prefs.edit().putBoolean("sound_enabled", enabled).apply()
    }

    fun setHapticEnabled(enabled: Boolean) {
        _hapticEnabled.value = enabled
        prefs.edit().putBoolean("haptic_enabled", enabled).apply()
    }

    fun setCameraAutoEnable(enabled: Boolean) {
        _cameraAutoEnable.value = enabled
        prefs.edit().putBoolean("camera_auto_enable", enabled).apply()
    }

    fun restoreOnboarding() {
        prefs.edit().putBoolean("onboarding_complete", false).apply()
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val hapticEnabled by viewModel.hapticEnabled.collectAsState()
    val cameraAutoEnable by viewModel.cameraAutoEnable.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        CozmoTopBar(title = "Settings", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingToggle(
                icon = "🔊",
                label = "Sound Effects",
                description = "Play sounds on button taps and events",
                checked = soundEnabled,
                onCheckedChange = viewModel::setSoundEnabled,
            )
            SettingToggle(
                icon = "📳",
                label = "Haptic Feedback",
                description = "Vibrate on interactive elements",
                checked = hapticEnabled,
                onCheckedChange = viewModel::setHapticEnabled,
            )
            SettingToggle(
                icon = "📷",
                label = "Camera Auto-Enable on Drive",
                description = "Turn camera on automatically when entering Drive screen",
                checked = cameraAutoEnable,
                onCheckedChange = viewModel::setCameraAutoEnable,
            )

            Spacer(Modifier.height(8.dp))

            // Action buttons
            OutlinedButton(
                onClick = viewModel::restoreOnboarding,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("🔄  Restore Setup Guide",
                    style = MaterialTheme.typography.bodyLarge, color = PrimaryBlue)
            }

            // About section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            ) {
                Column(modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("About", style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold), color = TextPrimary)
                    Text("CozmoPlay v1.0", style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary)
                    Text("Open source licences available in app settings",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(
    icon: String,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(icon, fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold), color = TextPrimary)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White,
                    checkedTrackColor = PrimaryBlue),
            )
        }
    }
}
