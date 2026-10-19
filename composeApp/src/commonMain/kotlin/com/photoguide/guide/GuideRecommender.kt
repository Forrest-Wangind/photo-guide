package com.photoguide.guide

import com.photoguide.decision.ScenePreset
import com.photoguide.decision.ShootingIntent

/**
 * 一条自动推荐。
 *
 * [reason] 必须能说清"为什么是这条线"，因为 UI 会原样展示给用户 ——
 * "智能推荐"四个字对不会构图的人没有任何价值，他需要知道这条线能解决什么问题。
 *
 * [fromText] 只用于日志与文案区分：用户明确写下的构图词（"对称""斜着摆"）
 * 比预设更具体，优先级也更高。
 */
data class GuideRecommendation(
    val guide: CompositionGuide,
    val reason: String,
    val fromText: Boolean = false,
)

/**
 * 按拍摄意图挑选参考线。
 *
 * **纯函数、无状态、无平台依赖**：输入意图，输出推荐或 null。
 * 放在 commonMain 是为了让这条规则能被双端共享 —— 也因为它本质上是一条
 * "摄影常识表"，改动应该只改这张表，不该散进 UI。
 *
 * 三条原则：
 *  1. **用户描述优先于预设**：预设是省输入成本的快捷项，描述才是他真正想要的。
 *     写"拍个对称的合影"就不要再按"双人合影"硬推中心线之外的东西。
 *  2. **没有把握就不推荐**（返回 null）：乱画一条线比不画更糟 ——
 *     用户会以为这是"AI 认为对的构图"，从而照着一条无关的线摆。
 *  3. **只为能靠画线解决的问题推荐**：例如"全身要拍高"靠的是降低机位，
 *     不是靠某条参考线；这类场景推荐重点仍放在"人该落在哪"，不编造工具能力。
 */
object GuideRecommender {

    /**
     * 文字里的构图关键词。
     *
     * ⚠️ 顺序即优先级：越"专有"的词越靠前（"黄金螺旋"里同时含"黄金"，
     * 所以螺旋必须排在黄金之前，否则会被更宽泛的规则截胡）。
     *
     * ⚠️ 刻意不收的模糊词："留白""氛围""高级感"——这些和具体某条线没有稳定对应，
     * 收了只会让推荐看起来随机。
     */
    private val textRules: List<TextRule> = listOf(
        TextRule(
            keywords = listOf("螺旋"),
            guide = CompositionGuide.SPIRAL,
            reason = "描述里提到螺旋 —— 用黄金螺旋把视线收拢到主体上",
        ),
        TextRule(
            keywords = listOf("黄金"),
            guide = CompositionGuide.GOLDEN,
            reason = "描述里提到黄金分割 —— 主体落在 0.618 线上，比三分法更紧凑",
        ),
        TextRule(
            keywords = listOf("三分"),
            guide = CompositionGuide.THIRDS,
            reason = "描述里提到三分法 —— 主体压在三分线交点上",
        ),
        TextRule(
            keywords = listOf("对角", "斜线", "斜着", "动势", "延伸"),
            guide = CompositionGuide.DIAGONAL,
            reason = "描述里有斜向/动势 —— 沿对角线安排主体，画面更有张力",
        ),
        TextRule(
            keywords = listOf("对称", "居中", "正中", "倒影", "证件照"),
            guide = CompositionGuide.CENTER,
            reason = "描述要求对称/居中 —— 用中心十字对齐，一偏就看得出来",
        ),
        TextRule(
            keywords = listOf("裁切", "封面", "头像", "二创"),
            guide = CompositionGuide.SAFE_FRAME,
            reason = "成品还要二次裁切 —— 先按安全框留出 8% 余量",
        ),
    )

    /**
     * 场景预设 → 参考线。
     *
     * 每一条都能说出摄影上的理由，不是随便配的：
     *  - 半身/全身/宠物：主体是"点"，三分线交点是安置眼睛最稳的位置；
     *  - 双人：两人的对称关系就是构图本身，中心线能把关系拍出来；
     *  - 多人：真正的失败模式是边缘的人被裁掉 → 安全框比任何构图线都实用；
     *  - 美食：盘沿、餐具、桌沿天然是斜向的，沿对角线摆比正对拍更有食欲；
     *  - 静物/空间：主体偏一点才有留白和层次 → 0.618；
     *  - 风景人像：人小景大，黄金螺旋是唯一能描述"视线收拢到小人物"的线。
     */
    private val presetRules: Map<ScenePreset, GuideRecommendation> = mapOf(
        ScenePreset.PORTRAIT to GuideRecommendation(
            CompositionGuide.THIRDS,
            "半身人像：眼睛落在上三分线交点，朝向她看的那一侧留出空间",
        ),
        ScenePreset.FULL_BODY to GuideRecommendation(
            CompositionGuide.THIRDS,
            "全身：脚踩下三分线、头顶留白，人站在竖向三分线上就不会被裁",
        ),
        ScenePreset.COUPLE to GuideRecommendation(
            CompositionGuide.CENTER,
            "双人合影：两人对称分居中心线两侧，关系一眼看得出来",
        ),
        ScenePreset.GROUP to GuideRecommendation(
            CompositionGuide.SAFE_FRAME,
            "多人合影：最怕边缘的人被裁掉，先按安全框四周留 8%",
        ),
        ScenePreset.PET to GuideRecommendation(
            CompositionGuide.THIRDS,
            "宠物：眼睛落在交点，朝向的那一侧留出空间，别让它贴边",
        ),
        ScenePreset.FOOD to GuideRecommendation(
            CompositionGuide.DIAGONAL,
            "美食：盘沿和餐具沿对角线摆，比正面平铺更有食欲",
        ),
        ScenePreset.INTERIOR to GuideRecommendation(
            CompositionGuide.GOLDEN,
            "静物/空间：主体放 0.618，留白和层次才出得来",
        ),
        ScenePreset.SCENERY_WITH_SUBJECT to GuideRecommendation(
            CompositionGuide.SPIRAL,
            "风景人像：人小景大，把人物放在螺旋收紧的\"眼睛\"附近",
        ),
    )

    /**
     * 推荐一条参考线；没有把握时返回 null。
     *
     * 优先级：描述里的构图关键词 → 场景预设 → null。
     */
    fun recommend(intent: ShootingIntent): GuideRecommendation? {
        fromText(intent.freeText)?.let { return it }
        val preset = intent.preset ?: return null
        return presetRules[preset]
    }

    private fun fromText(text: String): GuideRecommendation? {
        if (text.isBlank()) return null
        val rule = textRules.firstOrNull { r -> r.keywords.any { text.contains(it) } } ?: return null
        return GuideRecommendation(rule.guide, rule.reason, fromText = true)
    }

    private data class TextRule(
        val keywords: List<String>,
        val guide: CompositionGuide,
        val reason: String,
    )
}
