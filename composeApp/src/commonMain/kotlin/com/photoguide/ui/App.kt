package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.photoguide.state.rememberAppState

@Composable
fun App() {
    PhotoGuideTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val state = rememberAppState()
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CameraScreen(state)
                if (state.showSettings) {
                    SettingsScreen(state)
                }
            }
        }
    }
}
