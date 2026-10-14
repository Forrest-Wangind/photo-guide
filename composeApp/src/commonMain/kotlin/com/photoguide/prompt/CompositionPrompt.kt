package com.photoguide.prompt

import com.photoguide.decision.ShootingIntent
import com.photoguide.llm.LlmConfig
import com.photoguide.perception.PerceptionSnapshot
import kotlinx.serialization.json.Json

/**
 * 提示词构建。
 *
 * 两条设计约束：
 *  1. 输出必须是**严格 JSON** —— 上层要结构化成双栏卡片，不能靠正则去猜自然语言；
 *  2. 必须区分"模型看到了什么"与"模型没看到什么" —— 没传图时如果不告诉它，
 *     它会凭常识编一套构图建议出来，这对用户是纯粹的伤害。
 */
object CompositionPrompt {

    private val json = Json { prettyPrint = false; explicitNulls = false }

    // 注意：不能用 const val —— .trimIndent() 不是编译期常量
    private val SYSTEM_PROMPT_CORE = """
你是一位有 20 年经验的商业人像摄影师，现在在做实时拍摄指导。用户正拿着手机，
要给一个重要的人拍照。他要的不是摄影课，是"现在马上该怎么做"。

你的输出必须同时回答两件事：
1. photographerActions —— 摄影师（拿手机的人）该做什么：机位、角度、距离、构图修正、何时按快门；
2. modelLines —— 模特该做什么，写成一句可以直接照着念的口语。

硬性要求：
- 每条 photographerActions 必须具体、可执行、当场能做完。
  反例（禁止）："注意构图"、"找个好角度"、"调整一下距离"。
  正例："往左挪半步，让树别贴在她头顶"、"把手机降到胸口高度，从下往上一点拍"。
- 每条 modelLines 必须是口语，带明确方向或幅度。
  正例："头往右转一点点，看远处，别看我"、"下巴往前伸一点点，再收回来"。
  禁止书面语、禁止没有方向的"放松一点"。
- 如果画面已经足够好，就直接说"这样就行，按快门"，不要硬凑问题。
- 你不确定的地方，写进 warnings，禁止编造。宁可少说，不要胡说。
- 只输出 JSON，不要 markdown 代码块，不要任何 JSON 之外的文字。
""".trimIndent()

    private val OUTPUT_SCHEMA = """
{
  "headline": "一句话结论，不超过 20 字",
  "score": 0-100 的整数,
  "photographerActions": ["摄影师的具体动作，2-4 条"],
  "modelLines": ["对模特说的口语指令，1-3 条"],
  "issues": ["当前画面具体存在的问题，0-3 条"],
  "warnings": ["你不确定的地方，0-3 条。没有就给空数组"],
  "confidence": 0.0-1.0
}
""".trimIndent()

    fun buildSystemPrompt(config: LlmConfig): String {
        val extra = config.extraInstruction.trim()
        return if (extra.isEmpty()) {
            SYSTEM_PROMPT_CORE
        } else {
            SYSTEM_PROMPT_CORE + "\n\n【用户的额外要求】\n" + extra
        }
    }

    fun buildUserPrompt(
        intent: ShootingIntent,
        perception: PerceptionSnapshot,
        config: LlmConfig,
        imageAttached: Boolean,
    ): String = buildString {
        appendLine("【用户的拍摄意图】")
        appendLine("- 场景：${intent.preset.displayName}。${intent.preset.guidance}")
        if (intent.freeText.isNotBlank()) {
            appendLine("- 用户补充说明：${intent.freeText.trim()}")
        }
        appendLine()

        appendLine("【本地感知数据】")
        if (perception.isEmpty) {
            appendLine("本次没有本地感知数据（Phase 1 未接入本地检测层）。")
            appendLine("这意味着：你无法获得任何像素级坐标。")
            appendLine("请只给定性的构图判断，绝对不要编造具体百分比或厘米数。")
        } else {
            appendLine(json.encodeToString(PerceptionSnapshot.serializer(), perception))
            appendLine("坐标为归一化值（0~1，左上角为原点）。")
        }
        appendLine()

        appendLine("【你可以看到什么】")
        if (imageAttached) {
            appendLine("你可以看到用户刚拍的这张照片本身。请基于画面内容判断。")
        } else {
            appendLine("你没有收到照片，你看不到画面。")
            appendLine("因此：不要编造画面里有什么人、什么背景。")
            appendLine("你只能给通用的场景化构图建议，并且必须在 warnings 里说明")
            appendLine("\"本次未收到画面，建议为通用指引\"。")
        }
        appendLine()

        appendLine("【输出格式】")
        appendLine("严格按下面这个 JSON 结构输出，不要加任何其它文字：")
        appendLine(OUTPUT_SCHEMA)
    }
}
