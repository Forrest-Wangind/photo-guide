package com.photoguide.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 相机权限状态。
 *
 * 权限请求必须由 Activity 发起（共享层拿不到 Activity），
 * 所以 MainActivity 负责请求，把结果写进这个全局状态，取景器订阅它。
 */
object CameraPermissionState {
    val granted = mutableStateOf(false)
}

@Composable
actual fun rememberCaptureController(): CaptureController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    return remember(context, lifecycleOwner) {
        AndroidCaptureController(context, lifecycleOwner)
    }
}

@Composable
actual fun CameraViewfinder(controller: CaptureController, modifier: Modifier) {
    val android = controller as? AndroidCaptureController
    if (android == null) {
        Box(modifier.background(Color(0xFF15161A)), contentAlignment = Alignment.Center) {
            Text("取景器类型不匹配", color = Color(0xFFB6B6C0), fontSize = 13.sp)
        }
        return
    }

    val granted by CameraPermissionState.granted

    if (!granted) {
        // 绝不静默失败：没权限就明确说，而不是给一块黑屏
        Box(modifier.background(Color(0xFF15161A)), contentAlignment = Alignment.Center) {
            Text(
                text = "需要相机权限才能取景。\n请在系统设置里允许后返回。",
                color = Color(0xFFB6B6C0),
                fontSize = 13.sp,
            )
        }
        return
    }

    LaunchedEffect(granted) { android.onPermissionGranted() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // COMPATIBLE 模式在部分机型上更稳（TEXTURE 模式偶发首帧黑屏）
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }.also { view -> android.attachPreview(view) }
        },
    )
}

/**
 * CameraX 实现。
 *
 * 注意这不是"最终形态"：主方案 §3.5/§3.8 的目标是帧通路完全 native 化
 * （ImageAnalysis + C++ 推理），但那属于 Phase 2。Phase 1 只需要
 * "预览 + 拍一张内存里的 JPEG"，用 CameraX 的 Preview + ImageCapture 正好，
 * 且不引入任何多余复杂度。
 */
internal class AndroidCaptureController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : CaptureController {

    private val _state = mutableStateOf<CameraState>(CameraState.Starting)
    private val _canCapture = mutableStateOf(false)

    override val state = _state
    override val canCapture = _canCapture

    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private var bound = false

    fun attachPreview(view: PreviewView) {
        previewView = view
        bind()
    }

    fun onPermissionGranted() {
        if (!hasPermission()) {
            _state.value = CameraState.Failed("未授予相机权限", permissionDenied = true)
            _canCapture.value = false
            return
        }
        if (previewView != null && !bound) bind()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun bind() {
        if (!hasPermission()) {
            _state.value = CameraState.Failed("未授予相机权限", permissionDenied = true)
            _canCapture.value = false
            return
        }
        val view = previewView
        if (view == null) {
            _state.value = CameraState.Failed("预览控件尚未就绪")
            return
        }

        _state.value = CameraState.Starting

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()

                val selector = when {
                    runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false) ->
                        CameraSelector.DEFAULT_BACK_CAMERA

                    runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false) ->
                        CameraSelector.DEFAULT_FRONT_CAMERA

                    else -> null
                }
                if (selector == null) {
                    _state.value = CameraState.Failed("这台设备上没有可用相机")
                    _canCapture.value = false
                    return@addListener
                }

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(view.surfaceProvider)

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)

                imageCapture = capture
                bound = true
                _state.value = CameraState.Ready
                _canCapture.value = true
            } catch (t: Throwable) {
                _state.value = CameraState.Failed("相机绑定失败：${t::class.simpleName} ${t.message.orEmpty()}")
                _canCapture.value = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun capture(onResult: (CapturedPhoto) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onError("相机还没准备好，稍等一下再按")
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        if (bytes.isEmpty()) {
                            onError("拍到的照片是空的（0 字节），可能被其它应用占用了相机")
                            return
                        }
                        val rotation = image.imageInfo.rotationDegrees
                        val swapped = rotation == 90 || rotation == 270
                        onResult(
                            CapturedPhoto(
                                bytes = bytes,
                                mimeType = "image/jpeg",
                                width = if (swapped) image.height else image.width,
                                height = if (swapped) image.width else image.height,
                                rotationDegrees = rotation,
                            )
                        )
                    } catch (t: Throwable) {
                        onError("读取照片失败：${t.message}")
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError("拍照失败：${exception.message}（code=${exception.imageCaptureError}）")
                }
            },
        )
    }
}
