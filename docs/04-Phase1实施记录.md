# Phase 1 实施记录

> 版本 v0.1 · 2026-10-14 · 状态：**Android 已构建通过（产出 debug APK）；iOS 代码就绪，本机无法编译**
> 上游：`docs/00-总体框架与实施计划.md` §2.5（演进架构）/ §7.1（Phase 1 精简路径）

---

## 0. 本轮决策（用户 2026-10-14 拍板）

| # | 决策项 | 结论 | 备注 |
|---|---|---|---|
| 1 | Phase 1 平台 | **双端并行**（推翻我此前的"Android 优先"建议） | Android 侧本机可构建验证；iOS 侧代码完整但需 Mac |
| 2 | 远端大模型 | **完全可配置**，默认 **DeepSeek** | 见 §3 |
| 3 | 开工 | 已开工 | 代码已落盘并通过 Android 构建 |

---

## 1. 一个必须先说的事实：DeepSeek 现在有视觉模型了

这一点改变了 Phase 1 的可行域。**DeepSeek 于 2026-08-21 上线
`deepseek-v4-flash-vision-exp`**（官方定位"实验性质"），支持图片输入：

| 项 | 值 | 来源 |
|---|---|---|
| 模型名 | `deepseek-v4-flash-vision-exp` | 官方 API 文档 |
| 输入方式 | base64 内联 / 外链 URL / Files API 引用 | 官方 API 文档 |
| 图片计费 | 按尺寸折算 token，**单张最多 384 token** | 官方发布会口径 |
| 费率 | 输入 ¥1 / 输出 ¥2 每百万 token | 官方发布会口径 |
| 下采样开关 | `detail: "low"` → 服务端降到 512×512 再推理 | 官方 API 文档 |
| 接口形态 | 兼容 OpenAI Chat Completions / Messages / Responses | 官方 API 文档 |

**这意味着 Phase 1 的"单图直传云端多模态"路径不需要任何折衷。** 而且
`detail: "low"` 恰好就是 §2.4 三件套里那一件「缩略图 512px」——服务端替我们做了。

> ⚠️ 两点保留：① 官方明确标注为**实验性质**，模型名和口径都可能在正式版时变；
> ② 因此**模型名做成可编辑**，不写死在代码里。

---

## 2. 已实现的模块

### 2.1 分层落地情况

| 层 | 内容 | 状态 |
|---|---|---|
| 采集层 | 相机预览 + 内存拍照（不落盘） | ✅ Android(CameraX) / ✅ iOS(AVFoundation，未编译) |
| 感知层 | `PerceptionSource` 接口 + `NoPerception` 空实现 + 注册表 | ✅ Phase 1 形态完成 |
| 决策层 | `HintEngine` 编排 + 宽容解析 + 显式降级 | ✅ |
| 模型层 | `LlmConfig` / `LlmProvider` / OpenAI 兼容实现 / `LlmClient` | ✅ |
| 交互层 | 相机页 / 双栏提示卡 / 设置页 / Debug 浮层 | ✅ |

### 2.2 关键文件

```
composeApp/src/commonMain/kotlin/com/photoguide/
├── capture/
│   ├── CapturedPhoto.kt          # 照片载体（含 width/height/rotation，为 Phase 2 坐标换算预留）
│   ├── CaptureController.kt      # ★ 取景器 @Composable expect + CaptureController 接口
│   └── FakeCaptureController.kt  # 无相机时的占位实现（保证链路可验证）
├── perception/
│   ├── PerceptionSource.kt       # ★ Phase 1/2 的接口钉子
│   ├── NoPerception.kt           # Phase 1 空实现
│   └── PerceptionRegistry.kt     # 注册表，Phase 2 加实现即可
├── llm/
│   ├── LlmConfig.kt              # ★ 可配置模型（provider/baseUrl/model/key/温度/超时/是否传图）
│   ├── ProviderCatalog.kt        # 服务商预设（DeepSeek + 自定义 OpenAI 兼容）
│   ├── LlmProvider.kt            # 抽象 + LlmClient 门面
│   ├── OpenAiCompatibleProvider.kt # 实际 HTTP 调用 + 分级错误映射
│   ├── HttpClientFactory.kt      # expect：Android=OkHttp / iOS=Darwin
│   └── LlmModels.kt              # FailureKind 显式分类
├── prompt/CompositionPrompt.kt   # ★ 提示词（严格要求 JSON 输出；区分"看到图/没看到图"）
├── decision/
│   ├── ShootingIntent.kt         # L0 预设场景 + L1 一句话
│   ├── CompositionHint.kt        # ★ 双栏结构（摄影师动作 / 模特台词）
│   └── HintEngine.kt             # 编排 + 分段计时 + 容错解析
├── metrics/StageTimer.kt         # 分段耗时 + P50/P99 趋势
├── state/AppState.kt             # 状态机 + 配置持久化
└── ui/
    ├── CameraScreen.kt / HintCard.kt / SettingsScreen.kt / DebugPanel.kt / Theme.kt / App.kt
```

