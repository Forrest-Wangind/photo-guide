# photo-guide · 拍摄指导

> 拍照时手把手告诉你**往哪站、往哪退、该怎么对她说**。

一款手机端拍摄指导 App。用户给出拍摄意图（预设场景或一句话），App 在取景时给出
构图建议：角度、距离、主体位置、前后层次。

**差异化点在于提示卡的形状——它是双栏的：**

| 左栏 · 摄影师侧 | 右栏 · 模特侧口播 |
|---|---|
| 往左半步，蹲下来一点 | 「头往右转一点，看远处别看我」 |
| 手机往上抬，把她放在右三分线 | 「下巴稍微收一点，笑一下」 |

市面上所有构图辅助工具都在解决「构图对不对」，没有人解决**「你到底该对她说点什么」**。
这一栏就是本项目的存在理由。

---

## 当前状态

**Phase 1 已开工并构建通过**（2026-10-14）。

| 项 | 结果 |
|---|---|
| Android 构建 | ✅ `BUILD SUCCESSFUL`，产物 `composeApp-debug.apk`（15.7 MB） |
| 源码量 | Kotlin 36 个文件 / 2979 行 |
| 双端 | Android 已跑通构建；iOS 源码完整，**本机 Windows 无 Xcode，未编译验证** |
| 阻塞项 | 缺一台 Android 真机、一个 DeepSeek API Key（均不阻塞代码） |

Phase 1 范围：**单张照片** → 拍照 → 双栏建议卡。
Phase 2 目标：**30fps 视频流**实时取景建议（感知层接口已预留在 `commonMain`）。

---

## 技术栈

**Kotlin Multiplatform (KMP) + Compose Multiplatform**，iOS + Android 双端共用一套 UI 与业务逻辑。

选型理由（详细论证见 `docs/00-总体框架与实施计划.md` §3）：KMP 没有"跨语言边界"——
CameraX 的 `ImageAnalysis.Analyzer` 本身就是 Kotlin lambda，iOS 侧 cinterop 可直调
AVFoundation，**帧通路不必绕一层自有插件桥**；而调参代码能写在 `commonMain` 并可单测，
不必沉进 C++。RN 的帧通路开销实测只有约 1ms（VisionCamera 官方 benchmark：纯原生 68ms vs
插件 69ms），短板在"每帧逻辑放哪"，属开发成本而非性能上限；Flutter 在帧通路上没有官方等价物，
被迫自造地基，因此排序靠后。

| 组件 | 版本 | 说明 |
|---|---|---|
| Kotlin | 2.3.21 | 取各库 kotlin-stdlib 依赖上界 |
| Compose Multiplatform | 1.11.1 | 不用 1.12.x：它要求 compileSdk 37 |
| Ktor | 3.5.2 | HTTP 客户端（Android=OkHttp / iOS=Darwin） |
| kotlinx-serialization | 1.10.0 | JSON |
| AGP / Gradle | 8.13.0 / 8.13 | 本机缓存已有，免下载 |
| CameraX | 1.5.3 | Android 采集 |
| compileSdk / minSdk / targetSdk | 36 / 26 / 36 | Android 8.0+ |

> 版本不是"挑最新的"，而是用 Gradle Module Metadata 反查 kotlin-stdlib 依赖后取上界得出的。
> 升级是一个独立动作，不该和开工混在一起。

---

## 快速开始

### 环境要求

- **JDK 21**（Android Studio 自带 JBR 即可）
- **Android SDK**，含 `platforms/android-36`
- 无真机时可用 AVD 跑通 UI（**但没有真实相机**，采集链路无法验收）

### 构建 Android

```bash
# Git Bash / macOS / Linux
./gradlew :composeApp:assembleDebug

# Windows CMD / PowerShell
gradlew.bat :composeApp:assembleDebug
```

产物：`composeApp/build/outputs/apk/debug/composeApp-debug.apk`

安装到真机：

