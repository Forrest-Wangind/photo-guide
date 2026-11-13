package com.photoguide.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.photoguide.platform.platformLog
import org.jetbrains.skia.Image

/**
 * iOS 解码：skia 直接解 JPEG 字节（CMP 在 iOS 目标自带 skiko）。
 */
actual fun decodeImageBitmapOrNull(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (t: Throwable) {
        platformLog("ImageDecode", "照片解码失败：${t::class.simpleName} ${t.message.orEmpty()}")
        null
    }
}
