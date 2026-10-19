package com.photoguide.llm

/**
 * 协议预设（服务商）。
 *
 * 只放"确定能用"的默认值；模型名一律可以改，
 * 因为服务商的模型命名变动比 App 迭代快得多——写死等于自找麻烦。
 *
 * ⚠️ 2026-10-15 起它的角色变了：不再代表"一个可点的槽位"，
 * 而是**协议族 + 出厂默认值模板**。用户看到的列表是 [Endpoint]，预设只是种子。
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsVision: Boolean,
    val note: String,
    /**
     * 该协议允许的采样随机性上限。Claude 官方 API 上限 1.0，
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
            displayName = "DeepSeek",
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
            displayName = "自定义端点",
            baseUrl = "https://api.example.com/v1",
            defaultModel = "",
            supportsVision = true,
            note = "任何实现了 /chat/completions 的服务：通义、豆包、自建 vLLM、One-API 网关等",
        ),
    )

    /** 内置端点（首次启动种进列表；可编辑、可重置、**可删除**，删掉后可一键恢复）。 */
    val builtinPresets: List<ProviderPreset> = presets.filter { it.id != CUSTOM }

    /**
     * 自定义端点可选的协议族。
     *
     * 只列两个：通用 OpenAI 兼容 / Claude 兼容。DeepSeek 不必列 ——
     * 它的协议行为（temperature 上限 1.5、支持 detail=low）与通用兼容完全相同，
     * 多一个选项只是多一次选择成本。
     */
    val customProtocols: List<ProviderPreset> = listOf(byId(CLAUDE), byId(CUSTOM))

    fun byId(id: String): ProviderPreset =
        presets.firstOrNull { it.id == id } ?: presets.first { it.id == CUSTOM }
}

/**
 * 端点 —— 模型服务层**唯一的真相源**。
 *
 * 内置两条（DeepSeek / Claude）与用户新建的，全都在 `AppState.endpoints` 这一个列表里；
 * 输入框直接编辑"当前选中"的那条，改一次落盘一次。
 *
 * ⭐ 为什么是这种形态（而不是"预设槽位 + 已保存端点 + 实时 config + 保存按钮"五件套）：
 * 2026-10-15 一天内连出两个 bug ——「改了 URL 按完成没生效」和「改 URL 后切走再切回被重置」，
 * 根因都是**同一份三元组被存了两遍**（config 里一份、槽位/列表里一份），必然出现
 * "选中了 A 但实际在用 B"这类不可判定状态。现在只剩一份，歧义从根上不存在。
 *
 * `id` 是**稳定 id**：内置用 preset id，自定义用 `ep:N`。绝不由 (baseUrl, model) 推导 ——
 * 推导式 id 会在用户改 URL 的那一刻失效，"选中态"随之断裂（上个版本的坑）。
 */
@kotlinx.serialization.Serializable
data class Endpoint(
    val id: String,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    /** 协议族（= ProviderPreset.id）：决定 temperature 上限、是否发 detail=low 等细节。 */
    val protocol: String = ProviderCatalog.CUSTOM,
    /** true = 内置端点（官方出厂配置）；可编辑/可重置/可删除，删掉后可从设置页一键恢复。 */
    val builtin: Boolean = false,
) {
    /** 内置端点用自身 id 当协议（它就是那家官方协议）；自定义端点看 protocol 字段。 */
    val preset: ProviderPreset get() = ProviderCatalog.byId(if (builtin) id else protocol)

    /** 列表里显示的名字：内置用官方名，自定义取 host。 */
    val displayName: String
        get() = if (builtin) preset.displayName else hostOf(baseUrl).ifBlank { "新端点" }

    /** 协议族的中文说法，设置页直接展示。 */
    val protocolLabel: String
        get() = when (protocol) {
            ProviderCatalog.CLAUDE -> "Claude 兼容"
            ProviderCatalog.CUSTOM -> "OpenAI 兼容（通用）"
            else -> preset.displayName
        }

    /** 请求地址。baseUrl 缺 /v1 的兜底重试逻辑在 provider 里，这里只拼后缀。 */
    val chatCompletionsUrl: String get() = baseUrl.trimEnd('/') + "/chat/completions"

    /** 三元组齐了才谈得上发请求。 */
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** 内置端点是否已被改成非出厂值（UI 据此显示"恢复默认"）。 */
    val isModifiedFromDefault: Boolean
        get() = builtin && (baseUrl != preset.baseUrl || model != preset.defaultModel)

    companion object {
        /** 自定义端点 id 的固定前缀。 */
        const val CUSTOM_PREFIX = "ep:"

        /** 去掉 scheme 和 path，取 host 作为默认名。 */
        fun hostOf(url: String): String {
            val noScheme = url.removePrefix("https://").removePrefix("http://")
            return noScheme.substringBefore('/').substringBefore(':').ifBlank { url }
        }

        /**
         * 生成新的自定义端点 id。
         *
         * 用"已占用集合里的计数"而不是时间戳：`Clock.nowMs()` 是**进程启动起算的单调时钟**，
         * 每次冷启动都从 0 附近重新计数，拿它当 id 会跨启动撞号（踩过）。
         * 传入 taken 后保证不与现有 id 冲突，且可读（ep:1 / ep:2 …）。
         */
        fun newCustomId(taken: Collection<String>): String {
            var n = taken.count { it.startsWith(CUSTOM_PREFIX) } + 1
            while (taken.contains(CUSTOM_PREFIX + n)) n++
            return CUSTOM_PREFIX + n
        }
    }
}
