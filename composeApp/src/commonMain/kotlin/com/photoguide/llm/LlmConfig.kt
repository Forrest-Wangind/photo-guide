package com.photoguide.llm

import kotlinx.serialization.Serializable

/**
 * 远端大模型配置 —— 完全可配置，不写死任何一家。
 *
 * 默认值指向 DeepSeek 的视觉实验模型：
 *   baseUrl = https://api.deepseek.com
 *   model   = deepseek-v4-flash-vision-exp
 * 该模型兼容 OpenAI Chat Completions 格式，图片最多按 384 token/张计费，
 * 输入 ¥1 / 输出 ¥2 每百万 token（2026-08 上线时口径，以官方文档为准）。
 *
 * 换成任何 OpenAI 兼容端点（通义 / 豆包 / 自建 vLLM / One-API 网关）只需改这三个字段。
 */
@Serializable
data class LlmConfig(
    val providerId: String = ProviderCatalog.DEEPSEEK,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-v4-flash-vision-exp",
    val apiKey: String = "",
    val temperature: Double = 0.6,
    val maxTokens: Int = 900,
    val timeoutMs: Long = 60_000,

    /** 是否把照片一起上传。关掉后会退化成"纯文本也能用"的形态（但拿不到审美判断）。 */
    val sendImage: Boolean = true,

    /**
     * DeepSeek 的 detail=low 会把图下采样到 512x512 再推理，更快更便宜。
     * 这正好对应 §2.4 三件套里的「缩略图」那一件。
     */
    val lowDetail: Boolean = true,

    /** 用户在设置里追加的额外要求，会拼进 system prompt。 */
    val extraInstruction: String = "",
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    val endpoint: String get() = baseUrl.trimEnd('/') + "/chat/completions"
}
