package com.photoguide.llm

/**
 * 失败分类。
 *
 * 设计原则 3「绝不静默失败」：不允许只回一句"失败了"。
 * 每一类都要能在 UI 上区分对待——超时可以重试，未配置要去设置页，
 * 限流要退避，鉴权失败说明 Key 错了。
 */
enum class FailureKind {
    NOT_CONFIGURED,
    UNAUTHORIZED,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    SERVER,
    BAD_RESPONSE,
    CANCELLED,
    UNKNOWN;

    val isRetryable: Boolean
        get() = this == TIMEOUT || this == NETWORK || this == SERVER || this == RATE_LIMITED
}

data class LlmUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

sealed interface LlmResult {
    data class Ok(
        val text: String,
        val usage: LlmUsage?,
        val latencyMs: Long,
        /** finish_reason=length：输出被 max_tokens 截断。上层必须显式告知用户，不许装没事。 */
        val truncated: Boolean = false,
    ) : LlmResult

    data class Failure(
        val kind: FailureKind,
        val message: String,
        /** 服务端原始返回，便于排查（截断后）。 */
        val raw: String? = null,
    ) : LlmResult
}

data class LlmRequest(
    val systemPrompt: String,
    val userPrompt: String,
    /** 形如 data:image/jpeg;base64,...；为 null 表示这次不传图。 */
    val imageDataUrl: String?,
    val lowDetail: Boolean,
)
