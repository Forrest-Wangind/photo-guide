package com.photoguide.perception

import com.photoguide.capture.CapturedPhoto

/**
 * Phase 1 的空实现。
 *
 * 它存在的意义不是"占位"，而是把接口先钉死在 commonMain。
 * Phase 2 只需把这里换成 YoloPerception，上层 UI / 决策层 / 提示词一行不改。
 */
class NoPerception : PerceptionSource {
    override val id: String = ID
    override val displayName: String = "无本地感知（Phase 1）"
    override val consumesPixels: Boolean = false

    override suspend fun analyze(photo: CapturedPhoto): PerceptionSnapshot = PerceptionSnapshot(
        source = id,
        notes = listOf(
            "Phase 1 未接入本地感知层：构图判断完全交给云端多模态大模型",
            "因此模型量不出像素级位置（如\"往左 5%\"），只会给定性建议",
        ),
    )

    companion object {
        const val ID = "none"
    }
}
