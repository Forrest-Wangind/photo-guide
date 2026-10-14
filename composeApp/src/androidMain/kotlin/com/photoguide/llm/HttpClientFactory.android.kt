package com.photoguide.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android 用 OkHttp 引擎：连接池成熟、超时语义清晰，
 * 而且它不依赖 GMS，国内机型无障碍。
 */
actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    installCommonPlugins()
    engine {
        config {
            retryOnConnectionFailure(true)
        }
    }
}