```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### 配置远端大模型

首次运行后进入 **设置页**，填入 API Key 即可跑通端到端。
**不填也能启动**——App 会明确提示「还没配置 API Key」并引导到设置页，不会崩。

默认指向 DeepSeek 的视觉实验模型（`deepseek-v4-flash-vision-exp`，兼容 OpenAI Chat Completions）。
换成任何 OpenAI 兼容端点（通义 / 豆包 / 自建 vLLM / One-API 网关）只需改
`baseUrl` + `model` 两个字段，**不需要改代码**。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `providerId` | `deepseek` | 服务商预设 |
| `baseUrl` | `https://api.deepseek.com` | 兼容 `/chat/completions` 即可 |
| `model` | `deepseek-v4-flash-vision-exp` | **特意做成可编辑**，服务商模型名变动比 App 迭代快 |
| `apiKey` | — | 存 SharedPreferences / NSUserDefaults |
| `sendImage` | `true` | 关掉退化成纯文本形态（但拿不到审美判断） |
| `lowDetail` | `true` | 对应 `detail=low`，服务端下采样到 512×512，更快更便宜 |
| `temperature` / `maxTokens` / `timeoutMs` | 0.6 / 900 / 60000 | |
| `extraInstruction` | — | 个性化要求，拼进 system prompt |

> ⚠️ 该模型官方标注为**实验性质**，模型名与计费口径都可能在正式版变化。
> 这正是模型名不写死的原因。仓库不提交任何密钥（`secrets.properties` 已在 `.gitignore`）。

### iOS

**必须 macOS + Xcode**，这是苹果生态的硬限制。
本仓库只提交 iOS 侧源码，工程壳（`.xcodeproj`）在 Mac 上生成一次即可，
补齐步骤见 [`iosApp/README.md`](iosApp/README.md)。

---

## 目录结构

```
composeApp/src/commonMain/kotlin/com/photoguide/
├── capture/          CapturedPhoto · CaptureController(expect) · Fake
├── perception/       ★ PerceptionSource 接口钉子 + NoPerception 空实现 + 注册表
├── llm/              LlmConfig · ProviderCatalog · OpenAiCompatibleProvider · LlmClient · FailureKind
├── prompt/           CompositionPrompt —— 严格 JSON 输出，区分"看到图/没看到图"
├── decision/         ShootingIntent · CompositionHint(双栏) · HintEngine
├── metrics/          StageTimer —— 分段耗时 + P50/P99
├── state/            AppState —— 状态机 + 配置持久化
├── ui/               CameraScreen · HintCard · SettingsScreen · DebugPanel · Theme · App
├── platform/         SettingsStore(expect)
└── util/             Base64 · Clock

composeApp/src/androidMain/   CameraX PreviewView + ImageCapture（内存拍照）· SharedPreferences · OkHttp
composeApp/src/iosMain/       AVCaptureSession + AVCapturePhotoOutput · NSUserDefaults · Darwin
iosApp/                       SwiftUI 入口 + Info.plist
docs/                         设计与决策文档
```

---

## 架构

### 能力分层

| 层 | 技术 | 延迟量级 | 职责 |
|---|---|---|---|
| 感知层 | 端侧专用小模型（Phase 2 接入） | 20~70ms（15~30Hz） | 看到什么、在哪、多大 |
| 理解层 | 云端多模态大模型 | 1~2s（0.5~1Hz） | 好不好看、该怎么调 |

两者延迟差两个数量级，所以**不是二选一**：端侧负责"快"，云端负责"懂"。

### 三条不可违背的设计原则

**1. 帧永进 Dart/托管层，只把结果给上层。**
预览走零拷贝通路，分析全在 native（CameraX `ImageAnalysis` → C++ 推理 → 只回传数百个 float）。
这是 30fps 的分水岭。

**2. 不做每帧推理。**
"30fps" = 预览 30fps + 检测 10~15fps + 跟踪补帧 30fps + LLM 0.5Hz。
构图是慢变量；把推理的长尾与预览帧率解耦，才有稳定的帧时间。

**3. 绝不静默失败。**
这条落到了每一层：

- `FailureKind` 分 9 类（未配置 / 鉴权 / 限流 / 超时 / 网络 / 服务端 / 坏响应 / 取消 / 未知），
  每类在 UI 上行为不同——超时给「点这里重试」，未配置引导去设置页；
