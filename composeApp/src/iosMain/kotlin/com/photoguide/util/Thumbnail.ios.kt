package com.photoguide.util

import com.photoguide.platform.platformLog
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * iOS 缩略图：skia 解码 → 光栅 Surface 上重绘到目标尺寸 → 编码成 JPEG。
 *
 * 为什么不用 `Image.makeFromEncoded(...).encodeToData(...)` 直接重压：
 * 那样尺寸一点没变（原图 1600px 还是 1600px），省不下 prefs 的体积，
 * 这个函数存在的意义就没了。skia 的缩放走"画到一个更小的画布上"，
 * drawImageRect 顺带完成重采样，一行搞定且不依赖 Bitmap 的中间态。
 */
actual fun makeThumbnailJpeg(bytes: ByteArray, maxDim: Int, quality: Int): ByteArray? {
    if (bytes.isEmpty()) return null
    return try {
        val img = Image.makeFromEncoded(bytes)
        val scale = min(
            maxDim.toFloat() / img.width,
            maxDim.toFloat() / img.height,
        ).coerceAtMost(1f)
        val w = (img.width * scale).roundToInt().coerceAtLeast(1)
        val h = (img.height * scale).roundToInt().coerceAtLeast(1)

        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(
            img,
            Rect.makeWH(img.width.toFloat(), img.height.toFloat()),
            Rect.makeWH(w.toFloat(), h.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
        surface.makeImageSnapshot()
            .encodeToData(EncodedImageFormat.JPEG, quality)
            ?.bytes
            ?: run {
                platformLog("Thumbnail", "JPEG 编码返回空，本条历史将无图")
                null
            }
    } catch (t: Throwable) {
        platformLog("Thumbnail", "缩略图生成失败：${t::class.simpleName} ${t.message.orEmpty()}")
        null
    }
}
