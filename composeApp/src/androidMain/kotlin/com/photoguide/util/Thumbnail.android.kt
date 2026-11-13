package com.photoguide.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android 缩略图：先按 inSampleSize 粗降采样（省内存），再精确缩到 maxDim 内，最后压 JPEG。
 *
 * 为什么两步：直接全量解码 1600px 再缩，中间要占 ~7MB ARGB；
 * inSampleSize 只能 2 的幂，单靠它又会缩小过头（1600 → 400 仍大于 240，得再来一次
 * createScaledBitmap 精确到位）。
 *
 * 全程自己回收临时 Bitmap：照片可能连着拍，20 张的中间态攒起来是会触发 GC 抖动的。
 */
actual fun makeThumbnailJpeg(bytes: ByteArray, maxDim: Int, quality: Int): ByteArray? {
    if (bytes.isEmpty()) return null
    return try {
        // 第一遍只读尺寸，不解码像素
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.d("PhotoGuide/Thumbnail", "读不到照片尺寸（数据损坏），本条历史将无图")
            return null
        }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }

        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
        if (decoded == null) {
            Log.d("PhotoGuide/Thumbnail", "降采样解码返回 null，本条历史将无图")
            return null
        }

        val scale = min(
            maxDim.toFloat() / decoded.width,
            maxDim.toFloat() / decoded.height,
        ).coerceAtMost(1f)
        val target = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        val out = ByteArrayOutputStream()
        val ok = target.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (target !== decoded) target.recycle()
        decoded.recycle()
        if (!ok) {
            Log.d("PhotoGuide/Thumbnail", "JPEG 压缩失败，本条历史将无图")
            return null
        }
        out.toByteArray()
    } catch (t: Throwable) {
        Log.d("PhotoGuide/Thumbnail", "缩略图生成失败：${t::class.simpleName} ${t.message.orEmpty()}")
        null
    }
}
