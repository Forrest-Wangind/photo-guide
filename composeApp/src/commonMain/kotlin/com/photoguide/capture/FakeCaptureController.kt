package com.photoguide.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * 预览/测试用的假相机。
 *
 * 存在的意义：没有真机、或相机不可用时，整条「拍照 → 云端模型 → 建议卡」
 * 的链路仍然能跑通并被验证。这就是 §5.3 Debug 思路的延伸——
 * 让"看不到画面"不成为"无法验证"。
 */
class FakeCaptureController : CaptureController {
    private val _state = mutableStateOf<CameraState>(CameraState.Ready)
    private val _canCapture = mutableStateOf(true)
    private val tick = mutableStateOf(0)

    override val state: State<CameraState> get() = _state
    override val canCapture: State<Boolean> get() = _canCapture

    override fun capture(onResult: (CapturedPhoto) -> Unit, onError: (String) -> Unit) {
        tick.value += 1
        onError("当前为占位取景器：本平台尚未接入相机实现")
    }

    @Composable
    fun Surface(modifier: Modifier) {
        androidx.compose.foundation.Canvas(modifier) {
            drawRect(color = androidx.compose.ui.graphics.Color(0xFF202024))
        }
    }
}

@Composable
fun rememberFakeCaptureController(): FakeCaptureController = remember { FakeCaptureController() }
