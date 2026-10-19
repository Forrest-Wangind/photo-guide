package com.photoguide.llm

import kotlinx.serialization.Serializable

/**
 * 运行期配置。
 *
 * ⚠️ 2026-10-15 起**只留两样东西**：
 *   1. 当前生效的端点 id（指向 [Endpoint] 列表里的一条）；
 *   2. 与"用哪家"无关的运行参数（随机性 / 输出上限 / 超时 / 是否传图 / 额外要求）。
 *
 * baseUrl、model、apiKey **不再存在这里** —— 它们属于 [Endpoint]，只存一份。
 * 过去这里也放一份，于是"改了 config 但列表里还是旧值"这种不可判定状态永远可能存在
 * （2026-10-15 连出两个 bug 的根因）。而且切换端点本就不该连带改 temperature /
 * max_tokens，这层切分也正好是对的。
 */
@Serializable
data class LlmConfig(
    /** 当前生效端点 id。内置端点的 id 即 preset id（deepseek / claude），自定义为 `ep:N`。 */
    val activeEndpointId: String = ProviderCatalog.DEEPSEEK,

    /**
     * 采样随机性。UI 上叫「随机性」，**不要翻成"温度"**——
     * 拍照类产品里"温度"第一反应是色温，会把人带偏。
     * 0.6 兼顾稳定（建议不飘）与表达多样（台词不重复）。
     */
    val temperature: Double = 0.6,

    /**
     * 单次回复的输出上限。
     *
     * ⚠️ 不能设太小（如 900）：带思考的模型会把推理 token 也算进 max_tokens，
     * 思考没结束正文就被截断，表现为 finish_reason=length 且 content 为空。
     * 结构化 JSON 输出（双栏 + 要点 + 问题 + 警告）留足 4096 才稳。
     */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val timeoutMs: Long = 60_000,

    /** 是否把照片一起上传。关掉后会退化成"纯文本也能用"的形态（但拿不到审美判断）。 */
    val sendImage: Boolean = true,

    /**
     * detail=low 会把图下采样到 512x512 再推理，更快更便宜（§2.4 三件套里的「缩略图」）。
     * 协议不支持该参数的端点（Claude）由 provider 自动跳过，不会发出去被网关拒。
     */
    val lowDetail: Boolean = true,

    /** 用户在设置里追加的额外要求，会拼进 system prompt。 */
    val extraInstruction: String = "",

) {
    companion object {
        /** 当前默认输出上限。 */
        const val DEFAULT_MAX_TOKENS = 4096

        /** 旧版默认值：已持久化配置里若仍是它，加载时会被提升（见 AppState.loadConfig）。 */
        const val LEGACY_MAX_TOKENS = 900

        /** 持久化配置的输出上限下限——低于它视为旧默认残留，不足以容纳思考 + JSON 正文。 */
        const val MIN_VALID_MAX_TOKENS = 2048
    }
}