### 2.3 三处值得单独说的实现选择

**① "绝不静默失败"落到了每一层**

- `FailureKind` 分了 9 类（未配置/鉴权/限流/超时/网络/服务端/坏响应/取消/未知），
  每类在 UI 上行为不同（超时给"点这里重试"，未配置引导去设置页）；
- 提示词解析失败时**不白屏也不假装成功**——降级为原始文本，并在卡片上打出
  「结构化解析失败，已降级为原始文本显示（不是你的操作问题）」；
- 相机权限缺失、相机绑定失败、设备无可用摄像头，都是明确的文字提示而不是黑屏；
- 模型返回的 `warnings` 一字不改透传到卡片。

**② 提示词显式区分"模型看到图 / 没看到图"**

不传图时如果不告诉模型，它会凭常识编一套构图建议出来——这对用户是纯粹的伤害。
所以 `buildUserPrompt` 在 `imageAttached = false` 时会明确写
"你没有收到照片，你看不到画面……必须在 warnings 里说明"，并且
在无感知数据时禁止它编造百分比或厘米数。

**③ Debug 浮层从第一天就有**

需求里要的是"标出识别的物体轮廓"，Phase 1 还没有检测器，所以现在画的是
**链路每一段耗时**（感知/编码/组装/模型）+ 最近 30 次的 P50/P99 折线。
位置和数据结构先立住，Phase 2 接入 YOLO 后在这里叠加归一化坐标的轮廓绘制即可。

---

## 3. 远端大模型：可配置是怎么做的

```
LlmConfig (可持久化，存 SharedPreferences / NSUserDefaults)
  ├── providerId  : deepseek | openai-compatible
  ├── baseUrl     : https://api.deepseek.com
  ├── model       : deepseek-v4-flash-vision-exp   ← 可在设置页手改
  ├── apiKey      : sk-…
  ├── sendImage   : true   ← 关掉就退化成纯文本形态
  ├── lowDetail   : true   ← 对应 detail=low（512×512 下采样）
  ├── temperature / maxTokens / timeoutMs
  └── extraInstruction  ← 拼进 system prompt 的个性化要求
```

**换服务商不改代码**：通义 / 豆包 / 自建 vLLM / One-API 网关只要兼容
`/chat/completions`，改 `baseUrl` + `model` 两个字段即可。

**为什么不用各家的原生 SDK**：一旦引入厂商 SDK，就等于把"可配置"这件事交出去了。
OpenAI 兼容格式在 2026 年已经是事实标准，HTTP + JSON 这一层自己维护成本极低。

---

## 4. 构建结果（本机实测）

| 项 | 结果 |
|---|---|
| 命令 | `./gradlew :composeApp:assembleDebug` |
| 结果 | **BUILD SUCCESSFUL**（首次 2m28s 含依赖下载，增量 48s） |
| 产物 | `composeApp/build/outputs/apk/debug/composeApp-debug.apk`（**15.7 MB**） |
| 源码量 | Kotlin 文件 **36 个**，**2979 行** |
| Gradle | wrapper 8.13 已生成（`./gradlew` 可直接用） |
| 环境 | JDK 21（Android Studio JBR）、Android SDK 36、NDK 26.1、24 核 |

---

## 5. 版本锁定：为什么是这一组

这一组不是"挑最新的"，而是**用 Gradle Module Metadata 反查每个库依赖的
kotlin-stdlib 版本**后取上界得出的：

