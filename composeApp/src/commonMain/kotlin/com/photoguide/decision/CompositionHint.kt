package com.photoguide.decision

import com.photoguide.guide.CompositionGuide
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

    /**
     * 模型建议开启的参考线（`CompositionGuide` 的 name 或中文名）。
     *
     * 为什么让模型说：它**看过这张照片**，知道主体偏在哪、边缘有没有人被切。
     * 本地规则只能按"用户说他要拍什么"推，拍完拿到的是"画面实际出了什么问题"，
     * 后者才是选参考线的真正依据。
     *
     * ⚠️ 这里存**原始字符串**而不是枚举：反序列化时不认识的值要能被上层看见并回退，
     * 直接映射成枚举会在解析失败时静默变成"关闭"。解析统一走 [suggestedGuideOrNull]。
     */
    val suggestedGuide: String? = null,

    /** 为什么是这条线（一句，UI 原样展示）。空 = 模型没给理由。 */
    val guideReason: String = "",

    val confidence: Double? = null,
) {
    /** 画面里有人物 → 右栏用"对她说"；否则用"画面要点"。 */
    val hasPerson: Boolean get() = modelLines.isNotEmpty()

    /** 右栏实际要展示的内容。 */
    val secondColumnLines: List<String>
        get() = if (hasPerson) modelLines else sceneNotes

    /**
     * 模型建议的参考线；没建议 / 建议"关闭" / 值认不出 → null。
     *
     * 三者在**行为上等价**（都不画线），不做区分：多一个"模型说关闭了"的状态
     * 只会让 UI 多一条分支，却不改变任何结果。
     */
    val suggestedGuideOrNull: CompositionGuide?
        get() = CompositionGuide.fromWire(suggestedGuide)?.takeIf { it != CompositionGuide.NONE }

    val hasContent: Boolean
        get() = headline.isNotBlank() ||
            photographerActions.isNotEmpty() ||
            modelLines.isNotEmpty() ||
            sceneNotes.isNotEmpty()
}
