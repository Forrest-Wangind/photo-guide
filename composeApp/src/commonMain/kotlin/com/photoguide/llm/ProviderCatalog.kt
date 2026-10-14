package com.photoguide.llm

/**
 * 服务商预设。
 *
 * 只放"确定能用"的默认值；模型名一律可以在设置里手改，
 * 因为服务商的模型命名变动比 App 迭代快得多——写死等于自找麻烦。
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsVision: Boolean,
    val note: String,
)

object ProviderCatalog {
    const val DEEPSEEK = "deepseek"
    const val CUSTOM = "openai-compatible"

    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = DEEPSEEK,
            displayName = "DeepSeek（默认）",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-flash-vision-exp",
            supportsVision = true,
            note = "视觉实验模型，兼容 OpenAI Chat Completions；图片按尺寸折 token，单图最多 384",
        ),
        ProviderPreset(
            id = CUSTOM,
            displayName = "自定义 OpenAI 兼容端点",
            baseUrl = "https://api.example.com/v1",
            defaultModel = "",
            supportsVision = true,
            note = "任何实现了 /chat/completions 的服务：通义、豆包、自建 vLLM、One-API 网关等",
        ),
    )

    fun byId(id: String): ProviderPreset =
        presets.firstOrNull { it.id == id } ?: presets.first()
}
