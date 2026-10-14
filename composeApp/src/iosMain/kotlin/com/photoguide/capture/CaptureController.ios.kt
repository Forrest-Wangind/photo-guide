package com.photoguide.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.sp
import com.photoguide.platform.platformLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.posix.memcpy

/**
 * iOS 相机实现（AVCaptureSession + AVCapturePhotoOutput）。
 *
 * ⚠️ 状态：代码完整，但**本机（Windows，无 Xcode）无法编译验证**。
 * 到 Mac 上第一次打开工程时，若 cinterop 签名有出入，按编译器提示微调即可，
 * 逻辑结构不需要动。
 */
@Composable
actual fun rememberCaptureController(): CaptureController {
    return remember { IosCaptureController() }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraViewfinder(controller: CaptureController, modifier: Modifier) {
    val ios = controller as? IosCaptureController
    if (ios == null) {
        Box(modifier.background(Color(0xFF15161A)), contentAlignment = Alignment.Center) {
            Text("取景器类型不匹配", color = Color(0xFFB6B6C0), fontSize = 13.sp)
        }
        return
    }

    if (ios.permissionDenied) {
        Box(modifier.background(Color(0xFF15161A)), contentAlignment = Alignment.Center) {
            Text(
                text = "未授予相机权限。\n请到「设置 → 隐私 → 相机」里允许后重新打开 App。",
                color = Color(0xFFB6B6C0),
                fontSize = 13.sp,
            )
        }
        return
    }

    UIKitView(
        modifier = modifier,
        factory = {
            val container = PreviewContainer(ios.previewLayer)
            container.addSubview(ios.previewLayer)
            container
        },
        update = { view ->
            ios.previewLayer.setFrame(view.bounds)
            ios.startIfNeeded()
        },
    )
}

/** 让预览层跟随容器尺寸变化——AVCaptureVideoPreviewLayer 是 CALayer，不会自动跟。 */
@OptIn(ExperimentalForeignApi::class)
private class PreviewContainer(
    private val previewLayer: AVCaptureVideoPreviewLayer,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.setFrame(bounds)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosCaptureController : CaptureController {

    private val _state = mutableStateOf<CameraState>(CameraState.Starting)
    private val _canCapture = mutableStateOf(false)

    override val state = _state
    override val canCapture = _canCapture

    var permissionDenied: Boolean = false
        private set

    private val session = AVCaptureSession()
    private var photoOutput: AVCapturePhotoOutput? = null
    private var started = false

    /** 一次性持有 delegate，否则会被 GC 掉导致回调丢失。 */
    private var pendingDelegate: Any? = null

    val previewLayer: AVCaptureVideoPreviewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    init {
        configure()
    }

    private fun configure() {
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        when (status) {
            AVAuthorizationStatusAuthorized -> setupSession()
            AVAuthorizationStatusNotDetermined -> {
                requestCameraPermission { granted ->
                    if (granted) {
                        setupSession()
                    } else {
                        permissionDenied = true
                        _state.value = CameraState.Failed("用户拒绝了相机权限", permissionDenied = true)
                    }
                }
            }
            else -> {
                permissionDenied = true
                _state.value = CameraState.Failed("相机权限被拒绝", permissionDenied = true)
            }
        }
    }

    private fun setupSession() {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        if (device == null) {
            _state.value = CameraState.Failed("这台设备上没有可用摄像头")
            return
        }

        val input = runCatching { AVCaptureDeviceInput.deviceInputWithDevice(device, null) }.getOrNull()
        if (input == null) {
            _state.value = CameraState.Failed("无法创建相机输入（可能被其它应用占用）")
            return
        }

        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetPhoto

        if (session.canAddInput(input)) session.addInput(input) else {
            session.commitConfiguration()
            _state.value = CameraState.Failed("相机输入被拒绝，可能设备不可用")
            return
        }

        val output = AVCapturePhotoOutput()
        if (session.canAddOutput(output)) {
            session.addOutput(output)
            photoOutput = output
        } else {
            session.commitConfiguration()
            _state.value = CameraState.Failed("无法添加拍照输出")
            return
        }

        session.commitConfiguration()
        _canCapture.value = true
        _state.value = CameraState.Ready
    }

    /** 启动会话在后台队列做——startRunning() 在主线程会阻塞 UI。 */
    fun startIfNeeded() {
        if (started || photoOutput == null) return
        started = true
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            session.startRunning()
        }
    }

    override fun capture(onResult: (CapturedPhoto) -> Unit, onError: (String) -> Unit) {
        val output = photoOutput
        if (output == null) {
            onError("相机还没准备好")
            return
        }

        val delegate = PhotoCaptureDelegate { bytes, error ->
            pendingDelegate = null
            if (error != null || bytes == null) {
                onError(error ?: "拍照失败：没有拿到图像数据")
                return@PhotoCaptureDelegate
            }
            onResult(
                CapturedPhoto(
                    bytes = bytes,
                    mimeType = "image/jpeg",
                    width = 0, // 真实尺寸由云端解码；Phase 2 从 CGImage 补上
                    height = 0,
                    rotationDegrees = 0,
                )
            )
        }
        pendingDelegate = delegate

        val settings = AVCapturePhotoSettings.photoSettings()
        output.capturePhotoWithSettings(settings, delegate)
    }

    private companion object {
        const val TAG = "IosCamera"
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PhotoCaptureDelegate(
    private val onFinish: (bytes: ByteArray?, error: String?) -> Unit,
) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {

    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        if (error != null) {
            platformLog("IosCamera", "拍照失败：${error.localizedDescription}")
            onFinish(null, "拍照失败：${error.localizedDescription}")
            return
        }
        val data = didFinishProcessingPhoto.fileDataRepresentation()
        if (data == null) {
            onFinish(null, "拍照返回了空数据")
            return
        }
        onFinish(data.toByteArray(), null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun requestCameraPermission(onResult: (Boolean) -> Unit) {
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
        onResult(granted)
    }
}
