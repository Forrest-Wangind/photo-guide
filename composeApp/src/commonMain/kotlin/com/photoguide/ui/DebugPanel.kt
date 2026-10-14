package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoguide.prompt.PromptTrace
import com.photoguide.state.AnalysisPhase
import com.photoguide.state.AppState

/**
 * 调试浮层。
 *
 * 需求里明确要求 "debug 模式可以标出识别的物体轮廓用于判断准确性"。
 * Phase 1 还没有本地检测器，所以现在能画的是**链路每一段的耗时**——
 * 但结构和位置先立住：Phase 2 接入 YOLO/pose 后，
 * 这里会同时叠加归一化坐标的轮廓绘制。
 *
 * 另含**提示词逐字查看**：提示词是本产品最容易出错的一环
 * （"对着空房间写台词"就是这么冒出来的），必须能在真机上看实际发了什么。
 *
 * 两个视图（概览 / 提示词）在**同一个滚动容器**内切换，不做嵌套滚动——
 * 同方向嵌套 verticalScroll 在 Compose 里会直接抛异常。
 */
@Composable
fun DebugPanel(state: AppState, modifier: Modifier = Modifier) {
    var showPrompt by remember { mutableStateOf(false) }

    // 拍过照就用真实发送的快照；没拍过则按当前意图预演（带 preview 标注）
    val prompt: PromptTrace = remember(state.lastPrompt, state.intent, state.config) {
        state.lastPrompt ?: state.previewPrompt()
    }

    // 内容超出屏幕时必须能滚：限高 90% + verticalScroll，绝不静默裁掉下半段
    BoxWithConstraints(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                // 看提示词时宽一点：190dp 下长句换行太碎，逐字核对提示词会很难受
                .width(if (showPrompt) 262.dp else 190.dp)
                .heightIn(max = maxHeight * 0.9f)
                // 吞掉面板区域内的点击：点面板空白不该被当成"点空白处关闭"
                .pointerInput(Unit) { detectTapGestures { } }
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xE6101014))
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (showPrompt) {
                PromptView(prompt = prompt, onBack = { showPrompt = false })
            } else {
                OverviewView(state = state, prompt = prompt, onOpenPrompt = { showPrompt = true })
            }
        }
    }
}

// ------------------------------------------------------------------ 概览视图

@Composable
private fun OverviewView(state: AppState, prompt: PromptTrace, onOpenPrompt: () -> Unit) {
    // 顶部也放一个入口：面板内容长了要滚动，底部那个不一定够得着
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "DEBUG",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "提示词 ›",
            color = Color(0xFF7BD88F),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenPrompt)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
    Spacer(Modifier.height(6.dp))

    Text(text = "phase = ${state.phase.name}", color = Color(0xFFB6B6C0), fontSize = 11.sp)
    Text(text = "perception = ${state.perceptionId}", color = Color(0xFFB6B6C0), fontSize = 11.sp)
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

    // ---------- 提示词入口 ----------
    // ⚠️ 整块（标题 + 说明行）都必须可点。
    // 曾经只给标题行绑了 clickable，而"点开逐字查看"这行字在它下面、不可点 ——
    // 用户照着提示去点那行字，什么都不会发生。
    // 教训：可点区域必须覆盖你写的每一句"点这里"。
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x1FFFFFFF))
            .clickable(onClick = onOpenPrompt)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "提示词 ›",
                color = Color(0xFF7BD88F),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (prompt.preview) "预演" else "已发送",
                color = if (prompt.preview) Color(0xFFE0C060) else Color(0xFF9A9AA5),
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "系统 ${prompt.system.length} 字 · 用户 ${prompt.user.length} 字，点开逐字查看",
            color = Color(0xFF9A9AA5),
            fontSize = 10.sp,
        )
    }

    state.rawResponse?.let { raw ->
        Spacer(Modifier.height(8.dp))
        Text(text = "原始返回", color = Color(0xFF7BD88F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            text = raw.take(1000) + if (raw.length > 1000) " …" else "",
            color = Color(0xFF9A9AA5),
            fontSize = 10.sp,
        )
    }

    if (state.phase == AnalysisPhase.FAILED) {
        Spacer(Modifier.height(6.dp))
        Text(text = "上次调用失败", color = Color(0xFFFF6B6B), fontSize = 11.sp)
    }
}

// ------------------------------------------------------------------ 提示词视图

@Composable
private fun PromptView(prompt: PromptTrace, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "‹ 返回",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "提示词",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    Spacer(Modifier.height(6.dp))
    // 预演与真实快照必须一眼可分：否则会拿"没发过的内容"去解释一次真实失败
    Text(
        text = if (prompt.preview) {
            "⚠ 尚未拍摄，以下是按当前意图预演的提示词，不是实际发送内容"
        } else {
            "✓ 最近一次请求实际发送的内容"
        },
        color = if (prompt.preview) Color(0xFFE0C060) else Color(0xFF7BD88F),
        fontSize = 10.sp,
    )

    Spacer(Modifier.height(4.dp))
    Text(text = "model = ${prompt.model}", color = Color(0xFFB6B6C0), fontSize = 10.sp)
    Text(text = "max_tokens = ${prompt.maxTokens}", color = Color(0xFFB6B6C0), fontSize = 10.sp)
    Text(
        text = if (prompt.imageAttached) {
            "附带照片 = 是（模型能看到画面）"
        } else {
            "附带照片 = 否（模型看不到画面，只依据文字）"
        },
        color = Color(0xFFB6B6C0),
        fontSize = 10.sp,
    )

    Spacer(Modifier.height(10.dp))
    Text(text = "SYSTEM", color = Color(0xFF7BD88F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(3.dp))
    Text(text = prompt.system, color = Color(0xFFBFBFC8), fontSize = 10.sp, lineHeight = 14.sp)

    Spacer(Modifier.height(12.dp))
    Text(text = "USER", color = Color(0xFF7BD88F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(3.dp))
    Text(text = prompt.user, color = Color(0xFFBFBFC8), fontSize = 10.sp, lineHeight = 14.sp)
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
