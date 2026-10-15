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
    suspend fun complete(request: LlmRequest, config: LlmConfig, endpoint: Endpoint): LlmResult
}

class LlmClient(
    private val httpClient: HttpClient = createPlatformHttpClient(),
) {
    /** 协议族 → 实现。key 是 ProviderPreset.id（deepseek / claude / openai-compatible）。 */
    private val providers: Map<String, LlmProvider> =
        ProviderCatalog.presets.associate { it.id to OpenAiCompatibleProvider(it, httpClient) }

    suspend fun complete(request: LlmRequest, config: LlmConfig, endpoint: Endpoint): LlmResult {
        // 用**端点的协议族**选实现，而不是端点本身的 id：
        // 一个自定义端点（ep:1，指向 4sapi.org）跑 claude-sonnet 时把协议选成 claude，
        // 才能拿到"temperature 上限 1.0、不发 detail=low"这些正确行为。
        // 未知协议回落到通用 OpenAI 兼容——不静默失败：protocolLabel 在设置页可见，
        // 日志里也会写明用了哪套。
        val provider = providers[endpoint.protocol] ?: providers.getValue(ProviderCatalog.CUSTOM)
        return provider.complete(request, config, endpoint)
    }

    fun close() {
        httpClient.close()
    }
}
