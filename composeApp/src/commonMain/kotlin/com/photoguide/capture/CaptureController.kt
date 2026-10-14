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

interface CaptureController {
    val state: State<CameraState>

    /** 是否已就绪、可以按快门。 */
    val canCapture: State<Boolean>

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
