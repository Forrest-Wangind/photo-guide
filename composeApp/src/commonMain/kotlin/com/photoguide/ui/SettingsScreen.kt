package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    .clickable { state.closeSettings() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text("完成", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        // -------------------- 远端大模型 --------------------
        SectionTitle("远端大模型")

        ProviderCatalog.presets.forEach { preset ->
            val selected = config.providerId == preset.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable {
                        state.updateConfig(
                            config.copy(
                                providerId = preset.id,
                                baseUrl = preset.baseUrl,
                                model = preset.defaultModel.ifBlank { config.model },
                            )
                        )
                    }
                    .padding(10.dp),
            ) {
                Column {
                    Text(
                        text = preset.displayName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(text = preset.note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        FieldLabel("Base URL")
        OutlinedTextField(
            value = config.baseUrl,
            onValueChange = { state.updateConfig(config.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(8.dp))

        FieldLabel("模型名")
        OutlinedTextField(
            value = config.model,
            onValueChange = { state.updateConfig(config.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("如 deepseek-v4-flash-vision-exp") },
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
            value = config.apiKey,
            onValueChange = { state.updateConfig(config.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("sk-…") },
        )
        Text(
            text = "只存在本机的 SharedPreferences / NSUserDefaults，不上传到任何第三方。",
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
            onCheckedChange = { state.updateConfig(config.copy(sendImage = it)) },
        )
        SwitchRow(
            title = "低分辨率模式",
            subtitle = "服务端把图下采样到 512×512，更快更省 token（DeepSeek 的 detail=low）",
            checked = config.lowDetail,
            onCheckedChange = { state.updateConfig(config.copy(lowDetail = it)) },
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
            onChange = { state.updateConfig(config.copy(temperature = it.toDouble())) },
        )

        SliderRow(
            label = "max_tokens",
            value = config.maxTokens.toFloat(),
            valueText = "${config.maxTokens}",
            // 带思考的模型会把推理 token 也算进输出预算，给足余量
            range = 200f..8192f,
            onChange = { state.updateConfig(config.copy(maxTokens = it.toInt())) },
        )

        SliderRow(
            label = "请求超时",
            value = (config.timeoutMs / 1000f),
            valueText = "${config.timeoutMs / 1000} s",
            range = 10f..120f,
            onChange = { state.updateConfig(config.copy(timeoutMs = (it * 1000).toLong())) },
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
                    .clickable { state.selectPerception(source.id) }
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
