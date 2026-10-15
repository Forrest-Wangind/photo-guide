package com.photoguide.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.photoguide.capture.CapturedPhoto
import com.photoguide.decision.CompositionHint
import com.photoguide.decision.HintEngine
import com.photoguide.decision.HintOutcome
import com.photoguide.decision.ShootingIntent
import com.photoguide.llm.FailureKind
import com.photoguide.llm.LlmClient
import com.photoguide.llm.LlmConfig
import com.photoguide.llm.ProviderCatalog
import com.photoguide.llm.SavedEndpoint
import com.photoguide.metrics.LatencyTrend
import com.photoguide.metrics.StageTiming
import com.photoguide.perception.NoPerception
import com.photoguide.perception.PerceptionSnapshot
import com.photoguide.perception.PerceptionSource
import com.photoguide.perception.PerceptionRegistry
import com.photoguide.platform.SettingsStore
import com.photoguide.platform.createSettingsStore
import com.photoguide.platform.platformLog
import com.photoguide.prompt.CompositionPrompt
import com.photoguide.prompt.PromptTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class AnalysisPhase { IDLE, ANALYZING, DONE, FAILED }

data class FailureInfo(
    val kind: FailureKind,
    val message: String,
)

/**
 * 应用状态。
 *
 * 刻意不用平台 ViewModel：两边各自的 ViewModel 依赖会带来一堆版本对齐问题，
 * 而 Phase 1 的状态模型简单到不值得。等状态真的复杂起来再换。
 */
class AppState(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) {
    private val llmClient = LlmClient()
    private val engine = HintEngine(llmClient)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    var config by mutableStateOf(loadConfig())
        private set

    /** 用户保存的自定义端点列表，落盘持久化，重启不丢。 */
    var savedEndpoints by mutableStateOf(loadEndpoints())
        private set

    var intent by mutableStateOf(ShootingIntent())
        private set

    var perceptionId by mutableStateOf(NoPerception.ID)
        private set

    var phase by mutableStateOf(AnalysisPhase.IDLE)
        private set

    var hint by mutableStateOf<CompositionHint?>(null)
        private set

    var failure by mutableStateOf<FailureInfo?>(null)
        private set

    var timings by mutableStateOf<List<StageTiming>>(emptyList())
        private set

    var lastPhotoInfo by mutableStateOf<String?>(null)
        private set

    var rawResponse by mutableStateOf<String?>(null)
        private set

    /** 最近一次请求**实际发送**的提示词（失败时也有）。调试面板逐字展示。 */
    var lastPrompt by mutableStateOf<PromptTrace?>(null)
        private set

    var showSettings by mutableStateOf(false)
        private set

    var showDebug by mutableStateOf(false)
        private set

    val trend = LatencyTrend()

    private var job: Job? = null

    // ---------------------------------------------------------------- 配置

    private fun loadConfig(): LlmConfig {
        val stored = settings.getString(KEY_CONFIG) ?: return LlmConfig()
        val decoded = runCatching { json.decodeFromString(LlmConfig.serializer(), stored) }
            .getOrElse {
                platformLog(TAG, "配置反序列化失败，回退默认值：${it.message}")
                return LlmConfig()
            }

        // 迁移：旧版默认 maxTokens=900 会被带思考的模型整个吃在推理阶段，
        // 正文为空（finish_reason=length）。低于下限的存量配置提升到新版默认并落盘。
        if (decoded.maxTokens < LlmConfig.MIN_VALID_MAX_TOKENS) {
            val fixed = decoded.copy(maxTokens = LlmConfig.DEFAULT_MAX_TOKENS)
            platformLog(TAG, "迁移：maxTokens=${decoded.maxTokens} 过小，提升为 ${fixed.maxTokens}")
            settings.putString(KEY_CONFIG, json.encodeToString(LlmConfig.serializer(), fixed))
            return fixed
        }
        return decoded
    }

    fun updateConfig(new: LlmConfig) {
        config = new
        settings.putString(KEY_CONFIG, json.encodeToString(LlmConfig.serializer(), new))
    }

