package com.photoguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoguide.capture.CameraState
import com.photoguide.capture.CameraViewfinder
import com.photoguide.capture.CapturedPhoto
import com.photoguide.capture.rememberCaptureController
import com.photoguide.decision.ScenePreset
import com.photoguide.decision.ShootingIntent
import com.photoguide.state.AnalysisPhase
import com.photoguide.state.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(state: AppState) {
    val controller = rememberCaptureController()
    val cameraState by controller.state
    val canCapture by controller.canCapture
    val focusManager = LocalFocusManager.current
    var lastPhoto by remember { mutableStateOf<CapturedPhoto?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    // 输入框是否持有焦点。用来决定"点空白"那一层要不要存在——
    // 键盘弹着的时候必须接住空白点击，否则用户只能去按键盘上的"完成"才能收键盘。
    var inputFocused by remember { mutableStateOf(false) }

    // 底部控制区的实测高度（px，含导航栏/软键盘 inset）。
    // 提示卡靠它上浮——控制区高度是变的（芯片换行、输入框两行、键盘弹起），
    // 用固定 dp 迟早会盖住卡片。
    var bottomBarPx by remember { mutableStateOf(0) }
    val bottomBarHeightDp = with(LocalDensity.current) { bottomBarPx.toDp() }

    Box(modifier = Modifier.fillMaxSize()) {

        // ---------- 取景器（各平台原生实现） ----------
        // 刻意不消费任何 insets：预览要满屏出血，只有控件层跟随键盘/系统栏上浮
        CameraViewfinder(controller = controller, modifier = Modifier.fillMaxSize())

        // ---------- 提示卡状态 ----------
        // 提前声明：下面的空白点击层需要它
        val hint = state.hint
        val hintVisible = hint != null && state.phase != AnalysisPhase.ANALYZING
        // 展开态受控：点空白处时父层要知道现在是"收起"还是"关闭"
        var hintExpanded by remember(hint) { mutableStateOf(false) }

        // ---------- 点空白处：收起展开态 / 关掉调试面板 / 收软键盘 ----------
        // ⚠️ 提示内容的生命周期**只由拍摄结果驱动**：显示到下一次拍摄，
        // 由新的分析结果替换或清空。这里绝不 dismissHint ——
        // 点空白、开关调试面板都与"提示卡还该不该在"无关，混在一起会互相误伤。
        // 想手动关掉提示卡，用卡片自身的 ✕（显式动作，不会误触）。
        //
        // ⚠️ 图层顺序在这里是**语义的一部分**：Compose 的命中测试取最上层节点，
        // 这一层必须最早声明（画在最底下），否则会盖住后声明的顶栏按钮、
        // 底栏控件，把它们的点击吃掉 —— 曾因此出现"点调试按钮反而关掉了提示卡"。
        // 声明在取景器之后、一切可点控件之前：只有真正的空白区落到这里。
        //
        // 只有"确实有该被收掉的东西"时才铺这一层：
        // 展开的提示卡、开着的调试面板、或正在输入的软键盘。
        if (hintExpanded || state.showDebug || inputFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        // 收起只是还原成精简态，内容仍在；几件事互不冲突，一次点完
                        hintExpanded = false
                        if (state.showDebug) state.toggleDebug()
                        // 点取景器空白就收键盘，不用特地去找键盘上的"完成"
                        focusManager.clearFocus()
                    },
            )
        }

        // ---------- 顶栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "拍摄指导",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Text(
                    text = cameraStateLabel(cameraState),
                    color = statusColor(cameraState),
                    fontSize = 12.sp,
                )
            }
            SmallAction(label = "调试", onClick = state::toggleDebug)
            Spacer(Modifier.width(8.dp))
            SmallAction(label = "设置", onClick = state::openSettings)
        }

        // ---------- 失败/降级横幅（绝不静默失败） ----------
        val failure = state.failure
        val bannerText = failure?.message ?: lastError
        if (bannerText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 60.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.92f))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "⚠ ${failure?.kind?.name ?: "CAMERA"}",
                        color = MaterialTheme.colorScheme.onError,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = bannerText,
                        color = MaterialTheme.colorScheme.onError,
                        fontSize = 13.sp,
                    )
                    if (failure?.kind?.isRetryable == true) {
                        Text(
                            text = "点这里重试",
                            color = MaterialTheme.colorScheme.onError,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable {
                                    lastPhoto?.let(state::analyze)
                                    lastError = null
                                },
                        )
                    }
                }
            }
        }

        // ---------- 结果卡片 ----------
        if (hintVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 浮在底部控制区**之上**，而不是猜一个魔法数字。
                    // 控制区高度会随芯片换行、输入框行数、手势条、软键盘变化，
                    // 写死 190dp 必然在某些状态下被盖住 —— 所以直接用实测高度。
                    // 实测高度里已含 inset（导航栏/键盘），所以这里不再单独吃 safeDrawing，
                    // 否则 inset 会被算两次，键盘弹起时多顶出一截空白。
                    .padding(
                        bottom = bottomBarHeightDp + 8.dp,
                        start = 12.dp,
                        end = 12.dp,
                    )
                    .fillMaxWidth()
            ) {
                HintCard(
                    hint = hint,
                    expanded = hintExpanded,
                    onExpandedChange = { hintExpanded = it },
                    onDismiss = state::dismissHint,
                )
            }
        }

        // ---------- 底部控制区 ----------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 量**整条**控制区（含 inset 内边距），供提示卡定位；
                // 放在 windowInsetsPadding 之前才是含 inset 的总高度
                .onSizeChanged { bottomBarPx = it.height }
                // 手势条 / 三键导航 / 软键盘：取底部最大 inset，整块控制区上浮而不是被盖住
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .background(Color(0xCC0E0E11))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 预设是**可选**快捷项：这一项代表"不限定场景，由输入框决定"
                item {
                    FilterChip(
                        selected = state.intent.preset == null,
                        onClick = { state.updateIntent(state.intent.copy(preset = null)) },
                        label = { Text("自由描述", fontSize = 12.sp) },
                    )
                }
                items(ScenePreset.entries.size) { i ->
                    val preset = ScenePreset.entries[i]
                    val selected = state.intent.preset == preset
                    FilterChip(
                        selected = selected,
                        // 再点一次已选中的预设 = 取消，避免与输入框里的描述互相打架
                        onClick = {
                            state.updateIntent(
                                state.intent.copy(preset = if (selected) null else preset),
                            )
                        },
                        label = { Text(preset.displayName, fontSize = 12.sp) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.intent.freeText,
                onValueChange = { state.updateIntent(ShootingIntent(state.intent.preset, it)) },
                // 记录焦点：键盘弹着时"点空白"那层才需要存在（见上面的空白点击层）
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { inputFocused = it.isFocused },
                placeholder = {
                    Text(
                        text = if (state.intent.preset == null) {
                            "描述你想怎么拍：例如「傍晚逆光的侧脸剪影」"
                        } else {
                            "补充一句：想要什么感觉？"
                        },
                        fontSize = 13.sp,
                    )
                },
                maxLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium,
                // 回车 = 完成：收起键盘并释放焦点，不再往文本框里插换行
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() },
                ),
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CaptureButton(
                    enabled = canCapture && state.phase != AnalysisPhase.ANALYZING,
                    analyzing = state.phase == AnalysisPhase.ANALYZING,
                    onClick = {
                        lastError = null
                        state.dismissFailure()
                        controller.capture(
                            onResult = { photo ->
                                lastPhoto = photo
                                state.analyze(photo)
                            },
                            onError = { msg -> lastError = msg },
                        )
                    },
                )
            }
        }

        // ---------- 性能 / 调试浮层 ----------
        // 位置：顶栏「调试」按钮**正下方**（右上角对齐），而不是垂直居中。
        // 浮层贴着它的入口按钮，视线不用在屏幕上来回找；也让出屏幕中段的取景区域。
        // top 偏移 = 状态栏 + 顶栏实际高度（按钮行 + 上下 12dp 内边距），
        // 面板自身还有 10dp 内缩，所以视觉上正好落在按钮下沿。
        if (state.showDebug) {
            DebugPanel(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 64.dp, end = 6.dp),
            )
        }
    }
}

@Composable
private fun CaptureButton(enabled: Boolean, analyzing: Boolean, onClick: () -> Unit) {
    val ring = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(3.dp, ring, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (analyzing) 26.dp else 56.dp)
                .clip(CircleShape)
                .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            contentAlignment = Alignment.Center,
        ) {
            if (analyzing) {
                Text("…", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

private fun cameraStateLabel(s: CameraState): String = when (s) {
    is CameraState.Starting -> "相机启动中…"
    is CameraState.Ready -> "相机就绪"
    is CameraState.Failed -> if (s.permissionDenied) "未授予相机权限" else "相机不可用"
}

private fun statusColor(s: CameraState): Color = when (s) {
    is CameraState.Ready -> Color(0xFF7BD88F)
    is CameraState.Starting -> Color(0xFFE0C060)
    is CameraState.Failed -> Color(0xFFFF6B6B)
}
