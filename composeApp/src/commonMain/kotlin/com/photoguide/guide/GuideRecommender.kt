package com.photoguide.guide

import com.photoguide.decision.CompositionHint
import com.photoguide.decision.ScenePreset
import com.photoguide.decision.ShootingIntent

/**
 * 这条推荐是**从哪来的** —— 决定 UI 用哪种措辞，也决定日志里怎么解释。
 *
 * 三档的可信度不同，必须让用户看得出来：
 *  - [MODEL]：模型看过这张照片后的判断，最贴合画面实际；
 *  - [HINT]：从本次建议的文字里读出来的（模型没直接给档位，但说了"更对称一点"）；
 *  - [INTENT]：只按拍摄意图推的，**没看过画面**，属于"先备着"。
 */
enum class GuideSource { MODEL, HINT, INTENT }

/**
 * 一条自动推荐。
 *
 * [reason] 必须能说清"为什么是这条线"，因为 UI 会原样展示给用户 ——
 * "智能推荐"四个字对不会构图的人没有任何价值，他需要知道这条线能解决什么问题。
 */
data class GuideRecommendation(
    val guide: CompositionGuide,
    val reason: String,
    val source: GuideSource = GuideSource.INTENT,
)

/**
 * 按拍摄意图 / 本次建议挑选参考线。
 *
 * **纯函数、无状态、无平台依赖**：输入意图（和可选的本轮建议），输出推荐或 null。
 * 放在 commonMain 是为了让这条规则能被双端共享 —— 也因为它本质上是一条
 * "摄影常识表"，改动应该只改这张表，不该散进 UI。
 *
 * 四条原则：
 *  1. **看过画面的判断优先于没看过画面的**：模型给了档位就用它的，
 *     其次是它文字里透出来的构图倾向，最后才按意图猜；
 *  2. **用户描述优先于预设**：预设是省输入成本的快捷项，描述才是他真正想要的；
 *  3. **没有把握就不推荐**（返回 null）：乱画一条线比不画更糟 ——
 *     用户会以为这是"AI 认为对的构图"，从而照着一条无关的线摆；
 *  4. **只为能靠画线解决的问题推荐**：例如"全身要拍高"靠的是降低机位，
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
            reason = "用黄金螺旋把视线收拢到主体上",
        ),
        TextRule(
            keywords = listOf("黄金"),
            guide = CompositionGuide.GOLDEN,
            reason = "主体落在 0.618 线上，比三分法更紧凑",
        ),
        TextRule(
            keywords = listOf("三分"),
            guide = CompositionGuide.THIRDS,
            reason = "主体压在三分线交点上",
        ),
        TextRule(
            keywords = listOf("对角", "斜线", "斜着", "动势", "延伸"),
            guide = CompositionGuide.DIAGONAL,
            reason = "沿对角线安排主体，画面更有张力",
        ),
        TextRule(
            keywords = listOf("对称", "居中", "正中", "倒影", "证件照"),
            guide = CompositionGuide.CENTER,
            reason = "用中心十字对齐，一偏就看得出来",
        ),
        TextRule(
            keywords = listOf("裁切", "封面", "头像", "二创", "别切到", "裁掉"),
            guide = CompositionGuide.SAFE_FRAME,
            reason = "先按安全框留出 8% 余量",
        ),
    )

    /**
     * 自由描述里的**主体**关键词 → 场景预设。
     *
     * 为什么需要这一层：用户完全可以不点场景芯片、只写一句"想拍傍晚海边的全身照"。
     * 只靠 [textRules]（构图专有名词）覆盖不到这种情况，推荐就会一直是空的 ——
     * 而"自由描述"恰恰是最常用的输入方式。
     *
     * ⚠️ 只在**没有预设**时才用（预设是用户明确点过的，比从文字里猜的可靠）。
     * ⚠️ 顺序即优先级：团体词在"人像"之前，否则"全家合影"会被"人像"截胡。
     */
    private val subjectRules: List<SubjectRule> = listOf(
        SubjectRule(listOf("合影", "全家", "全家福", "一群", "多人", "朋友们", "团队"), ScenePreset.GROUP),
        SubjectRule(listOf("双人", "两个", "情侣", "合照"), ScenePreset.COUPLE),
        SubjectRule(listOf("全身", "大长腿", "整身"), ScenePreset.FULL_BODY),
        SubjectRule(listOf("人像", "半身", "自拍", "肖像", "人物", "写真"), ScenePreset.PORTRAIT),
        SubjectRule(listOf("猫", "狗", "宠物", "兔", "仓鼠"), ScenePreset.PET),
        SubjectRule(listOf("美食", "菜", "饭", "咖啡", "蛋糕", "甜点", "食物", "茶", "面", "早餐"), ScenePreset.FOOD),
        SubjectRule(
            listOf("房间", "室内", "客厅", "卧室", "书房", "一角", "静物", "桌面", "窗边", "角落"),
            ScenePreset.INTERIOR,
        ),
        SubjectRule(
            listOf("风景", "日出", "日落", "海边", "晚霞", "山", "草原", "旅行", "星空", "湖边"),
            ScenePreset.SCENERY_WITH_SUBJECT,
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
     * 优先级（从"最贴合这张照片"到"最泛"）：
     *   1. 模型给的档位（它看过画面）；
     *   2. 本次建议文字里的构图倾向（"画面可以更对称一点" → 中心十字）；
     *   3. 用户描述里的构图关键词（"想要三分法"）；
     *   4. 场景预设；
     *   5. 用户描述里的主体关键词（"傍晚海边的全身照"）。
     *
     * [hint] 传 null 表示"还没拍"（取景阶段）—— 此时只能按意图猜。
     */
    fun recommend(intent: ShootingIntent, hint: CompositionHint? = null): GuideRecommendation? {
        if (hint != null) {
            // ① 模型直接给了档位。它看过照片，这是最可信的一档。
            //
            // ⚠️ 模型明确说 NONE 时**不**在这里返回 null：
            // NONE 的意思是"这张照片不需要额外加线"，不是"用户不该用工具"。
            // 直接吞掉会让"用户明明选了多人合影、安全框就在眼前能救命"变成没有线，
            // 所以 NONE 视为"本次没有画面驱动的建议"，继续往下按意图兜底。
            hint.suggestedGuideOrNull?.let { guide ->
                val reason = hint.guideReason.trim().ifBlank { guide.tip }
                return GuideRecommendation(guide, reason, GuideSource.MODEL)
            }
            // ② 模型没给档位，但它的建议里透着构图倾向
            fromText(hint.searchableText(), prefix = "本次建议里提到")?.let { return it.copy(source = GuideSource.HINT) }
        }

        // ③ 描述里直接点了构图法（"想要对称""沿对角线"）—— 意图里最硬的一条
        fromText(intent.freeText, prefix = "描述里提到")?.let { return it }

        // ④ 用户明确点过的场景预设
        intent.preset?.let { preset -> presetRules[preset]?.let { return it } }

        // ⑤ 只有自由描述：从主体关键词反推场景
        fromSubject(intent.freeText)?.let { return it }

        return null
    }

    private fun fromText(text: String, prefix: String): GuideRecommendation? {
        if (text.isBlank()) return null
        for (rule in textRules) {
            val hit = rule.keywords.firstOrNull { text.contains(it) } ?: continue
            return GuideRecommendation(rule.guide, "$prefix「$hit」—— ${rule.reason}")
        }
        return null
    }

    private fun fromSubject(text: String): GuideRecommendation? {
        if (text.isBlank()) return null
        for (rule in subjectRules) {
            if (rule.keywords.none { text.contains(it) }) continue
            val base = presetRules[rule.preset] ?: continue
            return base.copy(reason = "描述里的主体是「${rule.preset.displayName}」—— ${base.reason}")
        }
        return null
    }

    /**
     * 本次建议里可以被扫的文本。
     *
     * 刻意**不含 warnings**：那是模型自己都不确定的东西，拿它推参考线等于
     * 把"可能看错了"的判断画到用户眼前。
     */
    private fun CompositionHint.searchableText(): String = buildString {
        appendLine(headline)
        photographerActions.forEach { appendLine(it) }
        modelLines.forEach { appendLine(it) }
        sceneNotes.forEach { appendLine(it) }
        issues.forEach { appendLine(it) }
    }

    private data class TextRule(
        val keywords: List<String>,
        val guide: CompositionGuide,
        val reason: String,
    )

    private data class SubjectRule(
        val keywords: List<String>,
        val preset: ScenePreset,
    )
}
