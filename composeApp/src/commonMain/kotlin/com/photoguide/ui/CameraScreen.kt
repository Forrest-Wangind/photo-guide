package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoguide.capture.CameraState
import com.photoguide.capture.CameraViewfinder
import com.photoguide.capture.CapturedPhoto
import com.photoguide.capture.rememberCaptureController
import com.photoguide.decision.ScenePreset
import com.photoguide.decision.ShootingIntent
import com.photoguide.state.AnalysisPhase
import com.photoguide.state.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(state: AppState) {
    val controller = rememberCaptureController()
    val cameraState by controller.state
    val canCapture by controller.canCapture
    var lastPhoto by remember { mutableStateOf<CapturedPhoto?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {

        // ---------- 取景器（各平台原生实现） ----------
        CameraViewfinder(controller = controller, modifier = Modifier.fillMaxSize())

        // ---------- 顶栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "拍摄指导",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Text(
                    text = cameraStateLabel(cameraState),
                    color = statusColor(cameraState),
                    fontSize = 12.sp,
                )
            }
            SmallAction(label = "调试", onClick = state::toggleDebug)
            Spacer(Modifier.width(8.dp))
            SmallAction(label = "设置", onClick = state::openSettings)
        }

        // ---------- 失败/降级横幅（绝不静默失败） ----------
        val failure = state.failure
        val bannerText = failure?.message ?: lastError
        if (bannerText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.92f))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "⚠ ${failure?.kind?.name ?: "CAMERA"}",
                        color = MaterialTheme.colorScheme.onError,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = bannerText,
                        color = MaterialTheme.colorScheme.onError,
                        fontSize = 13.sp,
                    )
                    if (failure?.kind?.isRetryable == true) {
                        Text(
                            text = "点这里重试",
                            color = MaterialTheme.colorScheme.onError,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable {
                                    lastPhoto?.let(state::analyze)
                                    lastError = null
                                },
                        )
                    }
                }
            }
        }

        // ---------- 结果卡片 ----------
        val hint = state.hint
        if (hint != null && state.phase != AnalysisPhase.ANALYZING) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 190.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
            ) {
                HintCard(hint = hint, onDismiss = state::dismissHint)
            }
        }

        // ---------- 底部控制区 ----------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC0E0E11))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ScenePreset.entries.size) { i ->
                    val preset = ScenePreset.entries[i]
                    FilterChip(
                        selected = state.intent.preset == preset,
                        onClick = { state.updateIntent(state.intent.copy(preset = preset)) },
                        label = { Text(preset.displayName, fontSize = 12.sp) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.intent.freeText,
                onValueChange = { state.updateIntent(ShootingIntent(state.intent.preset, it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("补充一句：想要什么感觉？", fontSize = 13.sp) },
                maxLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CaptureButton(
                    enabled = canCapture && state.phase != AnalysisPhase.ANALYZING,
                    analyzing = state.phase == AnalysisPhase.ANALYZING,
                    onClick = {
                        lastError = null
                        state.dismissFailure()
                        controller.capture(
                            onResult = { photo ->
                                lastPhoto = photo
                                state.analyze(photo)
                            },
                            onError = { msg -> lastError = msg },
                        )
                    },
                )
            }
        }

        // ---------- 性能 / 调试浮层 ----------
        if (state.showDebug) {
            DebugPanel(state = state, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun CaptureButton(enabled: Boolean, analyzing: Boolean, onClick: () -> Unit) {
    val ring = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(3.dp, ring, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (analyzing) 26.dp else 56.dp)
                .clip(CircleShape)
                .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            contentAlignment = Alignment.Center,
        ) {
            if (analyzing) {
                Text("…", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

private fun cameraStateLabel(s: CameraState): String = when (s) {
    is CameraState.Starting -> "相机启动中…"
    is CameraState.Ready -> "相机就绪"
    is CameraState.Failed -> if (s.permissionDenied) "未授予相机权限" else "相机不可用"
}

private fun statusColor(s: CameraState): Color = when (s) {
    is CameraState.Ready -> Color(0xFF7BD88F)
    is CameraState.Starting -> Color(0xFFE0C060)
    is CameraState.Failed -> Color(0xFFFF6B6B)
}
