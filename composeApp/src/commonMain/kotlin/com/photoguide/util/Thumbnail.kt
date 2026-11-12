package com.photoguide.util

/**
 * JPEG 字节 → 小缩略图（JPEG 字节）。
 *
 * 用途只有一个：**历史记录里的那条缩略图**。
 *
 * 为什么必须另做一份小图，而不是直接存拍摄的原图：
 * 原图是 ~1600px / 700KB 的 JPEG，存 20 条就是 14MB，base64 之后还要再涨三分之一，
 * 塞进 SharedPreferences / NSUserDefaults 属于自找麻烦（每次写入都要整份重写）。
 * 压到长边 240px、质量 60 之后单张通常 8~20KB，20 条约 200~400KB，落在
 * 键值存储能安稳承受的量级里 —— 这正是"不需要数据库"这个前提的兑现方式。
 *
 * 为什么用 expect/actual 而不是在 commonMain 里直接做：
 * 缩放 + JPEG 编码在两端各有一套原生 API（Android Bitmap/compress、iOS skia），
 * commonMain 没有任何可用的编码接口（Compose 的 ImageBitmap 只能读不能写）。
 *
 * 失败返回 null：历史条目照常入库，列表里显示"无图"占位 ——
 * 缩略图是辅助信息，绝不能因为它挂掉而丢掉整条建议（但必须留日志）。
 */
expect fun makeThumbnailJpeg(bytes: ByteArray, maxDim: Int, quality: Int): ByteArray?

/** 历史缩略图的长边上限（px）。 */
const val THUMB_MAX_DIM = 240

/** 历史缩略图的 JPEG 质量。60 在 240px 上肉眼几乎看不出压缩痕迹。 */
const val THUMB_QUALITY = 60
