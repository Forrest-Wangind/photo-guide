package com.photoguide.decision

import com.photoguide.capture.CapturedPhoto
import com.photoguide.llm.Endpoint
import com.photoguide.llm.FailureKind
import com.photoguide.llm.LlmClient
import com.photoguide.llm.LlmConfig
import com.photoguide.llm.LlmRequest
import com.photoguide.llm.LlmResult
import com.photoguide.llm.LlmUsage
import com.photoguide.metrics.StageTimer
import com.photoguide.metrics.StageTiming
import com.photoguide.perception.PerceptionSnapshot
import com.photoguide.perception.PerceptionSource
import com.photoguide.platform.platformLog
import com.photoguide.prompt.CompositionPrompt
import com.photoguide.prompt.PromptTrace
import kotlinx.serialization.json.Json

sealed interface HintOutcome {
    data class Success(
        val hint: CompositionHint,
        val timings: List<StageTiming>,
        val usage: LlmUsage?,
        val totalMs: Long,
        val networkMs: Long,
        /** 模型原始返回，Debug 面板用；也方便排查"是不是解析错了"。 */
        val rawResponse: String,
        /** 本次实际发出去的提示词（Debug 面板逐字展示，含失败场景）。 */
        val prompt: PromptTrace,
    ) : HintOutcome

    data class Failure(
        val kind: FailureKind,
        val message: String,
        val timings: List<StageTiming>,
        /** 失败时更要能看到发了什么——否则无法判断是提示词的问题还是服务端的问题。 */
        val prompt: PromptTrace,
    ) : HintOutcome
}

/**
 * 决策层编排。
 *
 * 链路：照片 → 本地感知（Phase 1 为空实现）→ 组装提示词 → 云端多模态模型 → 解析成双栏卡片。
 *
 * 三条不可违背的纪律：
 *   1. 每一段都计时（为 Phase 2 的流畅性基线留下可比数据）；
 *   2. 解析失败必须降级**且标注**，不能白屏也不能假装成功；
 *   3. 模型返回的 warnings 一字不改地透传给用户。
 */
class HintEngine(private val llmClient: LlmClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    suspend fun analyze(
        photo: CapturedPhoto?,
        intent: ShootingIntent,
        perception: PerceptionSource,
        config: LlmConfig,
        /** 本次实际要用的端点（三元组 + 协议族）。不再是 config 的一坨字段。 */
        endpoint: Endpoint,
    ): HintOutcome {
        val timer = StageTimer()

        // ---- 1. 本地感知 ----------------------------------------------------
        val snapshot: PerceptionSnapshot = if (photo != null) {
            perception.analyze(photo)
        } else {
            PerceptionSnapshot(source = perception.id, notes = listOf("本次未拍摄照片"))
        }
        timer.mark("感知")

        // ---- 2. 编码上传数据 ------------------------------------------------
        // 单独计时：一张 720p JPEG 的 base64 在低端机上是几十毫秒量级，值得可见
        val imageDataUrl = if (photo != null && config.sendImage) photo.toDataUrl() else null
        timer.mark("编码")

        // ---- 3. 组装提示词 --------------------------------------------------
        val request = LlmRequest(
            systemPrompt = CompositionPrompt.buildSystemPrompt(config),
            userPrompt = CompositionPrompt.buildUserPrompt(
                intent = intent,
                perception = snapshot,
                config = config,
                imageAttached = imageDataUrl != null,
            ),
            imageDataUrl = imageDataUrl,
            lowDetail = config.lowDetail,
        )
        timer.mark("组装")

        // 快照实际发出去的内容，供 Debug 面板逐字查看
        val trace = PromptTrace(
            system = request.systemPrompt,
            user = request.userPrompt,
            imageAttached = imageDataUrl != null,
            model = endpoint.model,
            maxTokens = config.maxTokens,
            preview = false,
        )

        // ---- 4. 调模型 ------------------------------------------------------
        val result = llmClient.complete(request, config, endpoint)
        timer.mark("模型")

        return when (result) {
            is LlmResult.Ok -> {
                val hint = parseHint(result.text)
                HintOutcome.Success(
                    // 输出被截断时必须显式标注（设计原则 3）——即使 JSON 碰巧解析成功，
                    // 内容也可能是半截的，不能装作一切正常
                    hint = if (result.truncated) hint.copy(
                        warnings = hint.warnings +
                            "输出被 max_tokens 截断，内容可能不完整；请到设置里调大 max_tokens",
                    ) else hint,
                    timings = timer.snapshot(),
                    usage = result.usage,
                    totalMs = timer.totalMs(),
                    networkMs = result.latencyMs,
                    rawResponse = result.text,
                    prompt = trace,
                )
            }

            is LlmResult.Failure -> HintOutcome.Failure(
                kind = result.kind,
                message = result.message,
                timings = timer.snapshot(),
                prompt = trace,
            )
        }
    }

    /**
     * 容错解析。
     *
     * 现实里模型偶尔会：
     *  - 包一层 ```json 围栏；
     *  - 前面加一句"好的，这是分析结果：";
     *  - 少给一两个字段。
     * 这三种都不该让用户看到白屏，但也都不能假装没发生 —— 降级必须写进 warnings。
     */
    private fun parseHint(raw: String): CompositionHint {
        val cleaned = stripCodeFences(raw).trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')

        if (start in 0 until end) {
            val candidate = cleaned.substring(start, end + 1)
            val decoded = runCatching {
                json.decodeFromString(CompositionHint.serializer(), candidate)
            }.getOrNull()

            if (decoded != null && decoded.hasContent) {
                return decoded
            }
            if (decoded != null) {
                // 结构能解析但内容为空 —— 也属于异常，标注出来
                return decoded.copy(
                    headline = decoded.headline.ifBlank { "模型返回了空的结构化结果" },
                    warnings = decoded.warnings + "模型返回的结构化内容为空，建议重拍一次或换个说法",
                )
            }
            platformLog(TAG, "结构化解析失败，退回文本模式。原文前 300 字：${cleaned.take(300)}")
        } else {
            platformLog(TAG, "返回里没有找到 JSON 对象。原文前 300 字：${cleaned.take(300)}")
        }

        return CompositionHint(
            headline = cleaned.take(60).ifBlank { "模型没有返回内容" },
            issues = if (cleaned.length > 60) listOf(cleaned.take(400)) else emptyList(),
            warnings = listOf("结构化解析失败，已降级为原始文本显示（不是你的操作问题）"),
        )
    }

    private fun stripCodeFences(text: String): String {
        var s = text.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            if (firstNewline > 0) s = s.substring(firstNewline + 1)
            val fence = s.lastIndexOf("```")
            if (fence >= 0) s = s.substring(0, fence)
        }
        return s
    }

    private companion object {
        const val TAG = "HintEngine"
    }
}
