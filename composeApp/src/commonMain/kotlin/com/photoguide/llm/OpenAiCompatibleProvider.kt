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

    override suspend fun complete(request: LlmRequest, config: LlmConfig): LlmResult {
        if (!config.isConfigured) {
            return LlmResult.Failure(
                FailureKind.NOT_CONFIGURED,
                "还没配置 API Key / Base URL / 模型名，去设置页填一下",
            )
        }

        val started = Clock.nowMs()
        return try {
            val response: HttpResponse = withTimeout(config.timeoutMs) {
                client.post(config.endpoint) {
                    header("Authorization", "Bearer ${config.apiKey.trim()}")
                    header("Accept", "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(buildRequestBody(request, config))
                }
            }

            val latency = Clock.nowMs() - started
            val raw = runCatching { response.bodyAsText() }.getOrDefault("")

            if (!response.status.isSuccess()) {
                platformLog(TAG, "HTTP ${response.status.value}: ${raw.take(500)}")
                return LlmResult.Failure(
                    kind = mapStatus(response.status),
                    message = humanStatusMessage(response.status, raw),
                    raw = raw.take(600),
                )
            }

            val parsed = runCatching { json.decodeFromString<ChatResponse>(raw) }.getOrNull()
            if (parsed == null) {
                return LlmResult.Failure(
                    FailureKind.BAD_RESPONSE,
                    "服务端返回不是合法 JSON（可能被网关改写）",
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

    private fun buildRequestBody(request: LlmRequest, config: LlmConfig): JsonObject {
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
                            // detail=low 触发服务端下采样到 512x512，省 token 又够用
                            if (request.lowDetail) put("detail", "low")
                        }
                    )
                }
            }
        }

        return buildJsonObject {
            put("model", config.model.trim())
            put("temperature", config.temperature)
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
        return when {
            name.contains("Timeout", ignoreCase = true) -> FailureKind.TIMEOUT
            name.contains("Connect", ignoreCase = true) -> FailureKind.NETWORK
            name.contains("UnknownHost", ignoreCase = true) -> FailureKind.NETWORK
            name.contains("UnresolvedAddress", ignoreCase = true) -> FailureKind.NETWORK
            name.contains("Socket", ignoreCase = true) -> FailureKind.NETWORK
            else -> FailureKind.UNKNOWN
        }
    }

    private fun describe(e: Throwable): String {
        val prefix = when (classify(e)) {
            FailureKind.TIMEOUT -> "网络超时"
            FailureKind.NETWORK -> "网络不可达"
            else -> "调用异常"
        }
        return "$prefix：${e::class.simpleName} ${e.message.orEmpty().take(200)}".trim()
    }

    private companion object {
        const val TAG = "LlmProvider"
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
