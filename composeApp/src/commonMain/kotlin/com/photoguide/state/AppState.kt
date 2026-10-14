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
import com.photoguide.metrics.LatencyTrend
import com.photoguide.metrics.StageTiming
import com.photoguide.perception.NoPerception
import com.photoguide.perception.PerceptionSource
import com.photoguide.perception.PerceptionRegistry
import com.photoguide.platform.SettingsStore
import com.photoguide.platform.createSettingsStore
import com.photoguide.platform.platformLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    var showSettings by mutableStateOf(false)
        private set

    var showDebug by mutableStateOf(false)
        private set

    val trend = LatencyTrend()

    private var job: Job? = null

    // ---------------------------------------------------------------- 配置

    private fun loadConfig(): LlmConfig {
        val stored = settings.getString(KEY_CONFIG) ?: return LlmConfig()
        return runCatching { json.decodeFromString(LlmConfig.serializer(), stored) }
            .getOrElse {
                platformLog(TAG, "配置反序列化失败，回退默认值：${it.message}")
                LlmConfig()
            }
    }

    fun updateConfig(new: LlmConfig) {
        config = new
        settings.putString(KEY_CONFIG, json.encodeToString(LlmConfig.serializer(), new))
    }

    // 注意：不能叫 setIntent / setPerceptionId —— 会和 `var xxx` 自动生成的
    // JVM setter 撞签名（Platform declaration clash）。用 update* / select*。
    fun updateIntent(new: ShootingIntent) {
        intent = new
    }

    fun selectPerception(id: String) {
        perceptionId = id
    }

    fun currentPerception(): PerceptionSource = PerceptionRegistry.byId(perceptionId)

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
    }
}

@Composable
fun rememberAppState(): AppState {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        AppState(scope = scope, settings = createSettingsStore())
    }
}
