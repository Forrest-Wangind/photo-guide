package com.photoguide.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.sp
import com.photoguide.decision.CompositionHint
import com.photoguide.guide.GuideRecommendation
import com.photoguide.guide.GuideSource

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
 * - 展开：完整双栏，**整卡限高**（可用高度的 55%），正文在剩余空间里滚动，
 *   标题/缩略图/参考线条目恒定可见 —— 绝不吞掉整个屏幕、更不被顶出屏幕顶。
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
    /**
     * 这张照片该开哪条参考线（拍照后由本次建议决定；取景阶段按意图推）。
     *
     * 为什么放在卡片里而不是只放在顶栏：用户刚看完"头往右转一点"，
     * 紧接着就该知道"开三分线、把她眼睛放到右上交点"。两件事在空间上挨着，
     * 才像一套工具；否则取景器上会自己冒出几条线，没人知道是哪儿来的。
     */
    guide: GuideRecommendation? = null,
    onGuideClick: () -> Unit = {},
    /**
     * 本次建议对应的照片（拍完的那张，与建议同一来源）。
     *
     * 为什么建议卡必须带图：文字说"主体放到右上交点"，可用户刚拍的那张
     * 画面在哪、偏了多少，全凭记忆对不上 —— 图文同屏才能"照着改"。
     * null（解码失败/无照片）时整行不渲染，建议文字照常，不阻塞主功能。
     */
    photo: ImageBitmap? = null,
    onPhotoClick: () -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // ⭐ 展开态卡片**总高**封顶（可用高度的 55%）：卡片是底部锚定向上生长的，
        // 只限正文（旧的 360dp）不够 —— 标题行 + 缩略图 + 参考线条目叠加后
        // 照样能把标题行顶出屏幕顶端（真机实测：分数和"收起"按钮被裁掉一半）。
        // 55% 的余量：底部控制区约占 25%、顶栏约占 12%，再留呼吸空间；
        // 内容超出时正文在自己剩余空间里滚动，标题/缩略图/参考线恒定可见。
        val cardMaxHeight = if (maxHeight.isFinite) maxHeight * 0.55f else 440.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = cardMaxHeight)
                // 吞掉卡片区域内的点击：点正文空白不该被当成"点空白处收起"
                .pointerInput(Unit) { detectTapGestures { } }
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // ---- 头部：缩略图在左，标题/分数（收起时还带首句）在右，操作竖排右上 ----
            // 为什么标题挪到图片右侧：标题原本独占一整行、还要和"展开/收起/✕"抢宽度，
            // 只能截断成一行；图片又是标题下方独立一行，把卡片上半截撑得很高。
            // 并排后标题拿到图片右侧那整块宽度（可换 2~3 行不截断），整卡反而矮一截。
            val hasDetail = hint.photographerActions.isNotEmpty() ||
                hint.modelLines.size > 1 ||
                hint.sceneNotes.size > 1 ||
                hint.issues.isNotEmpty()
            Row(modifier = Modifier.fillMaxWidth()) {
                photo?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "本次拍摄的照片，点按查看原图并可缩放",
                        // aspectRatio 与位图实际比例一致，没有变形；高度统一，横竖拍占同一行高
                        modifier = Modifier
                            .height(104.dp)
                            .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onPhotoClick),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // 相对图片垂直居中：展开态右侧只剩标题，贴顶会显得上面挤下面空
                        .align(Alignment.CenterVertically),
                ) {
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
                            // 有图时右侧这块够宽，允许换行 —— 标题是"这张照片怎么了"的
                            // 第一句话，截成一行省略号等于丢信息；无图时没这个空间，退回 2 行
                            maxLines = if (photo != null) 3 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 有图 + 收起：把"最有用那句"也放进右侧，图片旁边的高度不浪费
                    if (photo != null && !expanded) CollapsedBody(hint)
                }
                // 操作竖排贴右上：并排后横向空间紧张，横排会把标题挤回一行截断
                Column(horizontalAlignment = Alignment.End) {
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
            }

            // 无图时的收起态首句（有图时已在右侧渲染，这里不能再画一遍）
            if (photo == null && !expanded) CollapsedBody(hint)

            // ---- 正文（仅展开态） ----
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 正文吃"卡片总高减去固定块（标题/缩略图/参考线）"的剩余空间：
                        // fill=false → 内容不满时按内容收缩，超出时在剩余空间里滚动。
                        // 卡片总高已由外层 heightIn 封顶，这里不再单独限 360dp。
                        .weight(1f, fill = false)
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

            // ---- 参考线入口（收起/展开都在，固定不滚） ----
            // 放在卡片里靠下、常驻可见：拍摄时卡片多为收起态，藏进展开态等于没有。
            guide?.let { GuideShortcut(it, onGuideClick) }
        }
    }
}

/**
 * 卡片底部那条"开哪条线"。
 *
 * 三种来源的措辞必须分开（模型判断 / 建议文字 / 我的场景）——
 * 用户有权知道这条线是"看了这张照片说的"还是"按你选的场景猜的"。
 */
@Composable
private fun GuideShortcut(rec: GuideRecommendation, onClick: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "参考线 · ${rec.guide.displayName}" + when (rec.source) {
                    GuideSource.MODEL -> "（按这张照片）"
                    GuideSource.HINT -> "（按本次建议）"
                    GuideSource.INTENT -> "（按你的场景）"
                },
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = rec.reason,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("切换 ›", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
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

/** 评分徽标配色。历史记录页复用同一套阈值，避免同一个分数在两个页面显示成不同颜色。 */
internal fun scoreColor(score: Int): Color = when {
    score >= 80 -> Color(0xFF7BD88F)
    score >= 60 -> Color(0xFFFFC24B)
    else -> Color(0xFFFF8A80)
}
