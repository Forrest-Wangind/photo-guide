package com.photoguide.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoguide.capture.CameraState
import com.photoguide.capture.CameraViewfinder
import com.photoguide.capture.CapturedPhoto
import com.photoguide.capture.rememberCaptureController
import com.photoguide.decision.ScenePreset
import com.photoguide.decision.ShootingIntent
import com.photoguide.guide.CompositionGuide
import com.photoguide.guide.GuideRecommendation
import com.photoguide.platform.excludeFromSystemGestures
import com.photoguide.state.AnalysisPhase
import com.photoguide.state.AppState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(state: AppState) {
    val controller = rememberCaptureController()
    val cameraState by controller.state
    val canCapture by controller.canCapture
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var lastPhoto by remember { mutableStateOf<CapturedPhoto?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    // 输入框是否持有焦点。用来决定"点空白"那一层要不要存在——
    // 键盘弹着的时候必须接住空白点击，否则用户只能去按键盘上的"完成"才能收键盘。
    var inputFocused by remember { mutableStateOf(false) }

    // 底部控制区的实测尺寸（px，含导航栏/软键盘 inset）。
    // 提示卡靠它上浮——控制区高度是变的（芯片换行、输入框两行、键盘弹起），
    // 用固定 dp 迟早会盖住卡片。
    var bottomBarPx by remember { mutableStateOf(0) }
    // 意图面板（可折叠那部分）的实测高度/宽度：高度用来算"收起后按钮上方空出多少"，
    // 宽度是抽屉平移的全量程
    var intentPanelPx by remember { mutableStateOf(0) }
    var intentPanelWidthPx by remember { mutableFloatStateOf(0f) }

    // 折叠进度：1 = 完全展开，0 = 完全收起。用 Animatable 而不是布尔值，
    // 是因为拖动必须跟手（布尔值只能做两态动画，手指拖到一半会跳）
    val panelFraction = remember { Animatable(if (state.inputPanelCollapsed) 0f else 1f) }
    val uiScope = rememberCoroutineScope()

    // 拖动起点的进度值：吸附判断要用"这次拖动往哪个方向走了多少"，
    // 单看终点位置会把"从收起态拖 300px"误判成"还该收着"
    var dragStartFraction = 1f

    // 唯一的外部驱动入口：点把手、或别处改了持久化状态，都从这里走动画
    LaunchedEffect(state.inputPanelCollapsed) {
        panelFraction.animateTo(
            targetValue = if (state.inputPanelCollapsed) 0f else 1f,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        )
    }

    /** 底部被占用的高度（px）：按钮区始终占位，意图面板只在展开时算进去。 */
    fun reservedBottomPx(): Float =
        (bottomBarPx - intentPanelPx).coerceAtLeast(0) + intentPanelPx * panelFraction.value

    // 参考线选择面板的展开态：纯临时 UI 状态，不落盘（落盘的是"选了哪条线"）
    var guidePickerOpen by remember { mutableStateOf(false) }
    // 提示卡实测高度：参考线面板要叠在卡片**上方**，猜一个 dp 迟早会盖住卡片
    var hintCardPx by remember { mutableStateOf(0) }
    val hintCardHeightDp = with(LocalDensity.current) { hintCardPx.toDp() }

    Box(modifier = Modifier.fillMaxSize()) {

        // ---------- 取景器（各平台原生实现） ----------
        // 刻意不消费任何 insets：预览要满屏出血，只有控件层跟随键盘/系统栏上浮
        CameraViewfinder(controller = controller, modifier = Modifier.fillMaxSize())

        // ---------- 构图参考线 ----------
        // 画在取景器之上、所有可点控件之下：Canvas 不消费触摸，不会挡住快门和按钮。
        // 关闭时直接返回，不构建任何绘制对象（与调试浮层同样的"零开销"约定）。
        GuideOverlay(guide = state.guide, modifier = Modifier.fillMaxSize())

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
        // 展开的提示卡、开着的调试面板、展开的参考线面板、或正在输入的软键盘。
        if (hintExpanded || state.showDebug || inputFocused || guidePickerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        // 收起只是还原成精简态，内容仍在；几件事互不冲突，一次点完
                        hintExpanded = false
                        guidePickerOpen = false
                        if (state.showDebug) state.toggleDebug()
                        // 点取景器空白就收键盘，不用特地去找键盘上的"完成"
                        focusManager.clearFocus()
                    },
            )
        }

        // ---------- 顶栏 ----------
        // 按钮上带出当前生效的档位：自动换线时（选了"美食"→对角线）用户不看面板也知道变了，
        // 否则只能从画面里多出来的几条线去猜"这是不是我刚选的那条"。
        val activeGuide = state.guide
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
            SmallAction(
                label = if (activeGuide == CompositionGuide.NONE) {
                    "构图"
                } else {
                    "构图·${activeGuide.displayName}"
                },
                active = activeGuide != CompositionGuide.NONE,
                onClick = { guidePickerOpen = !guidePickerOpen },
            )
            Spacer(Modifier.width(8.dp))
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
                    //
                    // 用 offset 而不是 padding：面板收起时这块要跟着往下落，
                    // offset 在 layout 阶段读折叠进度，拖动过程中不会把整页重组一遍。
                    .offset {
                        IntOffset(0, -(reservedBottomPx() + 8.dp.toPx()).roundToInt())
                    }
                    .padding(
                        // 收起后左边缘是把手，卡片让开它，否则会压住唯一的入口
                        start = 12.dp + if (state.inputPanelCollapsed) HANDLE_WIDTH else 0.dp,
                        end = 12.dp,
                    )
                    .fillMaxWidth()
                    // 量实测高度：参考线面板要叠在卡片上方，不能靠估
                    .onSizeChanged { hintCardPx = it.height }
            ) {
                HintCard(
                    hint = hint,
                    expanded = hintExpanded,
                    onExpandedChange = { hintExpanded = it },
                    onDismiss = state::dismissHint,
                )
            }
        }

        // ---------- 参考线选择面板 ----------
        // 位置：底部控制区之上；提示卡在时再抬一层，**不遮住刚拿到的建议**。
        // 放在底栏附近而不是顶栏下面：这些 chip 是拇指区操作，且要能一边选一边看取景。
        if (guidePickerOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset {
                        val above = reservedBottomPx() + 8.dp.toPx() +
                            if (hintVisible) hintCardPx + 8.dp.toPx() else 0f
                        IntOffset(0, -above.roundToInt())
                    }
                    .padding(
                        start = 12.dp + if (state.inputPanelCollapsed) HANDLE_WIDTH else 0.dp,
                        end = 12.dp,
                    )
                    .fillMaxWidth(),
            ) {
                GuidePicker(
                    auto = state.guideIsAuto,
                    effective = state.guide,
                    recommendation = state.guideRecommendation,
                    onSelect = state::selectGuide,
                    onSelectAuto = state::useAutoGuide,
                )
            }
        }

        // ---------- 底部控制区 ----------
        // 拆成两块，**职责不同**：
        //   ① 意图面板（场景芯片 + 描述输入）：折叠到左侧。它是"设定"，设完就该让位给取景；
        //   ② 拍摄按钮：任何折叠状态下都留在原地 —— 收起是为了看画面，不是为了不能按快门。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 量**整条**控制区（含 inset 内边距），供提示卡/参考线面板定位
                .onSizeChanged { bottomBarPx = it.height },
        ) {
            CollapsibleIntentPanel(
                state = state,
                fraction = panelFraction,
                collapsed = state.inputPanelCollapsed,
                widthPx = intentPanelWidthPx,
                onMeasured = { w, h ->
                    intentPanelWidthPx = w
                    intentPanelPx = h
                },
                onFocusChange = { inputFocused = it },
                onToggle = { state.updateInputPanelCollapsed(!state.inputPanelCollapsed) },
                onDragStart = {
                    dragStartFraction = panelFraction.value
                    // 拖着面板收起来时键盘还挂着，既挡画面又让布局上下乱跳，先收掉。
                    // ⚠️ 只 clearFocus 不够 —— Compose 移除焦点不保证 IME 收起
                    // （docs/04 §2.3 的老教训），必须显式 hide()
                    keyboard?.hide()
                    focusManager.clearFocus()
                    guidePickerOpen = false
                },
                onDrag = { dx ->
                    if (intentPanelWidthPx > 0f) {
                        val next = (panelFraction.value + dx / intentPanelWidthPx).coerceIn(0f, 1f)
                        // UNDISPATCHED：拖动必须**立刻**改值。默认 launch 会排队到下一帧，
                        // 同一帧里的连续事件都读到旧值 —— 表现为"手指走了、面板没跟上"
                        uiScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            panelFraction.snapTo(next)
                        }
                    }
                },
                onDragEnd = {
                    // 吸附：这次拖动**净移动超过 30% 行程**就切到那一侧；
                    // 不足 30% 视为误触，按当前位置就近吸附（通常就是弹回原状）。
                    // 不能只看终点位置：从收起态向右拖 300px，终点仍在收起侧，
                    // 但用户意图明显是"拉出来" —— 位置判据会把它弹回去。
                    val moved = panelFraction.value - dragStartFraction
                    val target = when {
                        moved <= -0.3f -> 0f
                        moved >= 0.3f -> 1f
                        else -> if (panelFraction.value >= 0.5f) 1f else 0f
                    }
                    uiScope.launch {
                        panelFraction.animateTo(target, tween(240, easing = FastOutSlowInEasing))
                        // 动画结束再落盘：中间过程是连续的，存布尔量没有意义
                        state.updateInputPanelCollapsed(target == 0f)
                    }
                },
            )

            // 拍摄按钮区：背景随折叠淡出。收起后按钮直接浮在画面上，
            // 而不是把一条黑边从"挡画面"降级成"少挡一点"。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 手势条 / 三键导航：取底部最大 inset，按钮上浮而不是被盖住
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    // drawBehind 在绘制阶段读动画值，不触发重组（拖动时这里每帧都在变）
                    .drawBehind {
                        drawRect(PANEL_COLOR.copy(alpha = panelFraction.value))
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
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
private fun SmallAction(label: String, active: Boolean = false, onClick: () -> Unit) {
    // 选中态必须可见：参考线开着却没有入口提示，用户会以为"这功能没反应"
    val background = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = 12.sp,
            // 顶栏右侧是等宽的一条按钮带：标签变长（"构图·黄金螺旋"）时宁可挤标题，
            // 也不能换行把顶栏撑高 —— 撑高会连带把下面的按钮和取景区一起推走
            maxLines = 1,
            softWrap = false,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 意图面板（场景芯片 + 描述输入），可**折叠到左侧**。
 *
 * 折叠的动机是取景：这块常态占掉底部约四分之一屏幕，而"看画面"才是这个 App 的主任务。
 * 折起来时面板整体左移出屏幕，只在左边缘留一条把手；拍摄按钮不在这个组件里，
 * 所以任何折叠状态下它都不会跟着走。
 *
 * ⚠️ 平移读的是 `fraction.value`，但**只在 offset 的 layout lambda 里读** ——
 * 这样拖动 60fps 的过程中只有布局阶段失效，面板里的 LazyRow / TextField 不会每帧重组。
 * 之前把这类动画值直接读在 composition 里，会把整页（含取景器 wrapper）一起重组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsibleIntentPanel(
    state: AppState,
    fraction: Animatable<Float, AnimationVector1D>,
    collapsed: Boolean,
    widthPx: Float,
    onMeasured: (Float, Int) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasured(it.width.toFloat(), it.height) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(-(widthPx * (1f - fraction.value)).roundToInt(), 0) }
                .clipToBounds()
                .background(PANEL_COLOR)
                // 左边多让出把手宽度：不然把手会盖住第一个芯片，看着像被切掉一截
                .padding(start = 12.dp + HANDLE_WIDTH, end = 12.dp, top = 10.dp, bottom = 10.dp)
                // ⚠️ "向左拖收起"的手势必须挂**整块面板**，不能只挂把手：
                // 把手贴着屏幕左边缘，从它出发向左最多只能位移一个把手宽（26dp），
                // 永远够不到吸附阈值 —— 拖不动。挂在面板上，手指从面板中段向左拖
                // 有大半屏的行程。子组件（芯片横滑、输入框选词）在事件分发上优先于父，
                // 在它们上面起手的横滑仍然是滚动/选词，不会误触发折叠。
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            onDrag(amount)
                        },
                    )
                },
        ) {
            Column {
                // 顶部拖拽条：向左拖 = 收起。为什么必须有它 —— "拖面板收起"理论上
                // 可以起手在面板背景上，但背景几乎被满宽的芯片行和输入框盖住
                // （起手在那两块上分别是滚动/选词），剩下的空白只有边缘细条，
                // 用户根本摸不到。顶部中央的 grabber 是 bottom sheet 的通用语言：
                // 看到它就知道能拖，而且它的命中区不含任何子手势，拖动必然生效。
                PanelGrabber(
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    onToggle = onToggle,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

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
                        .onFocusChanged { onFocusChange(it.isFocused) },
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
            }
        }

        CollapseHandle(
            collapsed = collapsed,
            onToggle = onToggle,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

/**
 * 左侧折叠把手。
 *
 * 它是面板唯一没被藏起来的部分，所以必须**自己说明自己能拖**：
 * 收起时做呼吸位移 + 透明度脉动（体积只有 26dp 宽，静止时没人会注意到），
 * 箭头朝右表示"往右拉出来"，展开后箭头翻成朝左表示"往左收回去"。
 *
 * 把手只负责**向右拖出**（收起 → 展开）：向右有整屏行程，完全够用。
 * "向左拖收起"由整块面板承担 —— 把手贴着屏幕左边缘，从它出发向左
 * 最多只能移动一个把手宽，根本拖不出吸附距离（实测踩过）。
 *
 * 手势与面板内部的 LazyRow 不冲突 —— 面板上的 drag 用的是标准事件分发，
 * 子组件（芯片横滑、输入框选词）先消费，起手在它们上面仍是滚动/选词。
 */
@Composable
private fun CollapseHandle(
    collapsed: Boolean,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 只在收起态跑动画：展开时它是个普通按钮，没必要每帧重绘。
    // 条件调用 composable 是允许的（同一分支内结构稳定）。
    val pulse = if (collapsed) rememberHandlePulse() else 0f
    val backgroundAlpha = if (collapsed) 0.55f + 0.45f * pulse else 0.92f
    val shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)

    Box(
        modifier = modifier
            // 向右"探"一下：唯一的动效语言，暗示这东西能被拉出来
            .offset(x = if (collapsed) 4.dp * pulse else 0.dp)
            .width(HANDLE_WIDTH)
            .height(HANDLE_HEIGHT)
            // 把手贴着屏幕左边缘 = Android 侧滑返回的手势区。不豁免的话，
            // 从把手向右拖会被系统当返回手势整个截走（实测一拖就退出 App）
            .excludeFromSystemGestures()
            .clip(shape)
            .background(PANEL_COLOR.copy(alpha = backgroundAlpha))
            // 亮场景下深色半透明会糊掉，加一圈极淡的描边保证边界可见
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                )
            }
            // 点一下也能切换：拖拽对部分用户不直观，且辅助功能只认点击
            .pointerInput(Unit) {
                detectTapGestures { onToggle() }
            },
        contentAlignment = Alignment.Center,
    ) {
        ChevronGlyph(pointRight = collapsed)
    }
}

