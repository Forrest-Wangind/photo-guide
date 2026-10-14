package com.photoguide.perception

import com.photoguide.capture.CapturedPhoto
import kotlinx.serialization.Serializable

/**
 * 本地感知层抽象。
 *
 * 这是 §2.5「演进架构」的接口钉子：
 *  - Phase 1：实现是 [NoPerception]（空实现），照片直传云端多模态模型；
 *  - Phase 2：实现换成 YOLO26n / MoveNet 等，把结果作为 JSON 附加上下文一起给大模型。
 *
 * 上层（UI / HintEngine / 提示词）只依赖这个接口，换实现时一行不改。
 */
interface PerceptionSource {
    val id: String
    val displayName: String

    /** 是否会读取像素。false 时上层不必为它做任何图像预处理。 */
    val consumesPixels: Boolean

    suspend fun analyze(photo: CapturedPhoto): PerceptionSnapshot
}

/**
 * 感知结果。字段刻意对齐「喂给大模型的三件套」中的 JSON 那一件（§2.4.3）：
 * 图给审美、裁剪给细节、这份 JSON 给定量锚点。
 */
@Serializable
data class PerceptionSnapshot(
    val source: String,
    val boxes: List<DetectedBox> = emptyList(),
    val keypoints: List<Keypoint> = emptyList(),
    val persons: Int = 0,
    /** 人的脚底相对画面高度（0=顶边, 1=底边），用于廉价推断前后层次。 */
    val subjectFootlineY: Float? = null,
    /** 必须显式标注不可靠项——不允许默不作声地给一个错值。 */
    val notes: List<String> = emptyList(),
    val latencyMs: Long = 0,
) {
    val isEmpty: Boolean get() = boxes.isEmpty() && keypoints.isEmpty()
}

@Serializable
data class DetectedBox(
    val label: String,
    /** 归一化 [0,1]，左上角为原点。 */
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val confidence: Float,
    /** 主色，形如 "#B3243A"。Phase 1 为 null。 */
    val dominantColor: String? = null,
)

@Serializable
data class Keypoint(
    val name: String,
    val x: Float,
    val y: Float,
    val confidence: Float,
)
