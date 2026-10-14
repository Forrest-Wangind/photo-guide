package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoguide.decision.CompositionHint

/**
 * 双栏提示卡 —— 本产品的差异化点。
 *
 * 左栏：摄影师（拿手机的人）该做的动作。
 * 右栏**按画面里有没有人物切换**：
 *  - 有人物 → 「对她说」：写成可以**直接照着念**的话（本产品的核心差异）；
 *  - 无人像 → 「画面要点」：拍室内一角/食物/静物时，硬凑"对她说"就是胡说。
 *
 * 空间约束（拍摄场景下取景器必须可见）：
 * - 默认**收起**：只显示标题 + 右侧首条内容（拍摄瞬间最有用的是那句话）。
 * - 展开：完整双栏，整体限高并可滚动，绝不吞掉整个屏幕。
 * - 右上角 ✕ 直接关掉卡片；点击卡片外的空白区由上层收起/关闭。
 *
 * 展开态由外部受控（[expanded] / [onExpandedChange]）—— 因为"点空白收起"
 * 需要父层知道当前是展开还是已收起，才能决定这一次点是收起还是关掉。
 */
@Composable
fun HintCard(
    hint: CompositionHint,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 吞掉卡片区域内的点击：点正文空白不该被当成"点空白处收起"
            .pointerInput(Unit) { detectTapGestures { } }
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // ---- 标题行：分数 + 标题 + 展开/收起 + 关闭 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            hint.score?.let { score ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(scoreColor(score))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "$score",
                        color = Color(0xFF101014),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = hint.headline.ifBlank { "构图建议" },
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            val hasDetail = hint.photographerActions.isNotEmpty() ||
                hint.modelLines.size > 1 ||
                hint.sceneNotes.size > 1 ||
                hint.issues.isNotEmpty()
            if (hasDetail) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onExpandedChange(!expanded) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                text = "✕",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // ---- 正文 ----
        if (!expanded) {
            CollapsedBody(hint)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp) // 展开也不许吞屏
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HintColumn(
                        modifier = Modifier.weight(1f),
                        title = "你（摄影师）",
                        accent = MaterialTheme.colorScheme.secondary,
                        lines = hint.photographerActions,
                        emptyText = "无需调整机位",
                    )
                    // 第二栏标题跟着内容走：画面里有人才是"对她说"，
                    // 拍室内一角/食物/静物时硬写"对她说"就是胡说
                    HintColumn(
                        modifier = Modifier.weight(1f),
                        title = if (hint.hasPerson) "对她说" else "画面要点",
                        accent = MaterialTheme.colorScheme.primary,
                        lines = hint.secondColumnLines,
                        emptyText = if (hint.hasPerson) "不用开口" else "无需额外强调",
                        quote = hint.hasPerson,
                    )
                }

                // 模型两种都给（罕见但可能）：都展示，不静默丢掉任何一段
                if (hint.hasPerson && hint.sceneNotes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "画面要点",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    hint.sceneNotes.forEach { note ->
                        Text(
                            text = "· $note",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                if (hint.issues.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "画面对照问题",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    hint.issues.forEach { issue ->
                        Text(
                            text = "· $issue",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                // 设计原则 3：模型的不确定项必须显式展示，不允许藏起来
                if (hint.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x33FFC24B))
                            .padding(8.dp),
                    ) {
                        Column {
                            hint.warnings.forEach { w ->
                                Text(
                                    text = "⚠ $w",
                                    color = Color(0xFFFFD98A),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** 收起态：一行最有用的内容 + 概要计数。轻到不挡取景。 */
@Composable
private fun CollapsedBody(hint: CompositionHint) {
    // 有人物 → 优先露台词（拍摄瞬间要念的那句）；
    // 无人像 → 露第二条栏的首条要点，没有就退回摄影师动作
    val second = hint.secondColumnLines.firstOrNull { it.isNotBlank() }
    val fallback = hint.photographerActions.firstOrNull { it.isNotBlank() }
    val preview = second ?: fallback

    if (preview != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (second != null && hint.hasPerson) "\u201C$preview\u201D" else "· $preview",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val extra = buildList {
        if (hint.photographerActions.isNotEmpty()) add("机位 ${hint.photographerActions.size} 条")
        if (hint.hasPerson) {
            if (hint.modelLines.size > 1) add("台词 ${hint.modelLines.size} 句")
        } else if (hint.sceneNotes.isNotEmpty()) {
            add("要点 ${hint.sceneNotes.size} 条")
        }
        if (hint.issues.isNotEmpty()) add("问题 ${hint.issues.size} 项")
        if (hint.warnings.isNotEmpty()) add("注意 ${hint.warnings.size} 条")
    }
    if (extra.isNotEmpty()) {
        Text(
            text = extra.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    // 设计原则 3：警告不能只在展开态可见，收起时至少露出首条
    if (hint.warnings.isNotEmpty()) {
        Text(
            text = "⚠ ${hint.warnings.first()}",
            color = Color(0xFFFFD98A),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun HintColumn(
    modifier: Modifier,
    title: String,
    accent: Color,
    lines: List<String>,
    emptyText: String,
    quote: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(10.dp),
    ) {
        Text(
            text = title,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        if (lines.isEmpty()) {
            Text(
                text = emptyText,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        } else {
            lines.forEach { line ->
                Text(
                    text = if (quote) "\u201C$line\u201D" else "· $line",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> Color(0xFF7BD88F)
    score >= 60 -> Color(0xFFFFC24B)
    else -> Color(0xFFFF8A80)
}