/**
 * 面板顶部的拖拽条。视觉是一条 40dp 横杠，命中区放大到 56×22dp ——
 * 拖拽目标本来就小，再按视觉尺寸命中会让人反复拖空。
 * 与"收起到左侧"同理是**横向**拖动：向左收、向右展开（松手吸附）。
 */
@Composable
private fun PanelGrabber(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(56.dp)
            .height(22.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { onToggle() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.35f)),
        )
    }
}

/** 把手的箭头。用 Canvas 画而不是字符：字体缺字形时会变成方框，这里必须稳定。 */
@Composable
private fun ChevronGlyph(pointRight: Boolean) {
    Canvas(modifier = Modifier.size(width = 8.dp, height = 13.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (pointRight) {
                moveTo(0f, 0f)
                lineTo(w, h / 2f)
                lineTo(0f, h)
            } else {
                moveTo(w, 0f)
                lineTo(0f, h / 2f)
                lineTo(w, h)
            }
        }
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.9f),
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 1200ms 一次的呼吸值（0→1→0）。只在收起态被调用。 */
@Composable
private fun rememberHandlePulse(): Float {
    val transition = rememberInfiniteTransition(label = "handle-hint")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "handle-pulse",
    )
    return value
}

/** 意图面板 / 拍摄按钮区的底色（与参考线面板同色，展开时两块看起来是一整块）。 */
private val PANEL_COLOR = Color(0xF2141419)

