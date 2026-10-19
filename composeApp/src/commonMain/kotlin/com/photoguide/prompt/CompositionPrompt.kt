package com.photoguide.prompt

import com.photoguide.decision.ShootingIntent
import com.photoguide.guide.CompositionGuide
import com.photoguide.llm.LlmConfig
import com.photoguide.perception.PerceptionSnapshot
import kotlinx.serialization.json.Json

/**
 * 一次请求实际使用的提示词快照 —— 只给调试面板用。
 *
 * 存在的理由：提示词是本产品最容易出事的一环（"对着空房间写台词"就是这么来的），
 * 必须能在真机上**逐字看到实际发出去的东西**，而不是靠看代码脑补。
 *
 * [preview] = true 表示还没拍过照，这是按当前意图预演的，不是真实发送的内容——
 * 必须和真实快照区分开，否则调试时会拿预演内容去解释一次真实的失败。
 */
data class PromptTrace(
    val system: String,
    val user: String,
    val imageAttached: Boolean,
    val model: String,
    val maxTokens: Int,
    val preview: Boolean,
)

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
你是一位有 20 年经验的商业摄影师，现在在做实时拍摄指导。用户正拿着手机，
要拍一张重要的照片。他要的不是摄影课，是"现在马上该怎么做"。

你最常见的场景是**给人拍照**（本产品的主场景），但用户也可能在拍
室内一角、食物、街景、静物、宠物——这些画面里根本没有人物。

你的输出必须同时回答两件事：
1. photographerActions —— 摄影师（拿手机的人）该做什么：机位、角度、距离、构图修正、何时按快门；
2. 第二栏，**按画面里有没有人物二选一**：
   a) 有人物（含合影）→ 填 modelLines：模特该做什么，写成一句可以直接照着念的口语；
   b) 没有人物（静物、空间、室内一角、食物、宠物、风景）→ 填 sceneNotes：
      这个画面值得强调的要点（光线方向与色温、前中后景层次、留白、杂物规避、氛围）。
   ⚠️ 两者**必须二选一**，另一个字段给空数组 []。
   ⚠️ 绝对不要对着没有人的画面写"对她说"的台词，也不要写"让模特…"。
   判断依据以画面内容为准；看不到画面时以用户的描述为准；都判断不了，默认按 a) 处理。

硬性要求：
- 每条 photographerActions 必须具体、可执行、当场能做完。
  反例（禁止）："注意构图"、"找个好角度"、"调整一下距离"。
  正例："往左挪半步，让树别贴在她头顶"、"把手机降到胸口高度，从下往上一点拍"。
- 每条 modelLines 必须是口语，带明确方向或幅度。
  正例："头往右转一点点，看远处，别看我"、"下巴往前伸一点点，再收回来"。
  禁止书面语、禁止没有方向的"放松一点"。
- 每条 sceneNotes 必须是针对该画面的具体动作或判断，不能是空泛的审美评价。
  反例（禁止）："注意光线的运用"、"营造温馨氛围"。
  正例："把台灯往左挪 10 厘米，让光从侧面扫过茶杯"、"退半步把门框带进画面右侧当边框"。
