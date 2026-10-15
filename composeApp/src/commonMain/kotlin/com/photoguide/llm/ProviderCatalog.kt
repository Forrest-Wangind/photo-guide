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
    /**
     * 该服务商允许的采样随机性上限。Claude 官方 API 上限 1.0，
     * 超出会 400；请求侧会自动截断并留日志，不在 UI 上藏 surprise。
     */
    val maxTemperature: Double = 1.5,
    /**
     * 是否支持 image_url 里的 detail 参数（OpenAI 的低分辨率下采样）。
     * Claude 不支持该参数，传了有被网关拒绝的风险 → 不发。
     */
    val supportsImageDetail: Boolean = true,
)

object ProviderCatalog {
    const val DEEPSEEK = "deepseek"
    const val CLAUDE = "claude"
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
            id = CLAUDE,
            displayName = "Claude（Anthropic）",
            baseUrl = "https://api.anthropic.com/v1",
            defaultModel = "claude-sonnet-4-5",
            supportsVision = true,
            maxTemperature = 1.0,
            supportsImageDetail = false,
            note = "走官方 OpenAI 兼容层 /v1/chat/completions，Bearer 鉴权；" +
                "temperature 上限 1.0（自动截断），不支持 detail=low；模型名以官方文档为准，可手改",
        ),
        ProviderPreset(
            id = CUSTOM,
            displayName = "自定义 OpenAI 兼容端点",
            baseUrl = "https://api.example.com/v1",
            defaultModel = "",
            supportsVision = true,
            note = "任何实现了 /chat/completions 的服务：通义、豆包、自建 vLLM、One-API 网关等；" +
                "选中后手动填下方 Base URL / 模型名，可保存多个端点随时切换",
        ),
    )

    fun byId(id: String): ProviderPreset =
        presets.firstOrNull { it.id == id } ?: presets.first()
}

/**
 * 用户保存的自定义端点。
 *
 * 为什么不直接复用 LlmConfig：这里只存"端点三元组"（url/model/key），
 * temperature / max_tokens 等运行参数仍由全局 config 决定，切换端点不该连带改它们。
 *
 * id 是**确定性 key**（由 baseUrl+model 推导，见 keyFor）：既做去重，
 * 也直接作为 config.providerId 的值——选中态因此天然随 config 持久化，
 * 旧版本保存的无 id 数据在加载时按同一规则补齐，无缝迁移。
 */
@kotlinx.serialization.Serializable
data class SavedEndpoint(
    val id: String = "",
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
) {
    val displayName: String get() = name.ifBlank { baseUrl }

    companion object {
        /** 同 (baseUrl, model) 视为同一条，天然去重；改 URL 后 key 变化，属新端点。 */
        fun keyFor(baseUrl: String, model: String): String =
            "saved:" + baseUrl.trim() + "|" + model.trim()

        /** 去掉 scheme 和 path，取 host 作为默认名。 */
        fun hostOf(url: String): String {
            val noScheme = url.removePrefix("https://").removePrefix("http://")
            return noScheme.substringBefore('/').substringBefore(':').ifBlank { url }
        }
    }
}