/** 收起后留在屏幕左边缘的把手。宽度就是"体积太小"的那个小。 */
private val HANDLE_WIDTH = 26.dp
private val HANDLE_HEIGHT = 92.dp

/**
 * 参考线选择面板。
 *
 * 一行 chip + 一句话说明。**每一档都得说明"这条线怎么用"** ——
 * 只画线不解释等于把工具丢给不会用的人，而"三分线交点该放眼睛"这件事
 * 恰恰是目标用户（帮女友拍照的人）最缺的知识。
 *
 * 首项是「自动」：按当前意图（场景预设 / 描述里的"对称""斜着摆"）挑一条线。
 * 手动点任何一档都会**锁定**，之后换场景不再自动改 —— 用户明确选过的东西
 * 不该被悄悄覆盖；想回到跟随意图的状态，点第一项即可。
 *
 * 选中后**不自动关闭**：构图工具需要来回比对（三分 vs 黄金），
 * 让用户自己点空白或再点一次「构图」收起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidePicker(
    auto: Boolean,
    effective: CompositionGuide,
    recommendation: GuideRecommendation?,
    onSelect: (CompositionGuide) -> Unit,
    onSelectAuto: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xF2141419))
            .padding(vertical = 8.dp),
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                // 自动档把"替我选了哪一条"写在 chip 上，而不是只写"自动"再让用户去猜
                val autoLabel = if (auto && recommendation != null) {
                    "自动·${recommendation.guide.displayName}"
                } else {
                    "自动"
                }
                FilterChip(
                    selected = auto,
                    onClick = onSelectAuto,
                    label = { Text(autoLabel, fontSize = 12.sp) },
                )
            }
            items(CompositionGuide.entries.size) { i ->
                val guide = CompositionGuide.entries[i]
                FilterChip(
                    selected = !auto && guide == effective,
                    onClick = { onSelect(guide) },
                    label = { Text(guide.displayName, fontSize = 12.sp) },
                )
            }
        }
        Text(
            text = guideNotice(auto, effective, recommendation),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp),
        )
    }
}

/**
 * 面板底部那行说明。
 *
 * 四种状态必须能区分开，否则用户无法判断"线为什么不跟着场景变"：
 *  - 自动 + 有推荐：说清**为什么**是这条线（不能只写"智能推荐"）；
 *  - 自动 + 无推荐：说明当前意图没有对应线，而不是让面板看起来坏了；
 *  - 手动 + 关闭：说明关闭会一直有效；
 *  - 手动 + 某条线：说明已锁定，换场景不会自动改。
 */
private fun guideNotice(
    auto: Boolean,
    effective: CompositionGuide,
    recommendation: GuideRecommendation?,
): String = when {
    !auto && effective == CompositionGuide.NONE ->
        "已关闭：换了场景也不会自动显示，想跟随意图请点「自动」"

    !auto -> "${effective.tip}（已锁定：换场景不会自动改）"

    recommendation != null ->
        "自动 · ${recommendation.guide.displayName} —— ${recommendation.reason}"

    else -> "当前意图没有明确对应的参考线，所以不画。点其它档位可手动锁定"
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
