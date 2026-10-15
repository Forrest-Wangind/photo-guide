package com.photoguide.llm

import io.ktor.client.HttpClient

/**
 * 大模型门面。
 *
 * 之所以要有这一层：把「选哪家」变成运行时可改的配置，而不是编译期写死。
 * 上层（HintEngine）只调 LlmClient.complete()，永远不知道背后是 DeepSeek 还是别的。
 */
interface LlmProvider {
    val id: String
    val displayName: String
    suspend fun complete(request: LlmRequest, config: LlmConfig): LlmResult
}

class LlmClient(
    private val httpClient: HttpClient = createPlatformHttpClient(),
) {
    private val providers: Map<String, LlmProvider> =
        ProviderCatalog.presets.associate { it.id to OpenAiCompatibleProvider(it, httpClient) }

    suspend fun complete(request: LlmRequest, config: LlmConfig): LlmResult {
        // providerId 可能是内置预设 id（deepseek/claude），也可能是已保存自定义端点的
        // "saved:xxx"——后者不在内置表里，统一走 OpenAI 兼容实现：
        // 协议相同，三元组（url/model/key）都在 config 里，用哪个 preset 对象无差别
        val provider = providers[config.providerId] ?: providers.getValue(ProviderCatalog.CUSTOM)
        return provider.complete(request, config)
    }

    fun close() {
        httpClient.close()
    }
}
