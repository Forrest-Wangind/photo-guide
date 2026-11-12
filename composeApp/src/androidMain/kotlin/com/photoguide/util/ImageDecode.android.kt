package com.photoguide.util

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android 解码：BitmapFactory 全量解码后转 Compose 位图。
 *
 * CapturedPhoto 是 ~1600px 的 JPEG（§2.3 统一过），全量解码 ≈ 7MB ARGB，
 * 只保留最近一张，不做 inSampleSize 下采样 —— 预览要支持缩放看细节，
 * 采样省的那点内存换不来"放大就糊"。
 */
actual fun decodeImageBitmapOrNull(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return try {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp == null) {
            Log.d("PhotoGuide/ImageDecode", "照片解码返回 null（数据损坏或格式不支持）")
            null
        } else {
            bmp.asImageBitmap()
        }
    } catch (t: Throwable) {
        Log.d("PhotoGuide/ImageDecode", "照片解码失败：${t::class.simpleName} ${t.message.orEmpty()}")
        null
    }
}
