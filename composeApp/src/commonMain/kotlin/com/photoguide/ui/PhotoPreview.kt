package com.photoguide.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 全屏原图预览：捏合缩放（1x~5x）+ 单指平移 + 双击在 1x/2.5x 间切换。
 *
 * "原图"就是本次拍下的那张 JPEG（~1600px，与发给模型的是同一份）——
 * 不存在更高分辨率的副本，CapturedPhoto 本身就是拍的原图。
 *
 * 实现取舍：
 *  - 变换（scale/offset）只在 `graphicsLayer{}` lambda 里读 —— 绘制阶段失效，
 *    缩放/平移全程不重组（与参考线/意图面板同一条流畅性纪律）；
 *  - **锚点缩放**：捏合时保持两指中心点的画面位置不动，否则图片会"从指尖滑走"；
 *  - scale 回到 1x 时位移强制归零：Fit 模式下图片已铺满可视区，允许平移只会
 *    把图拖出屏幕，用户会以为"卡死了"；
 *  - 双击动画逐帧插值 scale 和 offset（不是只动 scale）：从放大态双击回 1x 时
 *    位移若不平滑归零，会看到图片瞬移；
 *  - 关闭入口只有右上角 ✕：不加"单击空白关闭"（缩放调整时手指轻点极易误触），
 *    也不加"下滑关闭"（与平移手势打架），第一次实现宁可少给手势。
 *
 * 不消费返回键：这是浮层不是 Dialog。需要返回键关闭时再升级成 Dialog。
 */
@Composable
fun PhotoPreviewOverlay(
    bitmap: ImageBitmap,
    onClose: () -> Unit,
    /**
     * 底部手势提示**之上**的一行说明，用于声明"你现在看的到底是什么"。
     *
     * 历史记录页必须传它：那里只有长边 240px 的缩略图，放大到 2.5x 就会糊。
     * 不写这一行，用户只会觉得"这 App 拍照好糊"——把存储取舍的代价
     * 伪装成画质问题，属于静默失败。
     */
    note: String? = null,
) {
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6050508))
            // 捏合缩放 + 平移。注册在 tap 检测**之前**：transform 优先消费多指/移动事件，
            // tap 只在真正的"无位移点击"时触发，两条手势不打架。
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    if (newScale > 1.01f) {
                        // 锚点缩放：显示位置 = (p - center) * scale + offset。
                        // 要让 p 固定 → offset' = offset + (p - center) * (scale - newScale)，再叠 pan。
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = centroid - center
                        offset = offset + radius * (scale - newScale) + pan
                    }
                    scale = newScale
                    if (scale <= 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        val target = if (scale > 1.01f) 1f else 2.5f
                        val startScale = scale
                        val startOffset = offset
                        scope.launch {
                            animate(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 200),
                            ) { f, _ ->
                                scale = startScale + (target - startScale) * f
                                offset = Offset(startOffset.x * (1f - f), startOffset.y * (1f - f))
                            }
                            if (target <= 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            }
                        }
                    },
                )
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "原图预览：捏合缩放，双击放大，单指平移",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )

        // 关闭按钮：深色底 + 白 ✕，亮背景（雪地/天空）下也可见。
        // ⚠️ 必须避让状态栏：不加 statusBarsPadding 时按钮贴顶，中心会落进
        // 系统下拉手势区 —— 点了没反应（真机实测踩过：tap 被状态栏吃掉）。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 12.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✕",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (note != null) {
                Text(
                    text = note,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = "双击放大 · 捏合缩放 · 单指平移",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp,
            )
        }
    }
}
