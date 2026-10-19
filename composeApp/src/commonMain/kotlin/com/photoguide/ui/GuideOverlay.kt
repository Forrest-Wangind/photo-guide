package com.photoguide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.photoguide.guide.CompositionGuide
import kotlin.math.PI
import kotlin.math.atan2

/**
 * 参考线叠加层。
 *
 * 纯 Canvas、不消费触摸事件、不持有任何逐帧状态 —— 只在选中项变化时重绘一次，
 * 对取景流畅性（P99 帧时间）没有影响。
 *
 * 坐标一律按**当前可见取景区域**归一化：相机预览是 centerCrop 铺满的，
 * 屏幕上看到的范围就是用户以为的"这一张"，所以三分线取屏幕的 1/3 才是对的。
 * 将来接感知层时注意：模型给的是**帧**坐标，帧与可见区域不等价，届时要做映射，
 * 不能直接复用这里的比例（这是已知债，不是疏忽）。
 */
@Composable
fun GuideOverlay(guide: CompositionGuide, modifier: Modifier = Modifier) {
    if (guide == CompositionGuide.NONE) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val hairline = 1.dp.toPx()
        val line = Color.White.copy(alpha = 0.45f)
        val bold = Color.White.copy(alpha = 0.68f)
        val accent = Color(0xFFFFC24B)
        val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()), 0f)

        when (guide) {
            CompositionGuide.NONE -> Unit

            CompositionGuide.THIRDS -> {
                val marks = floatArrayOf(1f / 3f, 2f / 3f)
                for (f in marks) {
                    drawLine(line, Offset(w * f, 0f), Offset(w * f, h), hairline)
                    drawLine(line, Offset(0f, h * f), Offset(w, h * f), hairline)
                }
                // 交点强调：主体该落在哪，比线条本身更需要被看见
                for (fx in marks) for (fy in marks) {
                    drawCircle(
                        color = accent.copy(alpha = 0.85f),
                        radius = 3.5.dp.toPx(),
                        center = Offset(w * fx, h * fy),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }

            CompositionGuide.GOLDEN -> {
                val marks = floatArrayOf(0.382f, 0.618f)
                for (f in marks) {
                    drawLine(line, Offset(w * f, 0f), Offset(w * f, h), hairline, pathEffect = dash)
                    drawLine(line, Offset(0f, h * f), Offset(w, h * f), hairline, pathEffect = dash)
                }
                for (fx in marks) for (fy in marks) {
                    drawCircle(
                        color = accent.copy(alpha = 0.8f),
                        radius = 3.dp.toPx(),
                        center = Offset(w * fx, h * fy),
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }
            }

            CompositionGuide.CENTER -> {
                val cx = w / 2f
                val cy = h / 2f
                val gap = 28.dp.toPx()
                drawLine(bold, Offset(cx, 0f), Offset(cx, cy - gap), hairline)
                drawLine(bold, Offset(cx, cy + gap), Offset(cx, h), hairline)
                drawLine(bold, Offset(0f, cy), Offset(cx - gap, cy), hairline)
                drawLine(bold, Offset(cx + gap, cy), Offset(w, cy), hairline)
                drawCircle(
                    color = accent.copy(alpha = 0.85f),
                    radius = 6.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                // 四边中点刻度：判断水平/居中比目测准
                val tick = 10.dp.toPx()
                drawLine(bold, Offset(cx, 0f), Offset(cx, tick), 2.dp.toPx(), StrokeCap.Round)
                drawLine(bold, Offset(cx, h), Offset(cx, h - tick), 2.dp.toPx(), StrokeCap.Round)
                drawLine(bold, Offset(0f, cy), Offset(tick, cy), 2.dp.toPx(), StrokeCap.Round)
                drawLine(bold, Offset(w, cy), Offset(w - tick, cy), 2.dp.toPx(), StrokeCap.Round)
            }

            CompositionGuide.DIAGONAL -> {
                drawLine(line, Offset(0f, 0f), Offset(w, h), hairline, pathEffect = dash)
                drawLine(line, Offset(w, 0f), Offset(0f, h), hairline, pathEffect = dash)
                // 中心留一个小小的十字，方便判断主体是否真的压在中点
                drawLine(
                    bold,
                    Offset(w / 2f - 6.dp.toPx(), h / 2f),
                    Offset(w / 2f + 6.dp.toPx(), h / 2f),
                    hairline,
                )
                drawLine(
                    bold,
                    Offset(w / 2f, h / 2f - 6.dp.toPx()),
                    Offset(w / 2f, h / 2f + 6.dp.toPx()),
                    hairline,
                )
            }

            CompositionGuide.SPIRAL -> drawGoldenSpiral(w, h, line, accent, hairline, dash)

            CompositionGuide.SAFE_FRAME -> {
                val insetX = w * 0.08f
                val insetY = h * 0.08f
                drawRect(
                    color = line,
                    topLeft = Offset(insetX, insetY),
                    size = Size(w - insetX * 2f, h - insetY * 2f),
                    style = Stroke(width = hairline, pathEffect = dash),
                )
                // 四角加粗，切图时更容易对齐
                val len = 18.dp.toPx()
                val corners = listOf(
                    Offset(insetX, insetY) to Offset(1f, 1f),
                    Offset(w - insetX, insetY) to Offset(-1f, 1f),
                    Offset(insetX, h - insetY) to Offset(1f, -1f),
                    Offset(w - insetX, h - insetY) to Offset(-1f, -1f),
                )
                for ((p, dir) in corners) {
                    drawLine(bold, p, Offset(p.x + len * dir.x, p.y), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(bold, p, Offset(p.x, p.y + len * dir.y), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

/**
 * 黄金螺旋。
 *
 * 经典做法：在取景框内**居中内接一个 1:φ 的黄金矩形**，然后按斐波那契分块
 * （每步切掉一块短边正方形）在每个正方形里画四分之一圆弧。
 *
 * 两个实测教训（不要退回去）：
 * - 不能拿全屏比例直接分块：切正方形的构造只对黄金比例成立，
 *   手机屏 9:19.5 偏长太多，第二块之后圆弧就接不上了；
 * - 圆心不能按"每个正方形随便选一个角"来定：必须用递推
 *   `C' = 衔接点 + (C − 衔接点)·(s'/s)`（C' 与 C 在衔接点同侧），
 *   否则相邻圆弧在交界处切线反向，画出来是一朵"花瓣"而不是螺旋（模拟器实测）。
 *
 * 螺旋的"眼睛"（收敛点）= 分块极限点，用最后一小段圆心近似，主体就放它附近。
 */
private fun DrawScope.drawGoldenSpiral(
    w: Float,
    h: Float,
    color: Color,
    accent: Color,
    hairline: Float,
    dash: PathEffect,
) {
    val phi = PHI.toFloat()
    val grw: Float
    val grh: Float
    if (w / h > phi) { grh = h; grw = h * phi } else { grw = w; grh = w * phi }
    val ox = (w - grw) / 2f
    val oy = (h - grh) / 2f

    // 黄金矩形本体用虚线画出：把主体压在矩形边上同样是黄金构图
    drawRect(
        color = color,
        topLeft = Offset(ox, oy),
        size = Size(grw, grh),
        style = Stroke(width = hairline, pathEffect = dash),
    )

    // 第一段弧：最大正方形 = 黄金矩形顶部整条（竖屏时），圆心在其左下角，
    // 从矩形左上角顺时针扫 90° 到正方形右下角 —— 这个"衔接点"是下一段的起点
    var s = grw
    var cx = ox
    var cy = oy + s
    var sx = ox
    var sy = oy
    var eyeX = cx
    var eyeY = cy
    var started = false

    val path = Path()
    repeat(9) {
        if (s < 2f) return@repeat

        // 圆弧：圆心 (cx,cy)、半径 s、从 (sx,sy) 顺时针扫 90°
        val start = degreesOf(sy - cy, sx - cx)
        path.arcTo(
            rect = Rect(center = Offset(cx, cy), radius = s),
            startAngleDegrees = start,
            sweepAngleDegrees = 90f,
            forceMoveTo = !started,
        )
        started = true

        // 本段弧的终点 = 下一段弧的起点（衔接点）：
        // 起点相对圆心的向量 (dx,dy) 顺时针转 90°（屏幕坐标 y 向下）→ (−dy, dx)
        val dx = sx - cx
        val dy = sy - cy
        val jx = cx - dy
        val jy = cy + dx

        // 下一段：边长 s/φ，圆心在"衔接点 → 旧圆心"方向上按比例收缩
        // （新旧圆心与衔接点共线且同侧，切线才连续）
        val ratio = 1f / phi
        val ns = s * ratio
        val ncx = jx + (cx - jx) * ratio
        val ncy = jy + (cy - jy) * ratio

        eyeX = ncx
        eyeY = ncy
        sx = jx
        sy = jy
        cx = ncx
        cy = ncy
        s = ns
    }

    drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    drawCircle(
        color = accent.copy(alpha = 0.85f),
        radius = 5.dp.toPx(),
        center = Offset(eyeX, eyeY),
        style = Stroke(width = 1.5.dp.toPx()),
    )
}

private fun degreesOf(dy: Float, dx: Float): Float = atan2(dy, dx) * 180f / PI.toFloat()

private const val PHI = 1.6180339887498949
