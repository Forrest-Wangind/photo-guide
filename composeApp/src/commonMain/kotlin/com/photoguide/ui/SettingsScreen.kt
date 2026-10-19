package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoguide.llm.Endpoint
import com.photoguide.llm.LlmConfig
import com.photoguide.llm.ProviderCatalog
import com.photoguide.perception.PerceptionRegistry
import com.photoguide.state.AppState

/**
 * 设置页。
 *
 * 远端大模型完全可配置：provider / baseUrl / model / key / 随机性(temperature) / 是否传图。
 * 默认走 DeepSeek 的视觉模型，但换任何 OpenAI 兼容端点都只需要改这几个字段，
 * 不用改一行代码。
 */
@Composable
fun SettingsScreen(state: AppState) {
    val config = state.config
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // ⭐ 收起输入：撤焦点 + 显式 hide。
    //
    // 为什么不能只靠系统：Compose 里 `clickable` 的兄弟节点**不会**抢焦点，
    // 所以"改完 Base URL 去点端点卡片 / 开关 / 完成"时输入框一直是聚焦态，
    // 系统没有理由收键盘 —— 结果是键盘一直挂着，遮住半屏还盖住"完成"。
    // 清焦点本身会触发系统收键盘，但各家 ROM 行为不一致，所以 hide() 再来一次，
    // 不把正确性押在系统默认行为上。
    fun dismissKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // edge-to-edge 下窗口画到键盘后面，adjustResize 不会自动让位：
            // safeDrawing = 状态栏 + 导航条 + IME，键盘弹出时下方滚动区随之收缩，
            // 聚焦的输入框能被滚进可视区，不再被软键盘盖住
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // ⭐ 顶栏固定，不参与滚动（2026-10-15 改）。
        //
        // 此前"标题 + 完成"是滚动内容的第一个孩子：键盘一弹起就触发 adjustResize +
        // 系统自动滚动，整个页面被顶上去，顶栏连同「完成」一起滚出屏幕上方 ——
        // 用户想离开设置页得先把页面滑回顶部，纯属自己给自己挖坑。
        // 现在拆成「固定顶栏 + 独立滚动区」，标题与「完成」永远在视口内。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                // 空白处（如标题右边）点一下也收键盘，与下方内容区语义一致
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { dismissKeyboard() })
                },
        ) {
            Text(
                text = "设置",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        dismissKeyboard()
                        state.closeSettings()
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text("完成", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        // 分隔线让"这里是固定区"一眼可辨，滚动内容正好贴在它下面
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        // 只有这一块滚动：weight(1f) 吃掉顶栏之外的全部高度
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // 点空白处也收键盘。子元素（输入框、卡片）会消费掉自己的点击事件，
                // 所以这里只会收到"没被任何人处理"的点击 —— 不会误伤"从 Base URL
                // 直接点到模型名"这种换输入框的连续编辑（那种情况键盘应该留着）。
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { dismissKeyboard() })
                }
                .padding(16.dp),
        ) {

            // -------------------- 模型服务 --------------------
            // ⭐ 这里只有一个概念：**端点列表**。内置两家（DeepSeek / Claude）与用户新建的
            // 都在这一份列表里，点选即生效；下方输入框直接编辑当前选中的那条，改一次落盘一次。
            //
            // 历史上这里是"预设卡 + 已保存端点列表 + 实时 config + ＋保存按钮"四件套，
            // 2026-10-15 一天内连出两个 bug（改了 URL 按完成没生效 / 改完切走再切回被重置）——
            // 根因都是同一份三元组被存了两遍。现在只剩一份，"选中了 A 但在用 B"无从发生。
            SectionTitle("模型服务")

            Text(
                text = "点任意端点即切换生效；下方输入框直接编辑当前端点，改一个字就存一次，" +
                    "不存在「改了没保存」。「完成」只是返回，配置早就是即时保存的。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            val active = state.activeEndpoint
            state.endpoints.forEach { ep ->
                EndpointRow(
                    endpoint = ep,
                    selected = ep.id == active.id,
                    canDelete = state.endpoints.size > 1,
                    onSelect = {
                        dismissKeyboard()
                        state.selectEndpoint(ep.id)
                    },
                    onDelete = {
                        dismissKeyboard()
                        state.removeEndpoint(ep.id)
                    },
                    onReset = {
                        dismissKeyboard()
                        state.resetEndpointToDefaults(ep.id)
                    },
                )
            }
            if (state.endpoints.size == 1) {
                Text(
                    text = "最后一个端点不能删除——没有端点就无法发请求了。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Text(
                text = "＋ 新增端点",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .clickable {
                        dismissKeyboard()
                        state.addEndpoint()
                    }
                    .padding(10.dp),
            )

            // 被删掉的内置端点：一键恢复（幂等，已在列表里时只做选中）
            val missingBuiltins = ProviderCatalog.builtinPresets
                .filter { p -> state.endpoints.none { it.id == p.id } }
            if (missingBuiltins.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    missingBuiltins.forEach { p ->
                        Text(
                            text = "＋ 恢复${p.displayName}官方端点",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                                .clickable {
                                    dismissKeyboard()
                                    state.restoreBuiltinEndpoint(p.id)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // 当前端点详情：明写"下面这组值属于哪个端点"，不再让用户猜
            Spacer(Modifier.height(12.dp))

            Text(
                text = "当前端点：${active.displayName}",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "协议 ${active.protocolLabel} · temperature 上限 ${active.preset.maxTemperature}" +
                    if (active.preset.supportsImageDetail) " · 支持 detail=low" else " · 不发 detail=low",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )

            // 协议族只在自定义端点上可选：内置端点就是官方协议本身，改了名不副实
            if (!active.builtin) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderCatalog.customProtocols.forEach { p ->
                        val on = active.protocol == p.id
                        Text(
                            text = if (p.id == ProviderCatalog.CLAUDE) "Claude 兼容" else "OpenAI 兼容（通用）",
                            color = if (on) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                )
                                .clickable {
                                    dismissKeyboard()
                                    state.setActiveProtocol(p.id)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                Text(
                    text = "中转站跑 Claude 模型时选「Claude 兼容」：随机性自动截到 1.0、不发 detail=low，" +
                        "免得被网关当非法参数拒掉。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            FieldLabel("Base URL")
            OutlinedTextField(
                value = active.baseUrl,
                // 直接写回当前端点：改一个字存一次，没有"草稿 → 保存"这一步
                onValueChange = { state.editActiveEndpoint(baseUrl = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // 回车 = 完成：收键盘并释放焦点，不再往输入框里插换行
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
            )

            Spacer(Modifier.height(8.dp))

            FieldLabel("模型名")
            OutlinedTextField(
                value = active.model,
                onValueChange = { state.editActiveEndpoint(model = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("如 deepseek-v4-flash-vision-exp") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
            )
            Text(
                text = "模型名以服务商官方文档为准，可以直接手改。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(8.dp))

            FieldLabel("API Key")
            OutlinedTextField(
                value = active.apiKey,
                onValueChange = { state.editActiveEndpoint(apiKey = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text("sk-…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
            )
            Text(
                text = "每个端点的 Key 各存各的，切端点不会串。只存在本机的 " +
                    "SharedPreferences / NSUserDefaults，不上传到任何第三方。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(14.dp))

            // -------------------- 输入形态 --------------------
            SectionTitle("输入形态")

            SwitchRow(
                title = "上传照片",
                subtitle = "关闭后模型看不到画面，只能给通用建议；开启才能判断审美与构图",
                checked = config.sendImage,
                onCheckedChange = {
                    dismissKeyboard()
                    state.updateConfig(config.copy(sendImage = it))
                },
            )
            SwitchRow(
                title = "低分辨率模式",
                subtitle = "支持的服务商把图下采样到 512×512，更快更省 token（DeepSeek 的 detail=low；Claude 不支持，自动跳过）",
                checked = config.lowDetail,
                onCheckedChange = {
                    dismissKeyboard()
                    state.updateConfig(config.copy(lowDetail = it))
                },
            )

            Spacer(Modifier.height(10.dp))

            SliderRow(
                // 不写"温度"：拍照类产品里"温度"第一反应是色温，这里是模型的采样随机性
                label = "随机性 temperature",
                value = config.temperature.toFloat(),
                // 不要用 String.format：那是 JVM-only，commonMain 里编不过
                valueText = (((config.temperature * 10).toInt()).toDouble() / 10.0).toString(),
                range = 0f..1.5f,
                hint = "越高回答越发散、说法越多样；越低越稳定保守、每次都差不多。拍摄建议 0.5~0.8 最稳。",
                onChange = {
                    dismissKeyboard()
                    state.updateConfig(config.copy(temperature = it.toDouble()))
                },
            )

            SliderRow(
                label = "max_tokens",
                value = config.maxTokens.toFloat(),
                valueText = "${config.maxTokens}",
                // 带思考的模型会把推理 token 也算进输出预算，给足余量
                range = 200f..8192f,
                onChange = {
                    dismissKeyboard()
                    state.updateConfig(config.copy(maxTokens = it.toInt()))
                },
            )

            SliderRow(
                label = "请求超时",
                value = (config.timeoutMs / 1000f),
                valueText = "${config.timeoutMs / 1000} s",
                range = 10f..120f,
                onChange = {
                    dismissKeyboard()
                    state.updateConfig(config.copy(timeoutMs = (it * 1000).toLong()))
                },
            )

            Spacer(Modifier.height(8.dp))

            FieldLabel("额外要求（会拼进 system prompt）")
            OutlinedTextField(
                value = config.extraInstruction,
                onValueChange = { state.updateConfig(config.copy(extraInstruction = it)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                placeholder = { Text("例：她不喜欢被拍正脸，尽量侧脸或背影", fontSize = 12.sp) },
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(14.dp))

            // -------------------- 本地感知 --------------------
            SectionTitle("本地感知（Phase 2）")
            Text(
                text = "Phase 1 使用空实现：不跑任何本地模型，照片直接交给云端多模态模型。" +
                    "Phase 2 接入后只需在这里切换，上层代码不变。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))

            PerceptionRegistry.all().forEach { source ->
                val selected = state.perceptionId == source.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .clickable {
                            dismissKeyboard()
                            state.selectPerception(source.id)
                        }
                        .padding(10.dp),
                ) {
                    Column {
                        Text(source.displayName, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                        Text("id = ${source.id}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = "photo-guide · Phase 1 · 版本 0.1.0",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 端点列表里的一行。
 *
 * 选中态用文字徽标表达（不靠背景色猜）；内置端点给"恢复默认"，自定义端点给"删除"。
 * 三元组没填全时**明说还不能用** —— 否则用户会以为"点了怎么没反应"。
 */
@Composable
private fun EndpointRow(
    endpoint: Endpoint,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(onClick = onSelect)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = endpoint.displayName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildString {
                    append(endpoint.baseUrl.ifBlank { "（未填 Base URL）" })
                    append(" · ")
                    append(endpoint.model.ifBlank { "（未填模型名）" })
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            if (!endpoint.isUsable) {
                Text(
                    text = "还不能用：Base URL / 模型名 / API Key 没填全",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                )
            }
        }
        if (endpoint.isModifiedFromDefault) {
            Text(
                text = "恢复默认",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        // 内置端点也可以删（2026-10-15 用户拍板）；只剩最后一个时置灰并另有文字说明
        Text(
            text = "删除",
            color = if (canDelete) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = canDelete, onClick = onDelete)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (selected) EffectiveBadge()
    }
}

/** "生效中"小徽标：选中态的文字化表达。 */
@Composable
private fun EffectiveBadge() {
    Text(
        text = "生效中",
        color = MaterialTheme.colorScheme.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    hint: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(valueText, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
        if (hint != null) {
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
        }
    }
}
