package com.photoguide.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * JPEG 字节 → Compose 位图。
 *
 * 为什么 expect/actual 而不是 commonMain 直接用 skia：
 * CMP 的 skia（skiko）只在非 Android 目标可用，Android 目标的位图走
 * `BitmapFactory` → `asImageBitmap`。iOS 侧用 skia 解码，一个 API 两端通用。
 *
 * 输入就是 CapturedPhoto 的 ~1600px JPEG（§2.3：与发给模型的是同一份），
 * 全量解码约 7MB 位图，只保留最近一张，内存预算可接受。
 *
 * 失败（坏数据 / 不支持的格式）返回 null：建议卡降级成"无图"，建议文字照常显示 ——
 * 图片是辅助，绝不能因为它挂掉而吞掉主功能。但一定留日志（绝不静默失败）。
 */
expect fun decodeImageBitmapOrNull(bytes: ByteArray): ImageBitmap?