| 库 | 版本 | 其依赖的 kotlin-stdlib |
|---|---|---|
| Kotlin | **2.3.21** | —（取上界） |
| Compose Multiplatform | **1.11.1** | 2.2.20 |
| Ktor | **3.5.2** | 2.3.21 |
| kotlinx-serialization | **1.10.0** | 2.3.0 |
| kotlinx-coroutines | **1.11.0** | 2.2.20 |
| AGP | **8.13.0** | 本机缓存已有 |
| Gradle | **8.13** | 本机缓存已有（免下载） |
| CameraX | **1.5.3** | — |
| compileSdk / minSdk / targetSdk | 36 / 26 / 36 | 本机已装 android-36 |

**为什么不用最新的 CMP 1.12.1 + Kotlin 2.4.21**：CMP 1.12 线要求
`compileSdk 37`，而本机只装到 36；且 AGP 需要 9.x。为了"能在本机真的构建出来"，
选了 1.11.1 这条线。**升级是一个独立动作，不该和开工混在一起。**

---

## 6. 踩到的三个坑（都已修，记在这里省下次的时间）

| # | 现象 | 原因 | 修法 |
|---|---|---|---|
| 1 | `Unresolved reference: camerax` | 版本目录别名 `camerax-core` 的访问器是 `libs.camerax.core`，我写成了 `libs.androidx.camerax.core` | 别名统一改为 `androidx-camerax-*` |
| 2 | `Const 'val' initializer must be a constant value` | `const val X = """…""".trimIndent()` —— `trimIndent()` 不是编译期常量 | 改成 `private val` |
| 3 | `Platform declaration clash: setIntent(...)` | `var intent` 自动生成的 JVM setter 与我手写的 `fun setIntent()` 签名相同 | 方法改名 `updateIntent` / `selectPerception` |

---

## 7. iOS 侧状态

**结论：代码已写完整，但本机（Windows，无 Xcode）无法编译验证。**

Kotlin/Native 的 iOS target 已在 `composeApp/build.gradle.kts` 里用
`os.name == Mac` 做条件声明——这样同一份仓库在 Windows 上只出 Android、
在 macOS 上同时出 iOS framework，不会因为缺工具链而让 Windows 构建挂掉。

| 文件 | 状态 |
|---|---|
| `iosMain/.../MainViewController.kt` | 已写 |
| `iosMain/.../capture/CaptureController.ios.kt` | 已写（AVCaptureSession + AVCapturePhotoOutput），**未编译** |
| `iosMain/.../platform/SettingsStore.ios.kt` | 已写（NSUserDefaults） |
| `iosMain/.../llm/HttpClientFactory.ios.kt` | 已写（Darwin 引擎） |
| `iosApp/iosApp/iOSApp.swift` / `ContentView.swift` / `Info.plist` | 已写 |
| `iosApp/iosApp.xcodeproj` | **未生成**（手写 pbxproj 无法在此验证），补齐步骤见 `iosApp/README.md` |

> 第一次在 Mac 上编译时，若某个 AVFoundation 的 cinterop 签名对不上
> （Kotlin/Native 的 ObjC 映射偶尔与文档写法有出入），按编译器提示调参数名或可空性即可，
> **逻辑结构不需要动**。

---

## 8. 还没做的（下一步）

**阻塞项（需要你）**

1. **一台 Android 真机**（`adb devices` 当前为空）—— 相机预览/拍照/权限、
   端到端延迟，都必须真机才能验。模拟器可跑通 UI，但没有真实相机。
2. **一个 DeepSeek API Key** —— 填进设置页即可跑通端到端。
   （也可以先不填，App 会明确提示"还没配置 API Key"而不是崩掉。）

**代码侧待办（不阻塞）**

- 拍照后按 EXIF 旋转把 JPEG 真正转正（现在只传了 `rotationDegrees`，未重编码）
- 图片上传前按 `imageMaxEdge` 做客户端下采样（现在依赖服务端 `detail=low`）
- Phase 1 验收：把"拍照 → 双栏建议卡"的**端到端延迟**和**建议采纳率**跑出来

**Phase 2 预留（接口已就位，只等实现）**

- `PerceptionSource` 的新实现（YOLO26n / MoveNet）+ 帧通路 native 化
- `StageTimer` 升级为逐帧的 P50/P99/最大连掉帧（§10.6 流畅性验收基线）
- Debug 浮层叠加归一化坐标的轮廓绘制
