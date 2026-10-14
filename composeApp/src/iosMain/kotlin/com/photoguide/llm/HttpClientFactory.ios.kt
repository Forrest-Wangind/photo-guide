package com.photoguide.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * iOS 用 Darwin 引擎（基于 NSURLSession）：系统原生、走系统代理与 ATS 策略，
 * 不需要额外打包 HTTP 栈。
 */
actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin) {
    installCommonPlugins()
}