- 提示词解析失败**不白屏也不假装成功**：降级为原始文本，并在卡片上标明
  「结构化解析失败，已降级为原始文本显示（不是你的操作问题）」；
- 相机权限缺失、相机绑定失败、设备无可用摄像头，都是明确文字提示而不是黑屏；
- 模型返回的 `warnings` 一字不改透传到卡片；
- 不传图时会明确告知模型「你看不到画面，必须在 warnings 里说明」——
  否则它会凭常识编一套建议出来，这对用户是纯粹的伤害。

### 双阶段演进：一个接口钉子

```kotlin
interface PerceptionSource {
    val consumesPixels: Boolean
    suspend fun analyze(photo: CapturedPhoto): PerceptionSnapshot
}
```

这个接口 Phase 1 就定义在 `commonMain`，Phase 1 的实现是 `NoPerception`（空实现，
照片直传云端）；Phase 2 换成 YOLO26n / MoveNet，把结果作为 JSON 附加上下文一起给大模型。
**UI、HintEngine、提示词一行不改。**

### Debug 浮层

需求里要的是"标出识别到的物体轮廓"。Phase 1 没有检测器，所以浮层现在画的是
**链路每一段耗时**（感知 / 编码 / 组装 / 模型）+ 最近 30 次的 P50/P99 折线。
位置和数据结构先立住，Phase 2 接入检测器后，在这里叠加归一化坐标的轮廓绘制即可。

---

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/00-总体框架与实施计划.md`](docs/00-总体框架与实施计划.md) | 主方案：需求拆解、三方框架矩阵、演进架构、性能预算、验收基线 |
| [`docs/02-框架性能PoC方案.md`](docs/02-框架性能PoC方案.md) | 框架性能 PoC 方案（已降级为 GC 闸门） |
| [`docs/03-本地模型选型横评.md`](docs/03-本地模型选型横评.md) | 检测 / 姿态 / 推理后端三张横评表 |
| [`docs/04-Phase1实施记录.md`](docs/04-Phase1实施记录.md) | Phase 1 决策、模块清单、构建结果、踩坑记录 |
| [`iosApp/README.md`](iosApp/README.md) | 在 Mac 上补齐 Xcode 工程的步骤 |

---

## 路线图

**Phase 1（当前）** — 单图闭环

- [x] 双端骨架 + 相机采集（Android 已验证）
- [x] 双栏建议卡 + 严格 JSON 提示词
- [x] 远端模型完全可配置，9 类失败显式分类
- [x] Debug 浮层（分段耗时 + P50/P99）
- [ ] 真机验收：端到端延迟、建议采纳率
- [ ] 拍照按 EXIF 真正转正（现在只传 `rotationDegrees`）
- [ ] 上传前客户端下采样（现在依赖服务端 `detail=low`）

**Phase 2** — 30fps 视频流

- [ ] `PerceptionSource` 新实现（YOLO26n + NCNN / MoveNet），帧通路 native 化
- [ ] `StageTimer` 升级为逐帧 P50/P99 / 最大连掉帧
- [ ] Debug 浮层叠加轮廓绘制
- [ ] 流畅性验收基线：P99 ≤ 33.3ms、掉帧率 < 5%、最大连掉 ≤ 2 帧、热路径 0 分配

**后续** — 参考图意图（L2）、爬行/定位相机机器人

---

## 已知限制

- **iOS 未编译验证**：本机 Windows 无 Xcode。首次在 Mac 上编译时，
  若某处 AVFoundation 的 cinterop 签名对不上（Kotlin/Native 的 ObjC 映射偶尔与文档有出入），
  按编译器提示调参数名或可空性即可，**逻辑结构不需要动**。
- **模拟器无真实相机**：AVD 只能跑通 UI，采集与端到端延迟必须真机。
- **Android 部分机型不支持"预览 + 图像分析"并行**，Phase 2 需做启动时能力探测。
- **感知层尚未接入**：Phase 1 的 `NoPerception` 不产生任何检测结果，
  定量锚点（box / keypoint）暂由云端模型自行估计。

---

## 许可

内部自用项目。若后续开源，需先处理 AGPL 相关依赖（当前自用阶段无需处理）。
