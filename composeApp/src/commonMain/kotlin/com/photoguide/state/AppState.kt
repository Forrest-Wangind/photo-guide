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
import com.photoguide.llm.Endpoint
import com.photoguide.llm.FailureKind
import com.photoguide.llm.LlmClient
import com.photoguide.llm.LlmConfig
import com.photoguide.llm.ProviderCatalog
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

    // ⚠️ 声明顺序有讲究：endpoints 必须在 config 之前。
    // Kotlin 按声明顺序初始化属性，而 loadConfig() 要拿 endpoints 校验 activeEndpointId。

    /**
     * 端点列表 —— 模型服务层**唯一**的真相源。
     *
     * 内置两条（DeepSeek / Claude）+ 用户新建的都在这里；输入框直接编辑当前选中的一条，
     * 改一次落盘一次。不再有"预设槽位快照 + 已保存端点列表 + config 三元组"三份并存——
     * 那份冗余正是 2026-10-15 两个 bug（改了没保存 / 切走再切回丢手改）的来源。
     */
    var endpoints by mutableStateOf(loadEndpoints())
        private set

    var config by mutableStateOf(loadConfig())
        private set

    /** 当前生效端点。列表保证非空（内置端点会被补齐），这里的 `first()` 只是兜底。 */
    val activeEndpoint: Endpoint
        get() = endpoints.firstOrNull { it.id == config.activeEndpointId } ?: endpoints.first()

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
        val stored = settings.getString(KEY_CONFIG)
            ?: return LlmConfig(activeEndpointId = endpoints.first().id)

        // 老格式（2026-10-15 之前）把三元组直接存在 config 里，新格式只存 activeEndpointId。
        // 用 providerId 是否存在判定格式——新 LlmConfig 没有这个字段，老 JSON 里才有。
        val legacy = runCatching { json.decodeFromString(LegacyLlmConfig.serializer(), stored) }.getOrNull()
        val isLegacyFormat = legacy?.providerId != null

        val decoded = runCatching { json.decodeFromString(LlmConfig.serializer(), stored) }
            .getOrElse {
                platformLog(TAG, "配置反序列化失败，回退默认值：${it.message}")
                return LlmConfig(activeEndpointId = endpoints.first().id)
            }

        var fixed = decoded.copy(
            // 老格式里的 activeEndpointId 只是默认值（库里根本没这字段），必须忽略，
            // 否则"用户正在用某个自定义端点"这个状态会在升级那一刻丢给 DeepSeek
            activeEndpointId = resolveActiveEndpointId(
                newId = if (isLegacyFormat) null else decoded.activeEndpointId,
                legacy = legacy,
            ),
        )

        // 迁移：旧版默认 maxTokens=900 会被带思考的模型整个吃在推理阶段，
        // 正文为空（finish_reason=length）。低于下限的存量配置提升到新版默认并落盘。
        if (fixed.maxTokens < LlmConfig.MIN_VALID_MAX_TOKENS) {
            platformLog(TAG, "迁移：maxTokens=${fixed.maxTokens} 过小，提升为 ${LlmConfig.DEFAULT_MAX_TOKENS}")
            fixed = fixed.copy(maxTokens = LlmConfig.DEFAULT_MAX_TOKENS)
        }

        // 校验/迁移结果立刻落盘：老格式被重写成新格式，下一次启动直接命中新格式
        settings.putString(KEY_CONFIG, json.encodeToString(LlmConfig.serializer(), fixed))
        if (isLegacyFormat) {
            platformLog(TAG, "配置已迁移到端点模型：activeEndpointId=${fixed.activeEndpointId}")
        }
        return fixed
    }

    /**
     * 解析"当前该用哪个端点"。
     *
     * 三级回落，一级都不能少 —— 升级路径上这三种存量数据都存在：
     *   1. 新格式：直接给了 id；
     *   2. 老格式的 providerId 正好与端点 id 同名（deepseek / claude）；
     *   3. 老格式的其他 providerId（`saved:url|model`、`openai-compatible`）：
     *      按三元组匹配迁移后的端点 —— 这正是"升级前正在用某个自定义端点"的场景。
     */
    private fun resolveActiveEndpointId(newId: String?, legacy: LegacyLlmConfig?): String {
        if (newId != null && endpoints.any { it.id == newId }) return newId

        val pid = legacy?.providerId
        if (pid != null && endpoints.any { it.id == pid }) return pid

        val lUrl = legacy?.baseUrl
        val lModel = legacy?.model
        if (!lUrl.isNullOrBlank() && !lModel.isNullOrBlank()) {
            endpoints.firstOrNull { it.baseUrl == lUrl && it.model == lModel }?.let { return it.id }
        }
        return endpoints.first().id
    }

    fun updateConfig(new: LlmConfig) {
        config = new
        settings.putString(KEY_CONFIG, json.encodeToString(LlmConfig.serializer(), new))
    }

    // ------------------------------------------------ 端点列表（唯一真相源）

    // ⚠️ 序列化器必须在方法内现场创建，不能做成类成员属性：
    // loadEndpoints() 在 `var endpoints by mutableStateOf(...)` 的初始化阶段就被调用，
    // 若序列化器声明在类体更靠后的位置，那一刻它还是 null，
    // decodeFromString 收到 null 直接抛异常 → 列表每次启动都被打回空，
    // 表现为"保存过的端点重启就丢"（2026-10-15 实锤，logcat 可见 parameter deserializer is null）
    private fun loadEndpoints(): List<Endpoint> {
        val stored = settings.getString(KEY_ENDPOINTS)
        if (stored != null) {
            val decoded = runCatching { json.decodeFromString(ListSerializer(Endpoint.serializer()), stored) }
                .getOrElse {
                    // 绝不静默失败：列表坏了重建内置端点，也要在 logcat 留下原因
                    platformLog(TAG, "端点列表反序列化失败，重建内置端点：${it.message}")
                    emptyList()
                }
            return ensureBuiltinEndpoints(decoded)
        }
        // 没有新格式数据 → 从旧格式（预设槽位快照 + 已保存端点 + config 三元组）迁移
        return migrateLegacyEndpoints()
    }

    /**
     * 内置端点**可删除**（2026-10-15 用户拍板：列表完全由用户做主），
     * 且删除必须跨启动生效——所以这里**不做"缺了就补"**，只在列表为空时
     * （首次启动 / 数据损坏）种入出厂内置端点，保证永不出现
     * "列表为空、没有端点可选"这种不可用状态。至少保留一条的约束在 removeEndpoint 里。
     * 手动恢复入口在设置页「＋ 新增端点」下方（见 restoreBuiltinEndpoint）。
     */
    private fun ensureBuiltinEndpoints(list: List<Endpoint>): List<Endpoint> {
        val builtinIds = ProviderCatalog.builtinPresets.map { it.id }.toSet()
        // 归一化：id 是内置的，就一定算 builtin，且协议族 = 自身（防止手改过的存量数据带偏）
        val normalized = list.map { ep ->
            if (ep.id in builtinIds) ep.copy(builtin = true, protocol = ep.id) else ep
        }
        if (normalized.isNotEmpty()) return normalized

        val seeded = ProviderCatalog.builtinPresets.map { preset ->
            Endpoint(
                id = preset.id,
                baseUrl = preset.baseUrl,
                model = preset.defaultModel,
                protocol = preset.id,
                builtin = true,
            )
        }
        platformLog(TAG, "端点列表为空，种入内置端点：${seeded.joinToString { it.id }}")
        persistEndpoints(seeded)
        return seeded
    }

    private fun persistEndpoints(list: List<Endpoint>) {
        settings.putString(KEY_ENDPOINTS, json.encodeToString(ListSerializer(Endpoint.serializer()), list))
    }

    /**
     * 把旧格式（2026-10-15 之前）的数据合成一份端点列表。
     *
     * 三种来源一条都不能丢：
     *   1. 内置两家：优先用该槽位快照，其次用"老 config 里正在用的那套"，最后才出厂默认；
     *   2. `saved_endpoints_v1` 里的自定义端点；
     *   3. 老 config 里正在用、但既不是内置也没进过列表的自定义端点
     *      （旧版 UI 靠"未保存的自定义端点"提示兜着的那种）。
     */
    private fun migrateLegacyEndpoints(): List<Endpoint> {
        val legacyConfig = settings.getString(KEY_CONFIG)?.let { raw ->
            runCatching { json.decodeFromString(LegacyLlmConfig.serializer(), raw) }.getOrNull()
        }
        val legacySaved = settings.getString(KEY_ENDPOINTS_LEGACY)?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(LegacySavedEndpoint.serializer()), raw) }.getOrNull()
        }.orEmpty()
        val legacyProfiles = settings.getString(KEY_PROFILES_LEGACY)?.let { raw ->
            runCatching {
                json.decodeFromString(
                    MapSerializer(String.serializer(), LegacyProviderProfile.serializer()),
                    raw,
                )
            }.getOrNull()
        }.orEmpty()

        val builtins = ProviderCatalog.builtinPresets.map { preset ->
            val profile = legacyProfiles[preset.id]
            val active = legacyConfig?.takeIf { it.providerId == preset.id }
            Endpoint(
                id = preset.id,
                baseUrl = profile?.baseUrl?.ifBlank { null }
                    ?: active?.baseUrl?.ifBlank { null }
                    ?: preset.baseUrl,
                model = profile?.model?.ifBlank { null }
                    ?: active?.model?.ifBlank { null }
                    ?: preset.defaultModel,
                apiKey = profile?.apiKey?.ifBlank { null } ?: active?.apiKey.orEmpty(),
                protocol = preset.id,
                builtin = true,
            )
        }

        val taken = builtins.map { it.id }.toMutableList()
        val customs = legacySaved.map { ep ->
            val id = Endpoint.newCustomId(taken)
            taken += id
            Endpoint(id = id, baseUrl = ep.baseUrl, model = ep.model, apiKey = ep.apiKey)
        }

        // 老 config 正在用、却没进过列表的自定义端点：补一条，否则升级那一刻就"换家"了
        val lUrl = legacyConfig?.baseUrl
        val lModel = legacyConfig?.model
        val carried: List<Endpoint> = if (
            legacyConfig != null &&
            !lUrl.isNullOrBlank() && !lModel.isNullOrBlank() &&
            legacyConfig.providerId !in ProviderCatalog.presets.map { it.id } &&
            (builtins + customs).none { it.baseUrl == lUrl && it.model == lModel }
        ) {
            val id = Endpoint.newCustomId(taken)
            taken += id
            listOf(Endpoint(id = id, baseUrl = lUrl, model = lModel, apiKey = legacyConfig.apiKey.orEmpty()))
        } else {
            emptyList()
        }

        val merged = builtins + customs + carried
        platformLog(
            TAG,
            "端点列表迁移：内置 ${builtins.size} + 自定义 ${customs.size} + 收编在用端点 ${carried.size}" +
                "（迁移前 providerId=${legacyConfig?.providerId ?: "无"}）",
        )
        persistEndpoints(merged)
        return merged
    }

    // ------------------------------------------------ 模型服务（唯一入口）
    //
    // 全部操作都只对着 endpoints 这一个列表做，不再有"切走前快照、切回来还原"这类补偿逻辑：
    // 值本身就存在端点上，不存在"两份拷贝谁覆盖谁"的问题。

    /**
     * 切换当前端点。**"换用哪家"的唯一入口。**
     *
     * 幂等：已选中的再点一次直接返回（避免把刚填好的值原地重置那种老毛病）。
     */
    fun selectEndpoint(id: String) {
        if (config.activeEndpointId == id) return
        if (endpoints.none { it.id == id }) {
            // 不静默：请求切到不存在的端点不是用户的错，但必须留痕
            platformLog(TAG, "忽略切换请求：端点不存在（$id）")
            return
        }
        updateConfig(config.copy(activeEndpointId = id))
        platformLog(TAG, "当前端点 → ${activeEndpoint.displayName}（${activeEndpoint.chatCompletionsUrl}）")
    }

    /**
     * 编辑**当前端点**的三元组，改一次落一次盘。
     *
     * 没有"草稿 + 保存按钮"两段式 —— 那正是"改了 URL、按完成却没生效"的来源。
     * 传 null 表示该字段不动（三个输入框各自只提交自己那一项）。
     */
    fun editActiveEndpoint(baseUrl: String? = null, model: String? = null, apiKey: String? = null) {
        val id = activeEndpoint.id
        val next = endpoints.map { ep ->
            if (ep.id != id) ep
            else ep.copy(
                baseUrl = baseUrl ?: ep.baseUrl,
                model = model ?: ep.model,
                apiKey = apiKey ?: ep.apiKey,
            )
        }
        endpoints = next
        persistEndpoints(next)
    }

    /** 新建一个自定义端点并立即选中 —— 用户接着在下方三个输入框里填就行。 */
    fun addEndpoint() {
        val ep = Endpoint(
            id = Endpoint.newCustomId(endpoints.map { it.id }),
            protocol = ProviderCatalog.CUSTOM,
        )
        val next = endpoints + ep
        endpoints = next
        persistEndpoints(next)
        updateConfig(config.copy(activeEndpointId = ep.id))
        platformLog(TAG, "新建端点 ${ep.id}，已切换为当前端点")
    }

    /**
     * 改自定义端点的协议族（Claude 兼容 / 通用 OpenAI 兼容）。
     * 内置端点的协议族固定为自身，不允许改 —— 改了它就不再是那家官方协议了。
     */
    fun setActiveProtocol(protocol: String) {
        val active = activeEndpoint
        if (active.builtin) return
        val next = endpoints.map { if (it.id == active.id) it.copy(protocol = protocol) else it }
        endpoints = next
        persistEndpoints(next)
        platformLog(TAG, "端点 ${active.displayName} 协议族 → ${next.first { it.id == active.id }.protocolLabel}")
    }

    /**
     * 删除端点。内置端点**也可以删**（2026-10-15 用户拍板），唯一硬约束是
     * **至少保留 1 条**——没有端点就无法发请求，这个状态不允许存在。
     *
     * 删掉的正是当前端点时回落到列表首项。**不静默**：日志一行，UI 选中态立刻可见。
     * 删掉的内置端点可从设置页「＋ 新增端点」下方一键恢复（restoreBuiltinEndpoint）。
     */
    fun removeEndpoint(id: String) {
        val target = endpoints.firstOrNull { it.id == id } ?: return
        if (endpoints.size <= 1) {
            platformLog(TAG, "拒绝删除 ${target.id}：至少要保留一个端点")
            return
        }
        val next = endpoints - target
        endpoints = next
        persistEndpoints(next)
        if (config.activeEndpointId == id) {
            updateConfig(config.copy(activeEndpointId = next.first().id))
        }
        platformLog(
            TAG,
            "已删除端点 ${target.id}（${target.displayName}${if (target.builtin) "，内置" else ""}）",
        )
    }

    /**
     * 恢复一个内置官方端点并选中（设置页「＋ 新增端点」下方的快捷项）。
     *
     * 已在列表里时只做选中、不重复添加（幂等）。Key 为空，需要用户重新填。
     */
    fun restoreBuiltinEndpoint(presetId: String) {
        val preset = ProviderCatalog.builtinPresets.firstOrNull { it.id == presetId } ?: return
        if (endpoints.any { it.id == presetId }) {
            selectEndpoint(presetId)
            return
        }
        val ep = Endpoint(
            id = preset.id,
            baseUrl = preset.baseUrl,
            model = preset.defaultModel,
            protocol = preset.id,
            builtin = true,
        )
        val next = endpoints + ep
        endpoints = next
        persistEndpoints(next)
        updateConfig(config.copy(activeEndpointId = ep.id))
        platformLog(TAG, "已恢复内置端点 ${preset.displayName} 并选中（API Key 需重新填写）")
    }

    /**
     * 把内置端点还原为出厂值。
     *
     * **不动 apiKey**：用户只想去掉手改的 URL/模型名，顺手抹掉凭证是过度删除。
     */
    fun resetEndpointToDefaults(id: String) {
        val target = endpoints.firstOrNull { it.id == id } ?: return
        if (!target.builtin) return
        val preset = ProviderCatalog.byId(id)
        val next = endpoints.map {
            if (it.id == id) it.copy(baseUrl = preset.baseUrl, model = preset.defaultModel) else it
        }
        endpoints = next
        persistEndpoints(next)
        platformLog(TAG, "端点 ${preset.displayName} 已还原出厂值（Base URL = ${preset.baseUrl}，Key 保留）")
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
        model = activeEndpoint.model,
        maxTokens = config.maxTokens,
        preview = true,
    )

    // ---------------------------------------------------------------- 分析

    fun analyze(photo: CapturedPhoto) {
        val endpoint = activeEndpoint
        if (!endpoint.isUsable) {
            failure = FailureInfo(
                FailureKind.NOT_CONFIGURED,
                // 说清是**哪个**端点缺东西：多端点之后"还没填 Key"太含糊
                "端点「${endpoint.displayName}」还没填全（Base URL / 模型名 / API Key），" +
                    "点右上角设置补一下",
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
                endpoint = endpoint,
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

        /** 端点列表（唯一真相源）。 */
        const val KEY_ENDPOINTS = "endpoints_v1"

        // 下面两个是**只读**的旧格式 key：仅用于一次性迁移，不再写入。
        // 刻意不删旧键 —— 万一迁移逻辑有问题，用户的数据还在，可以回滚排查。
        const val KEY_ENDPOINTS_LEGACY = "saved_endpoints_v1"
        const val KEY_PROFILES_LEGACY = "provider_profiles_v1"
    }
}

// ---------------------------------------------------------------------------
// 旧格式 DTO —— 只用于一次性迁移，App 永远不写这些结构
// ---------------------------------------------------------------------------

/** 2026-10-15 之前的 LlmConfig：三元组直接存在 config 里，providerId 指向预设或 `saved:*`。 */
@Serializable
private data class LegacyLlmConfig(
    val providerId: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
)

/** 2026-10-15 之前的"已保存端点"。 */
@Serializable
private data class LegacySavedEndpoint(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
)

/** 2026-10-15 之前的"预设槽位快照"（只在同一天内存在过，仍有用户带着它升级）。 */
@Serializable
private data class LegacyProviderProfile(
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
)

@Composable
fun rememberAppState(): AppState {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        AppState(scope = scope, settings = createSettingsStore())
    }
}