- 如果画面已经足够好，就直接说"这样就行，按快门"，不要硬凑问题。
- 你不确定的地方，写进 warnings，禁止编造。宁可少说，不要胡说。
- 只输出 JSON，不要 markdown 代码块，不要任何 JSON 之外的文字。
""".trimIndent()

    private val OUTPUT_SCHEMA = """
{
  "headline": "一句话结论，不超过 20 字",
  "score": 0-100 的整数,
  "photographerActions": ["摄影师的具体动作，2-4 条"],
  "modelLines": ["对模特说的口语指令，1-3 条。画面里没有人时必须为 []"],
  "sceneNotes": ["无人像画面时的强化要点，1-3 条。画面里有人物时必须为 []"],
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
        /**
         * 取景器上正显示着的参考线。
         *
         * 为什么会出现在提示词里：参考线是**画在用户眼前的**，模型不知道它的存在，
         * 就会说"稍微往左一点"——而用户眼前横着四条三分线，这句话没法核对。
         * 告诉模型"用户正开着三分线"，它才能说"把她的眼睛放到右上交点"，
         * 参考线和台词才是一件事，而不是两个各说各话的功能。
         */
        guide: CompositionGuide = CompositionGuide.NONE,
    ): String = buildString {
        appendLine("【用户的拍摄意图】")
        val preset = intent.preset
        if (preset != null) {
            appendLine("- 场景：${preset.displayName}。${preset.guidance}")
            appendLine(
                if (preset.subjectIsPerson) {
                    "- 本场景预设：画面里有**人物** → 第二栏用 modelLines。"
                } else {
                    "- 本场景预设：画面里**没有人物** → 第二栏必须用 sceneNotes，modelLines 给空数组。"
                }
            )
        } else {
            appendLine("- 用户**没有指定预设场景**（预设是可选快捷项，已被取消）。")
            appendLine("  请不要套用任何默认场景假设，完全依据下面的补充说明判断要拍什么。")
        }
        if (intent.freeText.isNotBlank()) {
            appendLine("- 用户补充说明：${intent.freeText.trim()}")
            appendLine("  若描述里的主体与场景预设不符，**以用户的描述为准**；")
            appendLine("  描述里明显没有人物时（例如\"室内温馨一角\"），第二栏用 sceneNotes。")
        } else if (preset == null) {
            // 既不选场景也不写字：不能默认成"人像半身"硬凑一套建议
            appendLine("- 用户既没选场景也没写说明。请先根据画面内容判断主体是什么，再给针对性建议；")
            appendLine("  若画面里没有可辨认的明确主体，必须在 warnings 里说明\"本次为通用指引\"。")
        }
        appendLine()

        appendLine("【屏幕上的参考线】")
        if (guide == CompositionGuide.NONE) {
            appendLine("用户没有开启参考线，画面上没有任何辅助线。")
            appendLine("位置类建议请用自然语言表达（左/右/上/下、靠近画面边缘、占画面几分之几），")
            appendLine("**不要**引用\"三分线交点\"\"黄金线\"这类画面上并不存在的参照。")
        } else {
            appendLine("用户正开着「${guide.displayName}」：${guide.promptHint}。")
            appendLine("这是用户抬眼就能看到的线，所以位置类建议**必须直接引用它**。")
            appendLine("正例：\"把她的眼睛放到右上交点\"、\"让杯口压在右下那条黄金线上\"、\"往左挪，让她落在左边那条竖线上\"。")
            appendLine("反例（禁止）：\"稍微往左一点\"\"再靠近一点\"——对不上用户眼前看到的东西。")
            if (guide == CompositionGuide.SAFE_FRAME) {
                appendLine("注意：安全框是**裁切**提示，不是构图线。主体仍按常规构图摆放，")
                appendLine("只需保证关键内容（人脸、手、产品主体）不跑出框外。")
            }
            if (guide == CompositionGuide.SPIRAL) {
                appendLine("黄金螺旋的\"眼睛\"是一个具体落点：请说清主体该落在螺旋中心附近，")
                appendLine("而不是笼统地让人\"往螺旋方向靠\"。")
            }
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
            appendLine("你可以看到用户刚拍的这张照片本身。请基于画面内容判断，")
            appendLine("尤其是：**画面里到底有没有人物** —— 这决定第二栏填 modelLines 还是 sceneNotes。")
        } else {
            appendLine("你没有收到照片，你看不到画面。")
            appendLine("因此：不要编造画面里有什么人、什么背景。")
            appendLine("第二栏只能依据上面的用户意图来判断：描述里没有人，就不要写 modelLines。")
            appendLine("你只能给通用的场景化构图建议，并且必须在 warnings 里说明")
            appendLine("\"本次未收到画面，建议为通用指引\"。")
        }
        appendLine()

        appendLine("【输出格式】")
        appendLine("严格按下面这个 JSON 结构输出，不要加任何其它文字：")
        appendLine(OUTPUT_SCHEMA)
    }
}
