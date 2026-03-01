package com.macdougallg.cozmoplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.macdougallg.cozmoplay.ui.CozmoPlayApp
import com.macdougallg.cozmoplay.ui.theme.CozmoPlayTheme

/**
 * Single activity for the entire CozmoPlay app.
 * Locked to landscape via AndroidManifest.
 * All navigation is handled by Jetpack Compose Navigation inside [CozmoPlayApp].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CozmoPlayTheme {
                CozmoPlayApp()
            }
        }
    }
}
