package com.photoguide.llm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * 各平台提供自己的 Ktor 引擎：
 *   Android -> OkHttp
 *   iOS     -> Darwin (NSURLSession)
 * 这样 commonMain 不必知道任何引擎细节。
 */
expect fun createPlatformHttpClient(): HttpClient

/** 两端共用的插件配置，保证超时和 JSON 行为一致。 */
internal fun HttpClientConfig<*>.installCommonPlugins() {
    install(HttpTimeout) {
        connectTimeoutMillis = 20_000
        requestTimeoutMillis = 120_000
        socketTimeoutMillis = 120_000
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
                encodeDefaults = true
            }
        )
    }
    // 由我们自己处理非 2xx，以便做「绝不静默失败」的分级报错
    expectSuccess = false
}
