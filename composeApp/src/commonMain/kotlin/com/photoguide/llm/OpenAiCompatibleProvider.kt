package com.photoguide.llm

import com.photoguide.platform.platformLog
import com.photoguide.util.Clock
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * OpenAI Chat Completions 兼容实现。
 *
 * 为什么只写一个实现就够：DeepSeek / 通义 / 豆包 / 自建 vLLM 都提供
 * 兼容的 /chat/completions，端点的差别只在 baseUrl、model、apiKey 三个字段。
 * 把它们抽成配置，不抽成代码。
 *
 * 图片按官方文档走 content 数组的 image_url 块；不支持视觉的模型会自动
 * 退化为纯文本（由 config.sendImage 控制）。
 */
class OpenAiCompatibleProvider(
    private val preset: ProviderPreset,
    private val client: HttpClient,
) : LlmProvider {

    override val id: String get() = preset.id
    override val displayName: String get() = preset.displayName

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override suspend fun complete(request: LlmRequest, config: LlmConfig, endpoint: Endpoint): LlmResult {
        if (!endpoint.isUsable) {
            return LlmResult.Failure(
                FailureKind.NOT_CONFIGURED,
                "端点「${endpoint.displayName}」还没填全：Base URL / 模型名 / API Key，" +
                    "去设置页补一下",
            )
        }

        // 瞬态失败（连接被中途掐断、陈旧 keep-alive 连接被复用、网关间歇性返回非 JSON 等）
        // 自动重试——这类失败重试通常立刻就好，弹错误横幅反而是打扰。
        // NETWORK：瞬态网络失败；BAD_RESPONSE：多见于中转站 HTTP 200 夹 HTML/纯文本错误页，
        // 间歇性抖动，实测重试即愈。TIMEOUT 重试要再等整个超时窗口，留给用户手动决定。
        var attempt = 0
        while (true) {
            attempt++
            val result = attemptOnce(request, config, endpoint)
            if (result is LlmResult.Ok) return result
            val failure = result as LlmResult.Failure
            val retryable = failure.kind == FailureKind.NETWORK || failure.kind == FailureKind.BAD_RESPONSE
            if (!retryable || attempt >= MAX_ATTEMPTS) return result
            platformLog(
                TAG,
                "瞬态失败（第 $attempt 次），${RETRY_DELAY_MS}ms 后重试：${failure.message.take(120)}",
            )
            delay(RETRY_DELAY_MS)
        }
    }

    private suspend fun attemptOnce(
        request: LlmRequest,
        config: LlmConfig,
        endpoint: Endpoint,
        url: String = endpoint.chatCompletionsUrl,
    ): LlmResult {
        val started = Clock.nowMs()
        return try {
            val body = buildRequestBody(request, config, endpoint)
            // 请求侧落日志：端点 / 模型 / 是否带图 / 体积。只打大小不打内容——
            // body 里是 base64 图片，全打会把 logcat 撑爆
            val bodyChars = json.encodeToString(JsonObject.serializer(), body).length
            platformLog(
                TAG,
                "POST $url · endpoint=${endpoint.displayName} · model=${endpoint.model.trim()} · " +
                    "image=${request.imageDataUrl != null} · body≈${bodyChars / 1024}KB",
            )

            // 响应体必须读在超时窗口内、且异常要抛出去：
            // 连接中途断开时走到下面的分类（NETWORK，可重试），
            // 而不是被 runCatching 吞成空字符串、伪装成"返回不是合法 JSON"
            val (response, raw) = withTimeout(config.timeoutMs) {
                val resp = client.post(url) {
                    header("Authorization", "Bearer ${endpoint.apiKey.trim()}")
                    header("Accept", "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                resp to resp.bodyAsText()
            }

            val latency = Clock.nowMs() - started

            // AI 原始返回值全量落日志（无论成败）——排障第一现场。
            // logcat 单条有 ~4KB 上限，超长会被系统静默截断，所以按 3000 字符分段打全
            val parts = raw.chunked(3000)
            platformLog(
                TAG,
                "HTTP ${response.status.value} · ${latency}ms · 原始返回共 ${raw.length} 字符，分 ${parts.size} 段",
            )
            parts.forEachIndexed { i, part ->
                platformLog(TAG, "[$i/${parts.size}] $part")
            }

            if (!response.status.isSuccess()) {
                return LlmResult.Failure(
                    kind = mapStatus(response.status),
                    message = humanStatusMessage(response.status, raw),
                    raw = raw.take(600),
                )
            }

            val parsed = runCatching { json.decodeFromString<ChatResponse>(raw) }.getOrNull()
            if (parsed == null) {
                val trimmed = raw.trimStart()
                if (trimmed.startsWith("<") && !url.contains("/v1/")) {
                    // 返回的是网页而非 API 结果——最典型原因是 Base URL 少了 /v1：
                    // 网关（New API / One-API 系）对未知路径会 SPA 兜底返回首页 HTML，
                    // HTTP 200 + HTML，用户侧表现为"服务端返回不是合法 JSON"。
                    // 自动补 /v1 重试一次（altUrl 已含 /v1/，不会二次进入本分支），
                    // 成功则提示用户把 Base URL 改对，一劳永逸
                    val altUrl = endpoint.baseUrl.trimEnd('/') + "/v1/chat/completions"
                    platformLog(TAG, "返回的是 HTML 网页——Base URL 疑似缺少 /v1，自动改用 $altUrl 重试")
                    val retried = attemptOnce(request, config, endpoint, altUrl)
                    if (retried is LlmResult.Ok) {
                        platformLog(
                            TAG,
                            "补 /v1 后请求成功。请在设置里把端点「${endpoint.displayName}」的 Base URL " +
                                "改为 \"${endpoint.baseUrl.trimEnd('/')}/v1\"",
                        )
                    }
                    return retried
                }
                // 横幅里带原文开头，让用户不用连电脑也能看出是 HTML 还是别的
                val message = if (trimmed.startsWith("<")) {
                    "服务端返回了网页（HTML）而非 API 结果——请检查 Base URL 是否缺少 /v1 " +
                        "（如 https://host/v1）。原文开头：${trimmed.take(80)}"
                } else {
                    "服务端返回不是合法 JSON——多为中转站间歇性故障，已自动重试仍失败。" +
                        "原文开头：${raw.take(80)}"
                }
                return LlmResult.Failure(
                    FailureKind.BAD_RESPONSE,
                    message,
                    raw.take(600),
                )
            }

            parsed.error?.let { err ->
                return LlmResult.Failure(
                    FailureKind.BAD_RESPONSE,
                    "服务端报错：${err.message ?: err.code ?: "未知"}",
                    raw.take(600),
                )
            }

            val text = parsed.choices.firstOrNull()?.message?.content
            if (text.isNullOrBlank()) {
                val finish = parsed.choices.firstOrNull()?.finishReason
                return LlmResult.Failure(
                    FailureKind.BAD_RESPONSE,
                    if (finish == "length") "输出被 max_tokens 截断且没有正文，请调大 max_tokens"
                    else "返回内容为空（finish_reason=$finish）",
                    raw.take(600),
                )
            }

            LlmResult.Ok(
                text = text,
                usage = parsed.usage?.let { LlmUsage(it.promptTokens, it.completionTokens) },
                latencyMs = latency,
                truncated = parsed.choices.firstOrNull()?.finishReason == "length",
            )
        } catch (e: TimeoutCancellationException) {
            LlmResult.Failure(FailureKind.TIMEOUT, "本地等待超时（${config.timeoutMs} ms）")
        } catch (e: CancellationException) {
            // 上游主动取消，必须原样抛出，不能吞（否则结构化并发就断了）
            throw e
        } catch (e: Throwable) {
            platformLog(TAG, "请求异常 ${e::class.simpleName}: ${e.message}")
            LlmResult.Failure(classify(e), describe(e), null)
        }
    }

    private fun buildRequestBody(request: LlmRequest, config: LlmConfig, endpoint: Endpoint): JsonObject {
        val userContent: JsonArray = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", request.userPrompt)
            }
            request.imageDataUrl?.let { url ->
                addJsonObject {
                    put("type", "image_url")
                    put(
                        "image_url",
                        buildJsonObject {
                            put("url", url)
                            // detail=low 触发服务端下采样到 512x512，省 token 又够用；
                            // 但不是每家都认这个参数（Claude 会拒），预设里逐家声明
                            if (request.lowDetail && preset.supportsImageDetail) put("detail", "low")
                        }
                    )
                }
            }
        }

        // 各家 temperature 上限不同（Claude 1.0，我们滑杆到 1.5）：超出先截断再发，
        // 并留日志——宁可截断可见，也不能让服务端 400 后用户对着报错猜
        val effectiveTemp = config.temperature.coerceAtMost(preset.maxTemperature)
        if (effectiveTemp < config.temperature) {
            platformLog(
                TAG,
                "temperature=${config.temperature} 超出 ${preset.displayName} 上限，截断为 $effectiveTemp",
            )
        }

        return buildJsonObject {
            put("model", endpoint.model.trim())
            put("temperature", effectiveTemp)
            put("max_tokens", config.maxTokens)
            put("stream", false)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userContent)
                }
            }
        }
    }

    private fun mapStatus(status: HttpStatusCode): FailureKind = when {
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
            FailureKind.UNAUTHORIZED

        status == HttpStatusCode.TooManyRequests -> FailureKind.RATE_LIMITED
        status.value == 402 -> FailureKind.UNAUTHORIZED // 余额不足
        status.value >= 500 -> FailureKind.SERVER
        status.value == 408 || status.value == 504 -> FailureKind.TIMEOUT
        status.value in 400..499 -> FailureKind.BAD_RESPONSE
        else -> FailureKind.UNKNOWN
    }

    private fun humanStatusMessage(status: HttpStatusCode, raw: String): String {
        val detail = runCatching {
            json.decodeFromString<ChatResponse>(raw).error?.message
        }.getOrNull()
        val prefix = when (mapStatus(status)) {
            FailureKind.UNAUTHORIZED -> "鉴权失败（检查 API Key / 余额）"
            FailureKind.RATE_LIMITED -> "被限流"
            FailureKind.SERVER -> "服务端错误"
            FailureKind.TIMEOUT -> "网关超时"
            FailureKind.BAD_RESPONSE -> "请求被拒绝"
            else -> "请求失败"
        }
        return "$prefix：HTTP ${status.value}${detail?.let { " - $it" } ?: ""}"
    }

    private fun classify(e: Throwable): FailureKind {
        val name = e::class.simpleName.orEmpty()
        val msg = e.message.orEmpty()
        return when {
            name.contains("Timeout", ignoreCase = true) -> FailureKind.TIMEOUT
            name.contains("Connect", ignoreCase = true) -> FailureKind.NETWORK
            name.contains("UnknownHost", ignoreCase = true) -> FailureKind.NETWORK
            name.contains("UnresolvedAddress", ignoreCase = true) -> FailureKind.NETWORK
            name.contains("Socket", ignoreCase = true) -> FailureKind.NETWORK
            // Ktor 对"连接被中途断开"的包装：ClosedByteChannelException /
            // "Software caused connection abort"（ECONNABORTED）——
            // 常见于陈旧 keep-alive 连接被复用或大请求体上传时被重置，属瞬态，可重试
            name.contains("ClosedByteChannel", ignoreCase = true) -> FailureKind.NETWORK
            msg.contains("connection abort", ignoreCase = true) ||
                msg.contains("connection reset", ignoreCase = true) ||
                msg.contains("broken pipe", ignoreCase = true) ||
                msg.contains("econnaborted", ignoreCase = true) ||
                msg.contains("econnreset", ignoreCase = true) -> FailureKind.NETWORK
            else -> FailureKind.UNKNOWN
        }
    }

    private fun describe(e: Throwable): String {
        val prefix = when (classify(e)) {
            FailureKind.TIMEOUT -> "网络超时"
            FailureKind.NETWORK -> "网络连接中断"
            else -> "调用异常"
        }
        return "$prefix：${e::class.simpleName} ${e.message.orEmpty().take(200)}".trim()
    }

    private companion object {
        const val TAG = "LlmProvider"

        /** 瞬态网络失败的最大尝试次数（含首次）。 */
        const val MAX_ATTEMPTS = 2

        /** 重试前的小退避：陈旧连接场景下这个间隔足够让连接池换一条新连接。 */
        const val RETRY_DELAY_MS = 600L
    }
}

// ---------------------------------------------------------------------------
// 线上返回体（只声明用得到的字段）
// ---------------------------------------------------------------------------

@Serializable
internal data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null,
)

@Serializable
internal data class Choice(
    val message: Message? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class Message(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
internal data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
)

@Serializable
internal data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
