package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.photoguide.decision.HintKind
import com.photoguide.decision.HintStep

/** 收起态最多再露几条次要建议。多了就折进「还有 N 条」。 */
private const val SECONDARY_LIMIT = 2

/**
 * 提示卡 —— 主次分层，**零操作即可执行**。
 *
 * 为什么不是"一次一条 + 翻页"：屏幕一次只显示得下一条，切换必须有个触发源
 * （用户点 / 定时 / 画面变化）。拍摄时单手举着手机，没人愿意翻页；定时自动翻
 * 会在读到一半时把字换掉；画面变化要本地感知层，Phase 1 还没有。
 * 所以改成**一次呈现、视觉分层**：不翻页，靠字号和权重分出"现在做"和"还有"。
 *
 * 结构：
 *  - 警告细条（[CompositionHint.warnings]）：常驻在卡片最上方，不随展开/收起消失。
 *    它是可信度信息，不是动作，绝不能混进下面那条执行序列里被翻过去。
 *  - 主条（[CompositionHint.steps] 第一条）：17sp，带类型标签和左侧色条。
 *    用户 0 次点击就能拿到"现在该干什么"。
 *  - 次条：12sp 压暗，最多 2 条。**只用来知道"还有"**，不指望拍摄时读清。
 *  - 计数：「还有 N 条 ›」——漏不掉，且想看的才点。
 *  - 展开：完整序列，整体限高可滚动，绝不吞掉整个屏幕。
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
    val steps = hint.steps
    // 收起态能显示 1 主 + SECONDARY_LIMIT 次；超出的才值得给一个"展开"
    val collapsible = steps.size > 1 + SECONDARY_LIMIT

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 吞掉卡片区域内的点击：点正文空白不该被当成"点空白处收起"
            .pointerInput(Unit) { detectTapGestures { } }
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
    ) {
        if (hint.warnings.isNotEmpty()) {
            WarningsStrip(hint.warnings)
        }

        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {

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
                if (collapsible) {
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

            Spacer(Modifier.height(9.dp))

            if (steps.isEmpty()) {
                Text(
                    text = "这次没有需要调整的，直接按快门",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            } else if (expanded) {
                // 展开态：完整序列，限高可滚动 —— 展开也不许吞屏
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    steps.forEachIndexed { index, step ->
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        HintStepBlock(step = step, primary = index == 0)
                    }
                }
            } else {
                HintStepBlock(step = steps.first(), primary = true)
                steps.drop(1).take(SECONDARY_LIMIT).forEach { step ->
                    CompactStepLine(step = step, maxLines = 1)
                }
                val rest = steps.size - 1 - SECONDARY_LIMIT
                if (rest > 0) {
                    Text(
                        text = "还有 $rest 条 ›",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .clickable { onExpandedChange(true) }
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 警告细条。刻意**不**进 steps 序列，也不随展开/收起消失：
 * 「输出被截断」「没收到画面」这类信息一旦被折叠，用户就会把它当成正常建议读下去。
 */
@Composable
private fun WarningsStrip(warnings: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x33FFC24B))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        warnings.forEach { warning ->
            Text(
                text = "⚠ $warning",
                color = Color(0xFFFFD98A),
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

/**
 * 主条 / 展开后的完整条目。
 *
 * [primary] 为 true 时用 17sp + 左侧色条，是"现在就要做的那一条"；
 * 其余用 13sp，是"还有这些"——同一个组件两种权重，避免两套布局走样。
 */
@Composable
private fun HintStepBlock(step: HintStep, primary: Boolean) {
    val accent = step.kind.accent()
    if (!primary) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.75f)),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.kind.label,
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = step.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        return
    }

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.kind.label,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = step.text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 24.sp,
            )
        }
    }
}

/** 收起态的次要条目：一行压暗小字，只负责让人知道"还有这一条"。 */
@Composable
private fun CompactStepLine(step: HintStep, maxLines: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(step.kind.accent().copy(alpha = 0.75f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = step.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 类型配色：蓝=手（机位）、黄=嘴（台词，本产品最高价值）、绿=眼（画面要点）、灰=诊断。 */
private fun HintKind.accent(): Color = when (this) {
    HintKind.CAMERA -> Color(0xFF9BD1FF)
    HintKind.LINE -> Color(0xFFFFC24B)
    HintKind.SCENE -> Color(0xFF7BD88F)
    HintKind.ISSUE -> Color(0xFFB6B6C0)
}

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> Color(0xFF7BD88F)
    score >= 60 -> Color(0xFFFFC24B)
    else -> Color(0xFFFF8A80)
}
