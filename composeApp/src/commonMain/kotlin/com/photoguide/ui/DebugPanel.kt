package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoguide.state.AnalysisPhase
import com.photoguide.state.AppState

/**
 * 调试浮层。
 *
 * 需求里明确要求 "debug 模式可以标出识别的物体轮廓用于判断准确性"。
 * Phase 1 还没有本地检测器，所以现在能画的是**链路每一段的耗时**——
 * 但结构和位置先立住：Phase 2 接入 YOLO/pose 后，
 * 这里会同时叠加归一化坐标的轮廓绘制。
 */
@Composable
fun DebugPanel(state: AppState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6101014))
            .padding(10.dp)
            .width(190.dp),
    ) {
        Text(
            text = "DEBUG",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))

        Text(
            text = "phase = ${state.phase.name}",
            color = Color(0xFFB6B6C0),
            fontSize = 11.sp,
        )
        Text(
            text = "perception = ${state.perceptionId}",
            color = Color(0xFFB6B6C0),
            fontSize = 11.sp,
        )
        state.lastPhotoInfo?.let {
            Text(text = it, color = Color(0xFFB6B6C0), fontSize = 10.sp)
        }

        Spacer(Modifier.height(8.dp))
        Text(text = "分段耗时", color = Color(0xFF7BD88F), fontSize = 11.sp, fontWeight = FontWeight.Bold)

        if (state.timings.isEmpty()) {
            Text(text = "（尚未拍摄）", color = Color(0xFF6A6A75), fontSize = 11.sp)
        } else {
            state.timings.forEach { t ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(t.stage, color = Color(0xFFD0D0D8), fontSize = 11.sp)
                    Text("${t.ms} ms", color = Color(0xFFFFC24B), fontSize = 11.sp)
                }
            }
            val total = state.timings.sumOf { it.ms }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("合计", color = Color(0xFFD0D0D8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("$total ms", color = Color(0xFFFFC24B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(text = "最近延迟趋势", color = Color(0xFF7BD88F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val samples = state.trend.samples()
        if (samples.isEmpty()) {
            Text(text = "（无样本）", color = Color(0xFF6A6A75), fontSize = 11.sp)
        } else {
            Text(
                text = "P50 ${state.trend.p50()} ms ｜ P99 ${state.trend.p99()} ms",
                color = Color(0xFFD0D0D8),
                fontSize = 11.sp,
            )
            Sparkline(values = samples, modifier = Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp))
        }

        state.rawResponse?.let { raw ->
            Spacer(Modifier.height(8.dp))
            Text(text = "原始返回", color = Color(0xFF7BD88F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                text = raw.take(220) + if (raw.length > 220) " …" else "",
                color = Color(0xFF9A9AA5),
                fontSize = 10.sp,
                modifier = Modifier.fillMaxHeight(0.25f),
            )
        }

        if (state.phase == AnalysisPhase.FAILED) {
            Spacer(Modifier.height(6.dp))
            Text(text = "上次调用失败", color = Color(0xFFFF6B6B), fontSize = 11.sp)
        }
    }
}

/** 极简折线图。Phase 2 会换成真正的帧间隔条状图。 */
@Composable
private fun Sparkline(values: List<Long>, modifier: Modifier = Modifier) {
    val maxV = (values.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Box(modifier = modifier) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val n = values.size
            if (n < 2) return@Canvas
            val dx = size.width / (n - 1).toFloat()
            var prevX = 0f
            var prevY = size.height * (1f - values[0].toFloat() / maxV)
            for (i in 1 until n) {
                val x = dx * i
                val y = size.height * (1f - values[i].toFloat() / maxV)
                drawLine(
                    color = Color(0xFFFFC24B),
                    start = androidx.compose.ui.geometry.Offset(prevX, prevY),
                    end = androidx.compose.ui.geometry.Offset(x, y),
                    strokeWidth = 2f,
                )
                prevX = x
                prevY = y
            }
        }
    }
}
