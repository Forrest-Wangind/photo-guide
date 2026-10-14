package com.photoguide.capture

/**
 * 一张已拍摄的照片。
 *
 * Phase 1 是单图形态：拍完就把 JPEG 字节直接交给云端多模态大模型。
 *
 * 这里刻意保留 width / height / rotationDegrees —— Phase 2 接入本地感知层后，
 * 模型输出的归一化坐标要按它们换算回像素、再映射到预览控件尺寸。
 * 接口现在留好，Phase 2 就不必动 commonMain。
 */
class CapturedPhoto(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
) {
    /** 直接可塞进 OpenAI 兼容 messages 的 data URL。 */
    fun toDataUrl(): String = "data:$mimeType;base64," + com.photoguide.util.Base64.encode(bytes)

    val sizeKb: Int get() = bytes.size / 1024

    override fun toString(): String =
        "CapturedPhoto(${width}x$height, rot=$rotationDegrees, ${sizeKb}KB, $mimeType)"
}
