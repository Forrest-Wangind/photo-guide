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
                // 历史记录放在设置之后：两者都是全屏浮层，历史是更"当前"的查看动作，
                // 万一同开也要盖在上面
                if (state.showHistory) {
                    HistoryScreen(state)
                }
            }
        }
    }
}
