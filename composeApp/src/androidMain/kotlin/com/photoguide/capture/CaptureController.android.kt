package com.photoguide.capture

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
    private val _facing = mutableStateOf(CameraFacing.BACK)

    /** 只有前后两颗都在时才允许切；单摄设备上按钮直接禁用。 */
    private val _canSwitch = mutableStateOf(false)
    private val _notice = mutableStateOf<String?>(null)

    override val state = _state
    override val canCapture = _canCapture
    override val facing = _facing
    override val canSwitchFacing = _canSwitch
    override val notice = _notice

    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private var bound = false

    /** 复用同一个 provider：切换镜头不该再 getInstance 一次（各机型首帧延迟不同，没必要多等）。 */
    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * 相册落盘线程：单线程 + daemon。
     *
     * 拍照回调跑在主线程（`takePicture` 传的是 mainExecutor），而写 MediaStore 是阻塞 IO ——
     * 在主线程做就是每拍一张卡掉一帧预览。串行也能保证多个文件名不会同一毫秒撞车。
     */
    private val gallerySaver = Executors.newSingleThreadExecutor { r ->
        Thread(r, "photoguide-gallery").apply { isDaemon = true }
    }

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

    private fun fail(reason: String, permissionDenied: Boolean = false) {
        _state.value = CameraState.Failed(reason, permissionDenied)
        _canCapture.value = false
    }

    override fun clearNotice() {
        _notice.value = null
    }

    private fun bind() {
        if (!hasPermission()) {
            fail("未授予相机权限", permissionDenied = true)
            return
        }
        if (previewView == null) {
            fail("预览控件尚未就绪")
            return
        }

        _state.value = CameraState.Starting

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val hasBack = provider.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA)
                val hasFront = provider.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA)
                _canSwitch.value = hasBack && hasFront

                // 首次绑定：优先后置（拍别人是主场景）；之后都按用户选的那颗走
                val target = pickFacing(hasBack, hasFront, _facing.value)
                if (target == null) {
                    _canSwitch.value = false
                    fail("这台设备上没有可用相机")
                    return@addListener
                }

                bindUseCases(provider, target, hasBack && hasFront)
            } catch (t: Throwable) {
                fail("相机绑定失败：${t::class.simpleName} ${t.message.orEmpty()}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 决定这次真正绑哪颗镜头。
     *
     * 想要的那颗不在（例如只有单摄）就换另一颗，**不报错但也不装作没发生** ——
     * 返回值只用于绑定，降级事实由 [_canSwitch]=false + UI 的镜头标签体现。
     */
    private fun pickFacing(hasBack: Boolean, hasFront: Boolean, want: CameraFacing): CameraFacing? = when {
        want == CameraFacing.BACK && hasBack -> CameraFacing.BACK
        want == CameraFacing.FRONT && hasFront -> CameraFacing.FRONT
        hasBack -> CameraFacing.BACK
        hasFront -> CameraFacing.FRONT
        else -> null
    }

    /** 真正绑定。切换镜头复用这条路径 —— 只有"绑哪颗"在变，"怎么绑"必须只有一份。 */
    private fun bindUseCases(provider: ProcessCameraProvider, target: CameraFacing, canSwitch: Boolean) {
        val view = previewView ?: throw IllegalStateException("预览控件已释放")

        val selector = if (target == CameraFacing.BACK) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(view.surfaceProvider)

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // 分辨率上限 ~1600px：模型端 detail=low 本来就会下采样到 512，
            // 全尺寸原图（真机可达 4000px+）只会把 base64 请求体膨胀到几 MB ——
            // 上传更容易被掐断（ECONNABORTED）、编码更慢、token 更贵，没有任何收益。
            // FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER：取不到目标就取最接近的可用档。
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1600, 1200),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)

        imageCapture = capture
        bound = true
        _facing.value = target
        _canSwitch.value = canSwitch
        _state.value = CameraState.Ready
        _canCapture.value = true
    }

    override fun switchFacing() {
        _notice.value = null
        val target = _facing.value.other

        // 快门先落下：unbind → rebind 这段窗口里 takePicture 必然抛异常
        _canCapture.value = false

        ContextCompat.getMainExecutor(context).execute {
            if (!hasPermission()) {
                fail("未授予相机权限", permissionDenied = true)
                return@execute
            }

            val provider = cameraProvider
            if (provider == null) {
                // 还没绑过（首帧都没起来就点了）：记下意图，交给 bind 去挑
                _facing.value = target
                bind()
                return@execute
            }

            val hasBack = provider.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA)
            val hasFront = provider.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA)
            if (!hasBack || !hasFront) {
                _canSwitch.value = false
                _notice.value = "这台设备只有一颗摄像头，无法切换"
                _canCapture.value = imageCapture != null && bound
                return@execute
            }

            _state.value = CameraState.Starting
            val previous = _facing.value
            try {
                bindUseCases(provider, target, canSwitch = true)
            } catch (t: Throwable) {
                val reason = "${t::class.simpleName} ${t.message.orEmpty()}".trim()
                // 回滚：预览已经 unbind 了，不拉回来就是一块黑屏
                val recovered = runCatching { bindUseCases(provider, previous, canSwitch = true) }.isSuccess
                if (recovered) {
                    _notice.value = "切换到${target.displayName}失败：$reason（已保持${previous.displayName}）"
                } else {
                    _notice.value = null
                    fail("切换镜头失败且无法恢复取景：$reason")
                }
            }
        }
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
                        // 落地与"分析结果"无关：拍到了就存。分析超时/失败/端点没配，
                        // 都不影响这张照片存在 —— 用户按下快门就该留下东西。
                        saveToGallery(bytes)
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

    /**
     * 把刚拍到的 JPEG 存进系统相册。
     *
     * 走 MediaStore + `IS_PENDING` 两步写：先插记录并标记"写入中"，写完流再置 0。
     * 一步到位直接写的话，相册 App 有机会在文件只写了一半时读进来（半张灰图）。
     * 29+ 插入即写入媒体库，系统会自动通知相册，**不需要**再手动 scanFile。
     *
     * < 29 写公共目录还需要 WRITE_EXTERNAL_STORAGE（外加运行时申请），Phase 1 不做 ——
     * 明确告诉用户没存进去，而不是假装存了。
     */
    private fun saveToGallery(bytes: ByteArray) {
        gallerySaver.execute {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Log.w(TAG_GALLERY, "API < 29，不写公共相册")
                    _notice.value = "系统版本低于 Android 10，照片没能存进相册（仅用于本次分析）"
                    return@execute
                }
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${timestamp()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_DIR)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("媒体库拒绝创建记录")

                try {
                    val out = resolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法打开输出流")
                    out.use { it.write(bytes) }
                } catch (t: Throwable) {
                    // 半成品必须删掉：否则相册里会留一张点不开的图，比没存更糟
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }

                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                Log.i(TAG_GALLERY, "已存入相册：$GALLERY_DIR (${bytes.size}B)")
            } catch (t: Throwable) {
                Log.w(TAG_GALLERY, "保存失败", t)
                _notice.value = "照片没能存进相册：${t::class.simpleName} ${t.message.orEmpty()}".trim()
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private companion object {
        /** 存到 DCIM 子目录：国产相册基本都按 DCIM 建相册，比 Pictures 更容易被看到。 */
        const val GALLERY_DIR = "DCIM/PhotoGuide"

        /** 相册保存成败都有 logcat 记录 —— 失败时 UI 上的 notice 可能被分析错误遮住，日志是第二现场。 */
        const val TAG_GALLERY = "PhotoGuideGallery"
    }
}

/**
 * `hasCamera` 在个别机型上会抛（相机服务未就绪 / 厂商实现异常）。
 * 探测能力不该炸穿整个绑定流程 —— 一律降级成 "没有"，由调用方按"没有另一颗"处理。
 */
private fun ProcessCameraProvider.hasCameraSafe(selector: CameraSelector): Boolean =
    runCatching { hasCamera(selector) }.getOrDefault(false)

