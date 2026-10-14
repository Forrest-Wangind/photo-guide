package com.photoguide.decision

import kotlinx.serialization.Serializable

/**
 * 给用户的构图建议。
 *
 * 结构直接对应本产品的差异化点：**双栏提示卡**
 *   左栏 [photographerActions] —— 摄影师（拿手机的人）该做的动作
 *   右栏 —— 按画面里有没有人物二选一（见下）
 *
 * 市面上所有构图辅助工具都只解决"构图对不对"，
 * 不解决"你该对她说点什么"。这个双栏就是我们要解决的。
 */
/**
 * 给用户的构图建议。
 *
 * 结构直接对应本产品的差异化点：**双栏提示卡**
 *   左栏 [photographerActions] —— 摄影师（拿手机的人）该做的动作
 *   右栏 —— **按画面里有没有人物二选一**：
 *     · 有人物 → [modelLines]：模特该做什么，可以直接照着念
 *     · 无人像 → [sceneNotes]：这个画面值得强调的要点（光线/层次/留白/杂物）
 *
 * ⚠️ 右栏不能写死成"对她说"：本产品的主场景确实是人像，但用户完全可以拍
 * 室内一角、食物、街景、宠物，这些画面里没有人，硬凑台词就是胡说。
 * 二选一由**内容**判定（哪个数组非空），不额外引入标志位——
 * 标志位和内容冲突时反而会静默丢掉模型已经生成的东西。
 */
@Serializable
data class CompositionHint(
    /** 一句话结论，卡片标题。 */
    val headline: String = "",

    /** 0~100 的构图评分。模型不给就为 null，不做假数据。 */
    val score: Int? = null,

    val photographerActions: List<String> = emptyList(),

    /** 有人物时：可以直接对模特念的口语指令。无人像时必须是空数组。 */
    val modelLines: List<String> = emptyList(),

    /** 无人像（静物/空间/食物/宠物/风景）时：这画面值得强调的要点。有人物时必须是空数组。 */
    val sceneNotes: List<String> = emptyList(),

    /** 当前画面具体的问题（给摄影师看的诊断）。 */
    val issues: List<String> = emptyList(),

    /** 模型不确定的地方。设计原则 3：必须显式标注，不许掩饰。 */
    val warnings: List<String> = emptyList(),

    val confidence: Double? = null,
) {
    /** 画面里有人物 → 右栏用"对她说"；否则用"画面要点"。 */
    val hasPerson: Boolean get() = modelLines.isNotEmpty()

    /** 右栏实际要展示的内容。 */
    val secondColumnLines: List<String>
        get() = if (hasPerson) modelLines else sceneNotes

    val hasContent: Boolean
        get() = headline.isNotBlank() ||
            photographerActions.isNotEmpty() ||
            modelLines.isNotEmpty() ||
            sceneNotes.isNotEmpty()
}
