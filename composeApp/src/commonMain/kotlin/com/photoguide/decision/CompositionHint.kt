package com.photoguide.decision

import kotlinx.serialization.Serializable

/**
 * 给用户的构图建议。
 *
 * 结构直接对应本产品的差异化点：**双栏提示卡**
 *   左栏 [photographerActions] —— 摄影师（拿手机的人）该做的动作
 *   右栏 [modelLines]          —— 模特该做什么，可以直接照着念
 *
 * 市面上所有构图辅助工具都只解决"构图对不对"，
 * 不解决"你该对她说点什么"。这个双栏就是我们要解决的。
 */
@Serializable
data class CompositionHint(
    /** 一句话结论，卡片标题。 */
    val headline: String = "",

    /** 0~100 的构图评分。模型不给就为 null，不做假数据。 */
    val score: Int? = null,

    val photographerActions: List<String> = emptyList(),
    val modelLines: List<String> = emptyList(),

    /** 当前画面具体的问题（给摄影师看的诊断）。 */
    val issues: List<String> = emptyList(),

    /** 模型不确定的地方。设计原则 3：必须显式标注，不许掩饰。 */
    val warnings: List<String> = emptyList(),

    val confidence: Double? = null,
) {
    val hasContent: Boolean
        get() = headline.isNotBlank() ||
            photographerActions.isNotEmpty() ||
            modelLines.isNotEmpty()
}
