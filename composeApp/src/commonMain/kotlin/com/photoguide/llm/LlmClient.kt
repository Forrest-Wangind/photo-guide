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
        val provider = providers[config.providerId] ?: providers.getValue(ProviderCatalog.DEEPSEEK)
        return provider.complete(request, config)
    }

    fun close() {
        httpClient.close()
    }
}
