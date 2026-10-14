package com.photoguide.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 相机类应用天然用深色：取景时浅色界面会干扰对画面亮度的判断。
 * 提示卡用高对比的暖色，保证在户外强光下也读得清。
 */
private val PhotoGuideDark = darkColorScheme(
    primary = Color(0xFFFFC24B),
    onPrimary = Color(0xFF241A00),
    secondary = Color(0xFF9BD1FF),
    onSecondary = Color(0xFF00203A),
    background = Color(0xFF0E0E11),
    onBackground = Color(0xFFE8E8EC),
    surface = Color(0xFF17171C),
    onSurface = Color(0xFFE8E8EC),
    surfaceVariant = Color(0xFF23232A),
    onSurfaceVariant = Color(0xFFB6B6C0),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
    outline = Color(0xFF4A4A55),
)

@Composable
fun PhotoGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PhotoGuideDark, content = content)
}
