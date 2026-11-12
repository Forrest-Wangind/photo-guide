package com.photoguide.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier

/**
 * 相机状态机。
 *
 * 设计原则 3：任何失败/降级都必须能被 UI 读到并显示，绝不允许"黑屏但无提示"。
 */
sealed interface CameraState {
    data object Starting : CameraState
    data object Ready : CameraState

    /** reason 是给人看的；上游必须把它显示出来。 */
    data class Failed(val reason: String, val permissionDenied: Boolean = false) : CameraState
}

/**
 * 镜头朝向。
 *
 * Phase 1 只认这两档：外接镜头、长焦、超广都在范围外。
 * 别把它做成可变列表 —— 朝向是**二值**的，"另一颗"由 [other] 唯一确定，
 * UI 和切换逻辑都依赖这个封闭性。
 */
enum class CameraFacing(val displayName: String) {
    BACK("后置"),
    FRONT("前置"),
    ;

    val other: CameraFacing get() = if (this == BACK) FRONT else BACK
}

interface CaptureController {
    val state: State<CameraState>

    /** 是否已就绪、可以按快门。 */
    val canCapture: State<Boolean>

    /** 当前正在用的镜头。UI 必须显示它，否则用户不知道自己在拍谁。 */
    val facing: State<CameraFacing>

    /**
     * 是否还有另一颗可切。
     *
     * false 时 UI 要把切换按钮标成不可用 —— 单摄设备上点不动比"点了没反应"诚实。
     */
    val canSwitchFacing: State<Boolean>

    /**
     * 非拍照动作（目前是切换镜头）失败时的可见提示。
     *
     * 为什么单独一条通道：切换失败往往**回滚成功**（预览仍是好的），
     * 用 [CameraState.Failed] 会谎报"相机不可用"，而静默回滚等于骗用户
     * "我切了，你没看出来"。所以失败原因走这里，成功/新的尝试时自动清掉。
     */
    val notice: State<String?>

    fun clearNotice()

    /** 切到另一颗镜头。失败必须留下 [notice]，不允许静默不动。 */
    fun switchFacing()

    /**
     * 拍一张。结果通过回调回到共享层。
     * 失败必须回调 onError（不允许静默），由 UI 标注出来。
     */
    fun capture(onResult: (CapturedPhoto) -> Unit, onError: (String) -> Unit)
}

/**
 * 取景器。
 *
 * 依据主方案 §3.8 的决策：取景器属于"英雄界面"，双端各自原生实现，
 * commonMain 只保留这行契约。Android = PreviewView(AndroidView)，iOS = AVCaptureVideoPreviewLayer(UIKitView)。
 */
@Composable
expect fun CameraViewfinder(controller: CaptureController, modifier: Modifier)

@Composable
expect fun rememberCaptureController(): CaptureController
