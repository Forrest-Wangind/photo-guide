package com.photoguide.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoguide.state.AppState
import com.photoguide.state.HistoryEntry
import com.photoguide.state.MAX_HISTORY
import com.photoguide.util.Base64
import com.photoguide.util.decodeImageBitmapOrNull
import com.photoguide.util.nowEpochMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 历史记录页（最近 [MAX_HISTORY] 条）。
 *
 * 为什么要有这一页：建议卡是**一次性**的 —— 拍完看一眼、下次快门就被替换掉。
 * 而"她刚才那句台词是怎么说的""上一张的机位提示"恰恰是拍完几秒后最想回看的，
 * 那时候卡片早没了（或者被新一次分析顶掉）。
 *
 * 三条克制：
 * ① 只读。不提供"套用这条设置再拍一张"—— 那会把历史变成第二个意图入口，
 *    与底部面板抢同一份状态（"同一件事两条写入路径"是本项目反复踩的坑）。
 * ② 不进数据库，整份历史就是一个 JSON 列表存在键值存储里，上限 20 条。
 * ③ 缩略图失败时不假装有：明确显示「无图」。
 */
@Composable
fun HistoryScreen(state: AppState) {
    val entries = state.history
    // "现在"只取一次：列表里的"3 分钟前"不需要秒级刷新，
    // 每次重组都调 nowEpochMs() 也不会更准，只会让标签在滑动时跳变。
    val now = remember { nowEpochMs() }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    /**
     * 正在全屏查看的那条记录（点缩略图进入）。
     *
     * 为什么状态提在这里、浮层挂在页面根节点：卡片活在 LazyColumn 的 item 里，
     * 浮层一旦挂进 item 就会随滚动被裁切、滑出屏幕就被回收；而且它要盖住顶栏，
     * 只能是整页级的。
     */
    var previewId by remember { mutableStateOf<Long?>(null) }

    // 二次确认 4 秒后自动收回：误触第一下不该把按钮永久停在"确认清空"上
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(4000)
            confirmClear = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // 顶栏固定不滚（与设置页同一条纪律）：滚动区里放标题，滚下去就找不回来了
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "拍摄记录",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${entries.size}/$MAX_HISTORY 条 · 只存本机，不进数据库",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                if (entries.isNotEmpty()) {
                    Text(
                        text = if (confirmClear) "确认清空" else "清空",
                        color = if (confirmClear) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = if (confirmClear) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (confirmClear) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            )
                            .clickable {
                                if (confirmClear) {
                                    state.clearHistory()
                                    confirmClear = false
                                } else {
                                    confirmClear = true
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "完成",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { state.closeHistory() }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            if (entries.isEmpty()) {
                EmptyHistory()
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = entries, key = { it.id }) { entry ->
                    HistoryCard(
                        entry = entry,
                        now = now,
                        expanded = expandedId == entry.id,
                        onToggle = { expandedId = if (expandedId == entry.id) null else entry.id },
                        onThumbClick = {
                            // 没有缩略图的条目不进预览：宁可什么都不发生，
                            // 也不要弹出一个空白全屏（那看起来像崩了）
                            if (entry.thumbBase64 != null) previewId = entry.id
                        },
                    )
                }
                item {
                    Text(
                        text = "点缩略图可全屏查看（历史里只有长边 240px 的小图，原图在系统相册）。" +
                            "最多保留 $MAX_HISTORY 条，再拍新的一张会挤掉最老的一条。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )
                }
            }
        }

        // 全屏预览浮层：盖在整页之上（含顶栏），点 ✕ 回到列表
        entries.firstOrNull { it.id == previewId }?.let { entry ->
            HistoryPreview(entry = entry, onClose = { previewId = null })
        }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有拍摄记录",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "按下快门、拿到一次建议之后，这里会留下最近 $MAX_HISTORY 张的缩略图与当时的全部提示。" +
                "记录下来是为了\u201C拍完几秒后还想回看那张说了什么\u201D —— " +
                "建议卡本身是一次性的，下次快门就被替换。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

/**
 * 历史记录的全屏预览。
 *
 * ⭐ 这里复用的还是那张 240px 缩略图，**不是原图** —— 所以必须用 [note] 说清楚。
 * 放大到 2.5x 一定会糊，那是"历史只存小图"的必然代价；不说明的话，
 * 用户只会觉得这个 App 拍照很糊，把一个存储取舍的代价伪装成了画质问题。
 * 真正的原图本来就在系统相册（每次拍摄已自动入库），这里不重复存。
 */
@Composable
private fun HistoryPreview(entry: HistoryEntry, onClose: () -> Unit) {
    val encoded = entry.thumbBase64 ?: return
    val bitmap by produceState<ImageBitmap?>(initialValue = null, encoded) {
        value = withContext(Dispatchers.Default) {
            Base64.decode(encoded)?.let { decodeImageBitmapOrNull(it) }
        }
    }
    val bmp = bitmap ?: return
    PhotoPreviewOverlay(
        bitmap = bmp,
        onClose = onClose,
        note = "这是历史里的缩略图（长边 240px），放大后会糊；原图已存入系统相册",
    )
}

/**
 * 一张记录卡片。收起态只有"什么时候 + 拍了什么 + 最有用的一句"，
 * 展开才给完整双栏 —— 历史列表要能一屏扫完几条，全部默认展开就没法扫了。
 */
@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    now: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onThumbClick: () -> Unit,
) {
    val hint = entry.hint
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onToggle)
            .padding(10.dp),
    ) {
        Row {
            // 缩略图自己带 clickable：内层点击会先被消费，所以点图看大图、
            // 点卡片其它地方展开/收起，两个动作互不干扰（不需要额外的命中判断）。
            HistoryThumb(entry = entry, onClick = onThumbClick)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${entry.timeLabel(now)} · ${entry.sceneLabel}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    entry.score?.let { score ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(scoreColor(score))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "$score",
                                color = Color(0xFF101014),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = entry.headline.ifBlank { "构图建议" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!expanded) {
                    val preview = hint.secondColumnLines.firstOrNull { it.isNotBlank() }
                        ?: hint.photographerActions.firstOrNull { it.isNotBlank() }
                    if (preview != null) {
                        Text(
                            text = if (hint.hasPerson) "\u201C$preview\u201D" else "· $preview",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
            Text(
                text = if (expanded) "收起" else "展开",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }

        if (expanded) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (entry.freeText.isNotBlank()) {
                Text(
                    text = "当时描述：${entry.freeText}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            DetailBlock(
                title = "你（摄影师）",
                accent = MaterialTheme.colorScheme.secondary,
                lines = hint.photographerActions,
                emptyText = "无需调整机位",
            )
            Spacer(Modifier.height(8.dp))
            DetailBlock(
                title = if (hint.hasPerson) "对她说" else "画面要点",
                accent = MaterialTheme.colorScheme.primary,
                lines = hint.secondColumnLines,
                emptyText = if (hint.hasPerson) "不用开口" else "无需额外强调",
                quote = hint.hasPerson,
            )
            if (hint.issues.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                DetailBlock(
                    title = "画面对照问题",
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    lines = hint.issues,
                    emptyText = "",
                )
            }
            if (hint.warnings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                DetailBlock(
                    title = "⚠ 当时的不确定项",
                    accent = Color(0xFFFFD98A),
                    lines = hint.warnings,
                    emptyText = "",
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("参考线 · ")
                    append(entry.guideLabel.ifBlank { "无" })
                    if (entry.modelLabel.isNotBlank()) {
                        append("　模型 · ")
                        append(entry.modelLabel)
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
    }
}

/** 展开态的一段：标题 + 若干行。空白段直接不渲染（[lines] 空且没标题就跳过）。 */
@Composable
private fun DetailBlock(
    title: String,
    accent: Color,
    lines: List<String>,
    emptyText: String,
    quote: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(8.dp),
    ) {
        Text(title, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (lines.isEmpty()) {
            Text(
                text = emptyText.ifBlank { "—" },
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
        } else {
            lines.forEach { line ->
                Text(
                    text = if (quote) "\u201C$line\u201D" else "· $line",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 缩略图。**懒解码**：base64 → 字节 → 位图三步都在后台线程，
 * 且只在条目进入可见区时才跑（LazyColumn 只在需要时组合）。
 *
 * 解码失败 / 没有图 → 明写「无图」，且**不可点**（点了也不会弹出空白全屏）。
 * 历史列表里"照片位置是空的"最容易被误读成"我这轮没拍成照片"，
 * 必须用文字说清这是记录缺图，不是拍摄失败。
 */
@Composable
private fun HistoryThumb(entry: HistoryEntry, onClick: () -> Unit) {
    val encoded = entry.thumbBase64
    val bitmap by produceState<ImageBitmap?>(initialValue = null, encoded) {
        value = encoded?.let { text ->
            withContext(Dispatchers.Default) {
                Base64.decode(text)?.let { decodeImageBitmapOrNull(it) }
            }
        }
    }

    val shape = RoundedCornerShape(10.dp)
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "该次拍摄的缩略图，点按全屏查看",
            modifier = Modifier
                .height(72.dp)
                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                .clip(shape)
                .clickable(onClick = onClick),
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 72.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (encoded == null) "无图" else "…",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp,
            )
        }
    }
}
