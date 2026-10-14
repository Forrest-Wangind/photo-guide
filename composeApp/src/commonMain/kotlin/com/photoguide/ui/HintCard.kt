package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.photoguide.decision.CompositionHint

/**
 * 双栏提示卡 —— 本产品的差异化点。
 *
 * 左栏：摄影师（拿手机的人）该做的动作。
 * 右栏：模特该做什么，写成可以**直接照着念**的话。
 *
 * 市面上构图辅助工具只解决"构图对不对"，不解决"你该对她说点什么"。
 * 这个左右分栏就是那个差异。
 */
@Composable
fun HintCard(hint: CompositionHint) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(14.dp),
    ) {
        // ---- 标题行 ----
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
            )
        }

        if (hint.photographerActions.isNotEmpty() || hint.modelLines.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HintColumn(
                    modifier = Modifier.weight(1f),
                    title = "你（摄影师）",
                    accent = MaterialTheme.colorScheme.secondary,
                    lines = hint.photographerActions,
                    emptyText = "无需调整机位",
                )
                HintColumn(
                    modifier = Modifier.weight(1f),
                    title = "对她说",
                    accent = MaterialTheme.colorScheme.primary,
                    lines = hint.modelLines,
                    emptyText = "不用开口",
                    quote = true,
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