    // ------------------------------------------------ 自定义端点列表

    // ⚠️ 序列化器必须在方法内现场创建，不能做成类成员属性：
    // loadEndpoints() 在 `var savedEndpoints by mutableStateOf(...)` 的初始化阶段就被调用，
    // 若 endpointListSerializer 声明在类体更靠后的位置，那一刻它还是 null，
    // decodeFromString 收到 null 直接抛异常 → 列表每次启动都被打回空，
    // 表现为"保存过的端点重启就丢"（2026-10-15 实锤，logcat 可见 parameter deserializer is null）
    private fun loadEndpoints(): List<SavedEndpoint> {
        val stored = settings.getString(KEY_ENDPOINTS) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(SavedEndpoint.serializer()), stored) }
            // 旧版本存的数据没有 id：按确定性规则补齐，与 saveCurrentEndpoint 一致
            .map { list ->
                list.map { ep ->
                    if (ep.id.isBlank()) ep.copy(id = SavedEndpoint.keyFor(ep.baseUrl, ep.model)) else ep
                }
            }
            .getOrElse {
                // 绝不静默失败：列表坏了宁可当空处理，也要在 logcat 留下原因
                platformLog(TAG, "自定义端点列表反序列化失败，当作空列表：${it.message}")
                emptyList()
            }
    }

    private fun persistEndpoints(list: List<SavedEndpoint>) {
        settings.putString(KEY_ENDPOINTS, json.encodeToString(ListSerializer(SavedEndpoint.serializer()), list))
    }

    /**
     * 把当前填写的 Base URL / 模型名 / Key 存成自定义端点，**并立即启用它**。
     *
     * 同一 (baseUrl, model) 组合已存在时覆盖更新（Key 也会刷新），不产生重复项。
     * @return 是否真的存了；URL 或模型名为空时拒绝，调用方（UI）应禁用按钮，
     *         这里仍显式返回 false 而不是悄悄吞掉。
     */
    fun saveCurrentEndpoint(): Boolean {
        val baseUrl = config.baseUrl.trim()
        val model = config.model.trim()
        if (baseUrl.isBlank() || model.isBlank()) return false
        val entry = SavedEndpoint(
            id = SavedEndpoint.keyFor(baseUrl, model),
            name = SavedEndpoint.hostOf(baseUrl),
            baseUrl = baseUrl,
            model = model,
            apiKey = config.apiKey.trim(),
        )
        val next = savedEndpoints.toMutableList()
        val idx = next.indexOfFirst { it.id == entry.id }
        if (idx >= 0) next[idx] = entry else next.add(entry)
        savedEndpoints = next
        persistEndpoints(next)
        // 保存即启用：providerId 指向该端点，与三元组一起随 config 落盘，重启后仍是它
        updateConfig(config.copy(providerId = entry.id))
        platformLog(TAG, "已保存并启用自定义端点：${entry.displayName} / ${entry.model}（共 ${next.size} 个）")
        return true
    }

    /** 应用一个已保存的端点：providerId 指向它并回填三元组；Key 为空时保留当前 Key。 */
    fun applyEndpoint(entry: SavedEndpoint) {
        updateConfig(
            config.copy(
                providerId = entry.id,
                baseUrl = entry.baseUrl,
                model = entry.model,
                apiKey = entry.apiKey.ifBlank { config.apiKey },
            )
        )
    }

    fun deleteEndpoint(entry: SavedEndpoint) {
        val next = savedEndpoints - entry
        savedEndpoints = next
        persistEndpoints(next)
        // 删除的正是当前使用的端点：config 里的三元组仍有效（请求不会断），
        // 但它已不在列表 → providerId 回落为 CUSTOM，UI 会标注"未保存的自定义端点"，
        // 不静默吞掉这个状态变化
        if (config.providerId == entry.id) {
            updateConfig(config.copy(providerId = ProviderCatalog.CUSTOM))
        }
    }

    /** 保存按钮的可用性：URL 与模型名都非空才有意义。 */
    val canSaveEndpoint: Boolean
        get() = config.baseUrl.isNotBlank() && config.model.isNotBlank()

    // 注意：不能叫 setIntent / setPerceptionId —— 会和 `var xxx` 自动生成的
    // JVM setter 撞签名（Platform declaration clash）。用 update* / select*。
    fun updateIntent(new: ShootingIntent) {
        intent = new
    }

    fun selectPerception(id: String) {
        perceptionId = id
    }

    fun currentPerception(): PerceptionSource = PerceptionRegistry.byId(perceptionId)

    /**
     * 调试用：还没拍过照时，按当前意图**预演**一份提示词。
     *
     * 这样改完预设/描述就能立刻在调试面板里看到提示词长什么样，
     * 不用先花一次真实请求。返回的 trace 带 `preview = true`，
     * 面板会明确标注"这不是实际发送内容"——不把预演和真实快照混为一谈。
     */
    fun previewPrompt(): PromptTrace = PromptTrace(
        system = CompositionPrompt.buildSystemPrompt(config),
        user = CompositionPrompt.buildUserPrompt(
            intent = intent,
            perception = PerceptionSnapshot(
                source = perceptionId,
                notes = listOf("预览：尚未拍摄，无本地感知数据"),
            ),
            config = config,
            imageAttached = config.sendImage,
        ),
        imageAttached = config.sendImage,
        model = config.model,
        maxTokens = config.maxTokens,
        preview = true,
    )

    // ---------------------------------------------------------------- 分析

    fun analyze(photo: CapturedPhoto) {
        if (!config.isConfigured) {
            failure = FailureInfo(
                FailureKind.NOT_CONFIGURED,
                "还没填 API Key，先打开右上角设置填一下",
            )
            phase = AnalysisPhase.FAILED
            return
        }

        job?.cancel()
        phase = AnalysisPhase.ANALYZING
        failure = null
        hint = null
        rawResponse = null
        lastPhotoInfo = "$photo"
        timings = emptyList()

        job = scope.launch {
            val outcome = engine.analyze(
                photo = photo,
                intent = intent,
                perception = currentPerception(),
                config = config,
            )
            when (outcome) {
                is HintOutcome.Success -> {
                    hint = outcome.hint
                    timings = outcome.timings
                    rawResponse = outcome.rawResponse
                    lastPrompt = outcome.prompt
                    trend.add(outcome.totalMs)
                    phase = AnalysisPhase.DONE
                    platformLog(
                        TAG,
                        "完成：总 ${outcome.totalMs}ms（网络 ${outcome.networkMs}ms）" +
                            outcome.timings.joinToString(prefix = " | ") { "${it.stage}=${it.ms}ms" },
                    )
                }

                is HintOutcome.Failure -> {
                    failure = FailureInfo(outcome.kind, outcome.message)
                    timings = outcome.timings
                    lastPrompt = outcome.prompt
                    phase = AnalysisPhase.FAILED
                    platformLog(TAG, "失败[${outcome.kind}]：${outcome.message}")
                }
            }
        }
    }

    fun retryLast(photo: CapturedPhoto?) {
        if (photo != null) analyze(photo)
    }

    fun dismissFailure() {
        failure = null
        if (phase == AnalysisPhase.FAILED) phase = AnalysisPhase.IDLE
    }

    /** 用户主动关掉提示卡。下一次按快门会重新生成。 */
    fun dismissHint() {
        hint = null
        if (phase == AnalysisPhase.DONE) phase = AnalysisPhase.IDLE
    }

    fun openSettings() { showSettings = true }
    fun closeSettings() { showSettings = false }
    fun toggleDebug() { showDebug = !showDebug }

    fun shutdown() {
        job?.cancel()
        llmClient.close()
    }

    private companion object {
        const val TAG = "AppState"
        const val KEY_CONFIG = "llm_config_v1"
        const val KEY_ENDPOINTS = "saved_endpoints_v1"
    }
}

@Composable
fun rememberAppState(): AppState {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        AppState(scope = scope, settings = createSettingsStore())
    }
}
