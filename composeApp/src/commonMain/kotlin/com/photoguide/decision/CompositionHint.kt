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

    /**
     * 拍一张照片时**实际要做的事**，按执行顺序排好的一条条建议。
     *
     * 为什么需要它：上面那四个数组是"模型给的分类"，不是"用户的操作序列"。
     * 分类并列意味着没有先后——可现实中这些事有严格顺序，顺序错了建议就是废的。
     *
     * 排序依据是**执行时序**，不是主观重要度：
     *   1. 先改机位 —— 手能立刻动的、且是后面一切的前提；
     *   2. 再开口（台词 / 画面要点）—— 台词虽然是本产品最差异化的东西，
     *      但机位错了还念台词等于白念，所以它不能排第一条；
     *   3. 最后是问题诊断 —— 它解释"为什么"，需要理解，放在动作之后。
     *
     * ⚠️ [warnings] **刻意不在这里**。它是可信度信息（"输出被截断""没收到画面"），
     * 不是动作。混进 3 条序列里用户一翻就翻过去了，而它恰恰最不该被翻过去。
     * 它由卡片自己做成常驻细条展示（见 HintCard）。
     *
     * 这是**派生**属性，不新增字段、不改提示词：CompositionHint 仍是唯一真相源，
     * 模型输出结构和存量数据都不受影响。
     */
    val steps: List<HintStep>
        get() = buildList {
            photographerActions.filter { it.isNotBlank() }
                .forEach { add(HintStep(HintKind.CAMERA, it)) }
            secondColumnLines.filter { it.isNotBlank() }.forEach {
                add(HintStep(if (hasPerson) HintKind.LINE else HintKind.SCENE, it))
            }
            issues.filter { it.isNotBlank() }
                .forEach { add(HintStep(HintKind.ISSUE, it)) }
        }
}

/**
 * 一条建议的类型。决定它在 [CompositionHint.steps] 里的位置，以及卡片上的标签与配色。
 */
enum class HintKind(val label: String) {
    /** 摄影师自己动手改的：机位、角度、距离、构图修正。 */
    CAMERA("机位"),

    /** 对模特说的话，可以直接照着念。 */
    LINE("对她说"),

    /** 无人像画面时值得强调的要点。 */
    SCENE("画面要点"),

    /** 当前画面存在的具体问题——解释"为什么要改"，不是动作本身。 */
    ISSUE("问题"),
}

data class HintStep(
    val kind: HintKind,
    val text: String,
)
