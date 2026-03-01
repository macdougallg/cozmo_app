package com.macdougallg.cozmoplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.macdougallg.cozmoplay.ui.CozmoPlayApp
import com.macdougallg.cozmoplay.ui.theme.CozmoPlayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CozmoPlayTheme {
                CozmoPlayApp(context = this)
            }
        }
    }
}
