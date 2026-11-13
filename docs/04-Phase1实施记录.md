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

### 2.3 几处值得单独说的实现选择

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

**④ 中途方案：给槽位加"记忆"（2026-10-15，当天即被 ⑤ 取代，留作决策痕迹）**

设置页最初的模型是"预设 = 出厂常量"，带来一类必然出现的问题：用户手改的值
（`deepseek` 加 `/v1`、换成中转站 URL）在切走再切回时被静默抹掉，而 UI 上
只有一层淡背景色表示选中，用户无法判断"现在到底在用什么"。

现在的模型：

- `providerProfiles: Map<String, ProviderProfile(baseUrl, model, apiKey)>` 落盘。
  切换前先给**离开的**槽位打快照，再载入**目标**槽位上次用的值（没有才用出厂默认）；
  三个切换入口（换预设 / 换已保存端点 / 存成新端点）都走同一条路径，不各写一份；
- `apiKey` 因此也按服务商分开记 —— 原先全局单 Key 时，切到 Claude 会拿着
  DeepSeek 的 Key 去撞 401，用户只会得出"Claude 配不通"这个错误结论；
- 卡片上有「生效中」文字徽标（不靠颜色猜）；手改时给「已手改」说明 +
  「恢复默认」按钮（**只重置 URL/模型名，不动 Key** —— 顺手抹掉凭证是过度删除）；
- "选中了哪个槽位"和"配置已偏离该槽位/列表里的值"是两件事，UI 分别说清楚。

**⑤ 最终形态：端点列表即唯一真相源，快照表被删掉了（2026-10-15 同日）**

④ 的快照表能修好 bug，但它本质是"同一份数据存两遍"的补救层 —— `providerProfiles`
与 `savedEndpoints` 在存同一类东西，"配置偏离了选中项"这个状态因此永远存在。
趁改动还热，直接收敛掉：

- `Endpoint(id, baseUrl, model, apiKey, protocol, builtin)` 一个列表装下所有端点：
  内置两家（DeepSeek / Claude）只是 `builtin = true` 的种子，可编辑、可重置、可删除
  （同日第二轮改为可删：至少保留 1 条，删掉的内置端点从「＋ 新增端点」一键恢复）；
- 输入框**直接编辑当前端点**（`editActiveEndpoint`），改一次落盘一次。
  **没有保存按钮 = 没有"改了没保存"这个状态**。随之删掉的：
  `providerProfiles` / `rememberCurrentProfile` / `switchProvider` / `applyEndpoint` /
  `saveCurrentEndpoint` / `canSaveEndpoint` / `isProviderCustomized`，
  以及 UI 上"已手改""未保存的自定义端点""＋ 保存并启用"三段文案；
- `LlmConfig` 瘦身为**运行参数 + `activeEndpointId`**，`baseUrl/model/apiKey` 只存在
  `Endpoint` 里一份。切端点本就不该改 `temperature/max_tokens`，这层切分正好也对；
- `Endpoint.id` 改为**稳定 id**（内置 = preset id，自定义 = `ep:N`）。旧版是
  `saved:` + baseUrl + model 推导出来的 —— **用户一改 URL，id 立即失效、选中态随之断裂**，
  这正是坑 6 的底层原因，顺手一并修掉；
- 协议族从 `providerId` 拆成端点的 `protocol` 字段：自定义端点跑 Claude 模型时选
  「Claude 兼容」，才能拿到 temperature 上限 1.0、不发 `detail=low` 的正确行为
  （用户明确选了"显式字段"而不是"按 URL 猜"——猜错时用户看不到任何提示）；
- **迁移一次到位**：旧的三处数据（config 三元组 / `saved_endpoints_v1` /
  `provider_profiles_v1`）合成列表；`activeEndpointId` 三级回落（新 id → 同名 providerId
  → 三元组匹配），旧键**保留不删**，万一逻辑有问题数据还在，可回滚排查。

> 教训（比代码本身值钱）：一个状态若能由两条路径写、或同一份值存了两份，
> 就一定会出现"UI 说是 A、实际在用 B"的不可判定态。修法是**消掉重复**，
> 而不是再补一层"提示用户两者不一致"的 UI —— 当天两个 bug 的正确解法都不是改文案。

> 待补：`commonTest` 源集与 `kotlin-test` 尚未接入。本次改动几乎全是纯状态逻辑
> （切换 / 编辑 / 删除 / 三级回落的迁移），却只能靠手工回归 —— 项目选 KMP 的
> 理由之一就是"中间层可单测"，这层基建值得单独一轮补上（需同时处理 `platformLog`
> 依赖 `android.util.Log` 在 JVM 单测下未被 mock 的问题，宜配合
> `testOptions.unitTests.isReturnDefaultValues`）。

**⑥ 输入框失焦即收软键盘，且这件事被脚本证明了（2026-10-15 修）**

现象：改完 Base URL 去点端点卡片，键盘一直挂着遮住半屏、还盖住「完成」。

根因一句话：**Compose 里 `clickable` 的兄弟节点不抢焦点**。输入框一直是聚焦态，
系统没有任何理由收键盘 —— 所以"等系统自动收"这条路根本不通。

修法（`SettingsScreen.kt`）：给三个输入框配 `ImeAction.Done`（回车 = 完成并收键盘），
并在所有"非输入类"交互里显式 `focusManager.clearFocus() + keyboard?.hide()`
（卡片 / 新增 / 恢复 / 协议 chip / 开关 / 滑杆 / 感知列表 / 「完成」），
根 Column 挂 `detectTapGestures` 处理"点空白"。

⚠️ **刻意没有用 `onFocusChanged { if (!it.isFocused) hide() }` 这个看起来很直觉的写法**：
从 URL 直接点到模型名时，先收到 `false` 的那个字段会调 `clearFocus()`，
而 `clearFocus()` 清的是整棵焦点树 —— 会把**刚拿到焦点的新字段**一起清掉
（并且焦点回调的先后顺序无保证）。所以"失去焦点"这个触发条件本身不可靠，
改用上面那组确定性的触发点。侧证：`clearFocus()` 在未聚焦时是幂等的，
滑动滑杆连续触发也只是空操作，没有额外开销。

> 验证方式（本机无真机也能做）：`tools/settings_keyboard_probe.py` 走 adb + uiautomator，
> 断言 `dumpsys input_method` 的 `mInputShown`。覆盖"点输入框弹 / 点空白收 / 点卡片收 /
> 换输入框不误伤 / 点完成收 + 返回"五条路径，连跑 3 轮 18/18 通过。
> 相机链路必须真机，但**设置页这类纯 Compose 页面 AVD 上就能自动验证** —— 这条值得记住。

写这类脚本会踩的两个坑（第一次跑就被坑了，写死坐标必错）：
键盘弹出触发 adjustResize + 自动滚动，布局整体位移，必须每步重新 dump 拿实时 bounds；
键盘还会遮住下方输入框、并把**顶部栏（含「完成」）滚出屏幕上方**，
层级里直接看不到 —— 得先收键盘 / 滑回顶部再定位。

**⑦ 顶栏固定，不再跟内容一起滚走（2026-10-15 同日修）**

⑥ 的验证过程顺带暴露了下一个问题：键盘一弹、内容区自动上滚，顶栏（标题 + 「完成」）
作为滚动内容的第一个孩子被一起推出屏幕上方 —— 用户想离开设置页得先把页面滑回顶部。

修法：设置页拆成「固定顶栏 + 独立滚动区」两层 Column。外层只做 `safeDrawing` 内边距；
顶栏 Row 与 `HorizontalDivider` 不参与滚动；内容区单独一个 `weight(1f) + verticalScroll`
的 Column。「点空白收键盘」的 `pointerInput` 顶栏和内容区各挂一份（子元素消费掉的点击
不会穿透，语义与 ⑥ 一致）。

**连带的教训：探测脚本的锚点判据必须跟着换。** 脚本原用"设置页里一句说明文字可见"
判断"在设置页"，但那句话会随内容滚出视口 —— 键盘一弹脚本就"找不到设置页"了。
顶栏固定后它才是稳定锚点：页面判据改成「完成」按钮存在与否（拍照页没有它），
"点空白"也优先点顶栏标题。**给会滚动的内容挑锚点，要挑不滚的那块。**

回归脚本的"滑回顶部"绕行方案同步升级成真断言：键盘弹起时断言「完成」
既在视口内、又在 `dumpsys window` 输入法窗口 `mGivenContentInsets` 算出的
App 可视底边之上。3 轮 21/21 通过（完成 y=113~176 / 可视底=946）。

---

## 3. 远端大模型：可配置是怎么做的

```
endpoints : List<Endpoint>          ← 唯一真相源，落盘
  └── Endpoint(id, baseUrl, model, apiKey, protocol, builtin)
        id       : 稳定 id。内置 = deepseek / claude，自定义 = ep:N（绝不由 URL 推导）
        protocol : deepseek | claude | openai-compatible
                   → 决定 temperature 上限与是否发 detail=low（协议族，不是"哪家"）
        builtin  : 内置端点可编辑/可重置/可删除（至少保留 1 条），删掉后可一键恢复

LlmConfig (落盘)                     ← 只剩运行参数 + 当前端点
  ├── activeEndpointId : 指向 endpoints 里的一条
  ├── sendImage        : true   ← 关掉就退化成纯文本形态
  ├── lowDetail        : true   ← 对应 detail=low（512×512 下采样），协议不支持则自动跳过
  ├── temperature / maxTokens / timeoutMs
  └── extraInstruction ← 拼进 system prompt 的个性化要求
```

**换服务商不改代码**：通义 / 豆包 / 自建 vLLM / One-API 网关只要兼容
`/chat/completions`，点「＋ 新增端点」填三元组即可；跑 Claude 模型的中转站把协议族
选成「Claude 兼容」，避免 `detail=low` 被网关拒。

**为什么 baseUrl/model/apiKey 不在 config 里**：同一份三元组存两遍，就会出现
"config 改了、列表还是旧值"的不可判定态。只留一份，就没有需要同步的两份（§2.3 ⑤）。

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
| AGP | **8.11.1** | 受 Android Studio 版本上限约束（见下） |
| Gradle | **8.13** | AGP 8.11 要求 >= 8.13；本机缓存已有 |
| CameraX | **1.5.3** | — |
| compileSdk / minSdk / targetSdk | 36 / 26 / 36 | 本机已装 android-36 |

**为什么不用最新的 CMP 1.12.1 + Kotlin 2.4.21**：CMP 1.12 线要求
`compileSdk 37`，而本机只装到 36；且 AGP 需要 9.x。为了"能在本机真的构建出来"，
选了 1.11.1 这条线。**升级是一个独立动作，不该和开工混在一起。**

**为什么 AGP 是 8.11.1 而不是 8.13.0**：这与上游库无关，纯粹被 **Android Studio
的兼容上界**卡住。AS 2025.1.1（Narwhal）官方只支持 **AGP 3.2~8.11**，用 8.13.0
会在 Sync 阶段被 IDE 直接拒绝，报
`The project is using an incompatible version (AGP 8.13.0) ... Latest supported version is AGP 8.11.1`。

各版本的上界（来源：https://developer.android.com/build/releases/about-agp ）：

| Android Studio | 支持的 AGP 上界 |
|---|---|
| 2025.1.1 Narwhal | **8.11** |
| 2025.1.2 Narwhal Feature Drop | 8.12 |
| 2025.1.3 Narwhal 3 Feature Drop | 8.13 |
| 2025.2.1 Otter | 8.13 |

取 **8.11.1** 而非已发布的 8.11.2，因为 AS 的版本检查就是按 8.11.1 这个上界判定的。
GitHub Actions / CI 上的命令行构建不受此限制（Gradle 不检查 IDE 版本），
所以这个约束只在"用 AS 打开"时生效。

**升到 AGP 8.13 的前提**：把 AS 升到 2025.1.3（Narwhal 3 Feature Drop）或更高。
这是独立动作，且对本项目**没有功能收益**——`composeApp/build.gradle.kts` 只用了
最基础的 `android { }` 配置块，8.11 与 8.13 在编译结果上无差异。

---

## 6. 踩到的坑（都已修，记在这里省下次的时间）

| # | 现象 | 原因 | 修法 |
|---|---|---|---|
| 1 | `Unresolved reference: camerax` | 版本目录别名 `camerax-core` 的访问器是 `libs.camerax.core`，我写成了 `libs.androidx.camerax.core` | 别名统一改为 `androidx-camerax-*` |
| 2 | `Const 'val' initializer must be a constant value` | `const val X = """…""".trimIndent()` —— `trimIndent()` 不是编译期常量 | 改成 `private val` |
| 3 | `Platform declaration clash: setIntent(...)` | `var intent` 自动生成的 JVM setter 与我手写的 `fun setIntent()` 签名相同 | 方法改名 `updateIntent` / `selectPerception` |
| 4 | AS 打开项目报 `incompatible version (AGP 8.13.0) ... Latest supported version is AGP 8.11.1`，Sync 被拒（命令行 `gradlew assembleDebug` 却完全正常） | AS 对 AGP 有**独立的兼容上界**，与 Gradle 无关。AS 2025.1.1 只到 AGP 8.11 | `libs.versions.toml` 里 `agp = "8.11.1"`；已在 AGP 8.11.1 下重新 `assembleDebug` 验证通过 |
| 5 | `Return type mismatch: expected 'LlmResult', actual 'Any'`（`OpenAiCompatibleProvider.kt:89`） | 手改时 `} catch (e: Throwable) {` 被提前一行，`catch (e: CancellationException) { }` 变成**空体**（值类型 `Unit`），`try` 表达式类型塌成 `Any`；同时 `throw e` 之后的 `platformLog` / `LlmResult.Failure` 成了死代码 | 恢复顺序：`TimeoutCancellationException`（→ `Failure(TIMEOUT)`）→ `CancellationException`（→ `throw e`，保结构化并发）→ `Throwable`（→ log + `Failure`）。顺序不可调换，前者是后者的子类 |
| 6 | 设置页"点 DeepSeek 卡会把手改的 Base URL 重置回出厂值" | 预设卡是**无状态**的：点击 = 无条件回填 `preset.baseUrl` / `defaultModel`，所以「改成 `…/v1` → 切 Claude → 切回 DeepSeek」必然丢手改值。顺带第二个坑：`apiKey` 是全局单个，切到 Claude 还在发 DeepSeek 的 Key | 先补 `ProviderProfile` 快照表（见 §2.3 ④），同日进一步**取消了快照表**：预设与自定义合并成单一 `endpoints` 列表，见坑 7 |
| 7 | 同上问题的根因复发面：`providerProfiles` 与 `savedEndpoints` 在存同一类东西 | 同一份三元组被存两遍（config 一份、列表/快照一份）→ 必然产生"选中了 A 但在用 B"这种不可判定状态，两个 bug 只是它的两种表现 | 收敛成**一个 `endpoints` 列表 = 唯一真相源**：输入框直接编辑当前端点（改一次落盘一次），删掉快照表 / ＋保存按钮 / 手改提示；`id` 改为稳定 id（不再由 baseUrl+model 推导）。见 §2.3 ⑤ |

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

---

## 9. 取景参考线（常用构图工具，2026-10-19）

**做了什么**：取景器上叠加一组可切换的构图参考线，入口在顶栏「构图」按钮。

| 档位 | 内容 |
| --- | --- |
| 关闭 | 默认，不画任何线 |
| 三分线 | 1/3 线 ×4 + 四个交点圆圈强调 |
| 黄金分割 | 0.382/0.618 线 ×4（虚线）+ 交点 |
| 中心十字 | 中心留缺口的十字 + 中心圆 + 四边中点刻度 |
| 对角线 | 两条对角线（虚线）+ 中心小十字 |
| 黄金螺旋 | 内接黄金矩形（虚线）+ 斐波那契分块四分之一圆弧 + "眼睛"圆点 |
| 安全框 | 8% 内缩虚线框 + 四角加粗切角 |

**实现**：

- `guide/CompositionGuide.kt`：枚举（displayName + 一句话用法 tip）
- `ui/GuideOverlay.kt`：纯 Canvas 叠加层，不消费触摸、NONE 时直接 return（零开销约定）
- `ui/CameraScreen.kt`：顶栏「构图」按钮（选中态高亮）+ 底部选择面板
  （LazyRow chips + 当前档位的一句话 tip）；面板**叠在提示卡上方**
  （提示卡实测高度 onSizeChanged，不猜 dp）；点空白收起（已并入空白点击层条件）
- `state/AppState.kt`：`guide` 状态 + 落盘 `composition_guide_v1`（枚举 name），
  读到未知值回退 NONE 并打日志（不静默）

**踩坑（都已修）**：

1. **黄金螺旋不能拿全屏比例直接分块** —— 切正方形的构造只对黄金比例成立，
   9:19.5 的屏幕第二块之后圆弧就接不上了。修法：居中内接 1:φ 黄金矩形再分块。
2. **圆心不能每个正方形随便选** —— 相邻圆弧在衔接点必须共圆心线且同侧
   （`C' = J + (C−J)·(s'/s)`），否则切线反向，画出来是"花瓣"。第一版参数式
   极坐标画法还会让外圈飘出取景框，均已废弃。
3. **本机环境变化**：Android Studio 的 JBR 升到了 JDK 25，Gradle 8.13 直接构建报错
   （What went wrong: 25.0.3）。构建时需指定 JDK 21：
   `./gradlew.bat :composeApp:assembleDebug -Dorg.gradle.java.home="C:/Users/HP/.jdks/jbr-21.0.7"`

**验证**：模拟器 `emulator-5554` 实测通过 —— 各档位渲染正确（截图核对）、
选择跨重启记忆（SharedPreferences）、点空白收面板、无 FATAL。

**刻意不做**：水平仪。需要加速度计（SensorManager / CoreMotion 的 expect/actual），
是独立一块工作，不顺手塞；要做时单独排。

---

## 10. 参考线自动推荐（按意图选工具，2026-10-19）

**需求**："能否根据意图选择合适的构图工具？" → 参考线从"只能手选"升级为
**默认自动跟随意图，可手动锁定**。

**模型**（单一真相源，不存两份）：

```
manualGuide: CompositionGuide?   // 唯一落盘的字段；null = 自动
guideRecommendation              // 每次由意图推导，纯函数 GuideRecommender.recommend(intent)
guide = manualGuide ?: recommendation?.guide ?: NONE   // 生效值，UI/绘制只读这个
```

- 落盘 key 仍为 `composition_guide_v1`：枚举 name = 手动锁定；`"AUTO"` = 自动；
  **无存储 = 自动**（新默认）。读到未知值回退自动并打日志。
- ⚠️ 教训同端点三元组：绝不把"当前生效的参考线"再存一份，推导值存两份必然出现
  "面板说 A、画面画 B"。

**推荐规则**（`guide/GuideRecommender.kt`，纯函数无平台依赖）：

1. **描述关键词 > 场景预设**：用户写的"对称""斜着摆""螺旋"比预设更具体；
2. 预设映射：半身/全身/宠物→三分线，双人→中心十字，多人→安全框（最怕裁掉人），
   美食→对角线，静物/空间→黄金分割，风景人像→黄金螺旋；
3. **没有把握返回 null**（不画线），乱画比不画更糟；
4. 每条推荐带 `reason`（一句话为什么），UI 原样展示——"智能推荐"四个字没有价值。

**交互**：

- 面板首项「自动」，chip 上直接写"自动·三分线"（替用户选了什么可见）；
- 手动点任何一档（含关闭）= 锁定，换场景不再自动改；点「自动」恢复跟随；
- 顶栏按钮带出生效档位（"构图·三分线"），自动换线时不用开面板也能发现；
- 自动模式下换意图打日志：`参考线自动 → 安全框（多人合影：…）`。

**提示词闭环**：`CompositionPrompt.buildUserPrompt` 新增 `guide` 参数，USER 段加
【屏幕上的参考线】：开着线时要求模型的位置建议**必须引用画面上真实存在的线**
（"把她的眼睛放到右上交点"），关闭时明确禁止引用不存在的参照。
`CompositionGuide` 相应新增 `promptHint`（给模型的几何描述）。
HintEngine/AppState 在发起分析那一刻对 intent+guide 取快照，防中途改芯片串味。

**验证**（模拟器实测）：自动默认+人像→三分线；多人合影→安全框（截图核对）；
全身→三分线；手动锁中心十字→切多人合影不变；锁定/自动均跨重启持久化；
预演提示词含【屏幕上的参考线】段；logcat 自动链路完整；无 FATAL。

**已知边界**：描述关键词匹配无法在模拟器上验证（`adb input text` 不支持中文输入），
规则本身是纯函数，等 commonTest 接入后补单测（词表在 `GuideRecommender.textRules`）。

---

## 11. 底部意图面板可折叠（2026-10-19）

**需求**：底部栏遮挡画面 → 可收起到左侧（拍摄按钮保留）；向右拖固定显示、
向左拖收起；收起后体积太小，需要动画提示。

**结构**（`ui/CameraScreen.kt`）：

- 底部控制区拆成两块，**职责分离**：
  ① 意图面板（场景芯片 + 描述输入）→ `CollapsibleIntentPanel`，整体左移出屏幕，
     只在左边缘留一条把手（`CollapseHandle`，26×92dp）；
  ② **拍摄按钮独立**于面板之外（面板底、屏幕底），任何折叠状态都原地不动 ——
     收起是为了看画面，不是为了不能按快门。
- 折叠进度是 `Animatable<Float>`（1=展开 0=收起），**不是布尔**：拖动要跟手。
  落盘只存吸附后的布尔（`input_panel_collapsed_v1`），默认展开。
- 提示卡/参考线面板的底边 = 按钮区高度 + 面板高度 × fraction，用 `Modifier.offset{}`
  在 layout 阶段读动画值 —— 拖动 60fps 时**不触发整页重组**；
  面板自身的平移同样只在 `offset{}` lambda 里读。

**手势（三个入口，各有分工）**：

| 入口 | 手势 | 说明 |
| --- | --- | --- |
| 左侧把手 | 向右拖 = 展开；点击 = 切换 | ⚠️ 只负责向右！把手贴屏幕左缘，从它向左最多拖 26dp，永远够不到吸附距离（实测踩过） |
| 面板顶部拖拽条（grabber，bottom sheet 惯例） | 向左拖 = 收起；点击 = 切换 | 面板背景几乎被满宽的 LazyRow/输入框盖住（起手在那儿是滚动/选词），没有 grabber 用户就摸不到"拖动收起" |
| 面板背景 | 向左拖 = 收起 | 挂在 Box 上，子组件（芯片/输入框）在事件分发上优先，不冲突 |

- **吸附**：按"这次拖动的净位移"判断 —— 移动超 30% 行程切到该侧，不足按位置就近。
  不能只看终点位置：从收起态向右拖 300px，终点仍在收起侧，但意图明显是"拉出来"。
- 把手申请了**系统手势豁免**（`platform/SystemGesture.kt` expect/actual，
  Android = `systemGestureExclusion()`，iOS no-op）：把手贴左缘正是 Android 10+
  侧滑返回区，不豁免的话一拖就被系统截走退出 App（实测踩过）。

**动画提示（收起后"这里有东西"）**：

- 把手呼吸位移（±4dp，1.2s 往返）+ 透明度脉动（0.55↔1.0）+ 朝右箭头（Canvas 画的
  chevron，不用字符 —— 缺字形会变方框）；
- **只在收起态跑**（`if (collapsed) rememberHandlePulse() else 0f`），
  展开时它是普通按钮，不做每帧动画 —— 流畅性纪律。

**踩坑（都已修）**：

1. 把手向左拖不出吸附距离（见上表）→ 拖动收起必须挂面板/拖拽条；
2. 把手拖动被系统返回手势截走 → `systemGestureExclusion`；
3. `clearFocus()` 不保证 IME 收起（§2.3 ⑥ 的老坑重现）→ 拖动开始时
   `keyboard?.hide()` + `clearFocus()` 都要调，实测 `mInputShown` 验证；
4. `updateInputPanelCollapsed` 的命名：`var inputPanelCollapsed` 的自动 setter
   是 `setInputPanelCollapsed`，手写 `fun setInputPanelCollapsed()` 会撞 JVM 签名
   （同 `setIntent` 的老坑，方法名避开 set 前缀）。

**验证**（模拟器实测）：展开↔收起拖动（把手向右 400px 展开、拖拽条向左 450px 收起）、
不足 30% 弹回、点击把手双向切换、拍摄按钮收起后仍浮在画面上、提示卡/参考线面板
在收起态正确定位（不压把手）、拖动收起时键盘自动收起（`mInputShown` 前后对照）、
收起态跨重启持久化、呼吸动画帧差验证（两截图逐像素 diff 显著）、无 FATAL。

---

## 12. 前后摄像头切换（2026-11-12）

**需求**：取景只有后置一颗用到死。前后切换是相机 App 的基本能力（拍人像指导的
主场景就是"帮别人拍"，但自拍核对姿势也需要前置）。

**接口**（`capture/CaptureController.kt`）：

- 新增 `enum CameraFacing { BACK, FRONT }`，**刻意做成封闭二值**：`other` 唯一确定
  "另一颗"。外接/长焦/超广不在 Phase 1 范围，不做成列表；
- 接口新增 `facing` / `canSwitchFacing` / `switchFacing()` / `notice` 四个成员；
- `canSwitchFacing=false`（单摄设备）→ UI 直接禁用按钮。点不动比"点了没反应"诚实；
- **`notice` 为什么单独一条通道**：切换失败经常"已回滚到原镜头"（预览还是好的），
  用 `CameraState.Failed` 会谎报相机坏了；静默回滚又等于骗用户"切了"。所以失败原因
  走 `notice` 独立显示，新的尝试开始时自动清掉。这是"绝不静默失败"的第三个出口
  （拍照 onError、分析 failure.banner、切换 notice）。

**Android（CameraX）**：

- `bind()` 拆成 `pickFacing()`（挑镜头，含降级）+ `bindUseCases()`（真绑定）两步，
  切换复用绑定路径 —— 只有"绑哪颗"在变，"怎么绑"必须只有一份；
- `hasCameraSafe()` 扩展：`provider.hasCamera` 在个别机型会抛，一律降级成"没有"，
  不让探测炸穿绑定流程；
- 切换时序：**快门先落**（`_canCapture=false`，unbind→rebind 窗口里 takePicture 必抛）
  → 主线程 `unbindAll()` → 按新朝向重绑 → 恢复 `canCapture`；
- 切换失败**回滚到原镜头**：预览已经 unbind 了，不拉回来就是一块黑屏。回滚也失败
  才报 `Failed`。

**iOS（AVFoundation）**（写而不能编，签名以 Xcode 首次编译为准）：

- 不重建 session（预览层绑的是 session，重建会闪屏且要重新 startRunning）；
  只做 `beginConfiguration → removeInput(旧) → addInput(新) → commitConfiguration`；
- 换 input 用 `defaultDeviceWithDeviceType(builtInWideAngleCamera, position, video)`。
  **只用广角**：超广/长焦的 framing 与"所见即所得"差太远，混进来会让参考线画的位置
  和实际画面对不上；
- 失败回滚：`canAddInput=false` 时把旧 input 加回去并写 `notice`。

**UI（`CameraScreen`）**：

- 切换按钮放**快门右侧**（相机 App 通用位置，拇指直达），`align(CenterEnd)` 而不是
  挤进 Row —— 快门必须绝对居中，否则构图参考线和画面中心对不上；
- 按钮结构 = ⇄ 图标（Canvas 画，缺字形会变方框的老原则）+ 下方**当前镜头名**：
  图标说"能做什么"，字说"现在在哪颗"。两颗镜头对着同一面墙时画面几乎没变化，
  没有文字就无法确认切没切成功；
- 顶栏状态同步带镜头名（"相机就绪 · 后置"）；
- 切换是纯相机动作：不改意图、不改参考线状态。

**验证**（真机 TAS-AN00 / Mate 60 实测，2026-11-12）：

- 后置 → 前置 → 后置 → 前置往返切换，顶栏标签每次实时更新，无 FATAL；
- 切到前置后拍照：照片正常产出并走到分析入口（该机未配端点，
  `NOT_CONFIGURED` 提示按预期出现）—— 重新绑定后的 ImageCapture 工作正常；
- 截图：`build/shots/pg_2_front.png`（前置）/ `pg_3_back.png`（后置）/
  `pg_4_capture.png`（拍照后提示）。

**已知边界**：

1. 前置拍照的 JPEG 镜像策略未单独处理（CameraX 默认镜像前置输出以匹配预览），
   构图判断不受影响；若日后把照片存盘/回显给用户，需再确认预期；
2. iOS 侧同样只写未编，切换路径的 cinterop 签名待 Mac 上首编核对；
3. 切换过程中状态短暂显示"相机启动中…"（unbind→rebind 窗口），实测 <1s，可接受。

---

## 13. 快门手势化 + 拍照自动入库（2026-11-12）

**需求**：①独立切换按钮太占地方 → 并进快门：点 = 拍照、左右滑 = 换镜头；②拍照后
不管分析结果如何，照片必须存进系统相册并通知媒体库。

### 13.1 快门手势（`ui/CameraScreen.kt`）

- `CaptureButton` 签名扩成 `facing` / `canSwitch` / `onSwitchFacing`；独立
  `SwitchFacingButton` / `SwapGlyph` 删除。快门仍绝对居中（下方标签随 Column 居中，
  视觉重心略升 ~9dp，可接受）；
- **点与滑共存的关键**：位移超过 touch slop 后横向拖动检测消费事件，`clickable`
  的 tap 随之取消；轻点则 drag 永远等不到 slop。两条手势互不误触，不需要状态机；
- 拖动跟手用 `Animatable` + `offset{}`（layout 阶段读值，不重组整页）；
  `snapTo` 必须 UNDISPATCHED（§2.3 老教训：默认 launch 排队到下一帧，同帧事件读旧值）；
- **松手看净位移不看方向**：前/后置与左/右没有天然映射，硬指定方向只会记错。
  位移 ≥40dp 切换，不足弹回（视觉反馈：按钮跟手平移，clamp ±56dp）；
- 单摄设备 `canSwitch=false` 时**根本不挂拖动检测器**；标签降级成只显示镜头名；
- 标签是唯一的手势提示（"后置 · 左右滑切换"）——"能滑"不写出来没人会试。

### 13.2 拍照自动入库（`CaptureController.android.kt` / `.ios.kt`）

- **时机**：`onCaptureSuccess` 拿到 bytes 后立即异步保存，与"分析结果"无关 ——
  用户按了快门就该留下东西，分析超时/端点没配都不能影响照片的存在；
- Android：单线程 daemon executor（主线程写 MediaStore = 每拍一张卡一帧预览；
  串行还避免同毫秒文件名撞车）。`MediaStore + IS_PENDING` 两步写（防相册读到半张图）；
  写失败**删掉半成品记录**（相册里一张点不开的图比没存更糟）。29+ 插入即自动通知
  媒体库，不需要手动 scanFile。目录 `DCIM/PhotoGuide`（国产相册按 DCIM 建相册）；
- `< 29`：写公共目录需要 WRITE_EXTERNAL_STORAGE + 运行时申请，Phase 1 明确降级
  提示，不假装存了；
- iOS：临时文件 → `PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL`
  → 用完删临时文件；`Info.plist` 补 `NSPhotoLibraryAddUsageDescription`
  （Add-only 授权）。cinterop 签名未实测，编译器提示改名即可，逻辑不动；
- **失败可见性**：保存失败走 `notice`。但横幅主位会被分析错误（如 NOT_CONFIGURED）
  占住 → 横幅加**双行**：`bannerText` 为主，`cameraNotice` 不等于主文案时补一行
  ⚠ 小字。静默丢掉任何一个都算谎报；
- 保存成败都写 logcat（`PhotoGuideGallery` tag）—— UI 提示可能被遮，日志是第二现场。

### 13.3 验证（真机 TAS-AN00，2026-11-12）

- 快门左滑 → 前置、右滑 → 后置、再左滑 → 前置（标签/顶栏实时更新，无 FATAL）；
- 前置拍照 → `已存入相册：DCIM/PhotoGuide (674330B)`，媒体库 `relative_path=
  DCIM/PhotoGuide/`、`is_pending=0`，相册可见；
- ⚠️ 排障记录：第一轮验证"相册没记录"实为**查询姿势错误** —— `adb shell content
  query --where "relative_path='DCIM/PhotoGuide'"` 查不到，因为媒体库的
  `relative_path` 带尾斜杠（`DCIM/PhotoGuide/`），`=` 精确匹配失败；用 LIKE 或
  全量统计才能对上。文件本身一直好好躺在磁盘上。教训：判断"保存失败"前先确认
  "查询成功"，磁盘文件是第一真相。

---

## 14. 建议卡带缩略图 + 全屏原图预览（2026-11-12）

**需求**：建议浮窗里要能看到本次拍的照片；点开可全屏预览原图，支持缩放/平移。

### 14.1 解码（`util/ImageDecode.kt` expect/actual）

- `decodeImageBitmapOrNull(bytes)`：**不能在 commonMain 直接用 skia** —— CMP 的
  skiko 只在非 Android 目标可用（首次实现踩到，`Unresolved reference 'skia'`）。
  拆三份：common expect；Android = `BitmapFactory` + `asImageBitmap`（全量解码
  不做 inSampleSize —— 预览要放大看细节，采样省内存换不来"放大就糊"）；
  iOS = skia `Image.makeFromEncoded`（写而不编）；
- 失败返回 null：建议卡降级成"无图"，建议文字照常 —— 图片是辅助，
  绝不因它挂掉吞掉主功能，但日志必留（platformLog）；
- 调用方在 `LaunchedEffect(lastPhoto)` 里 `withContext(Dispatchers.Default)` 解码
  （~700KB JPEG 全量解码不能在主线程）。

### 14.2 建议卡缩略图（`ui/HintCard.kt`）

- 新参数 `photo: ImageBitmap?` + `onPhotoClick`；缩略图行在标题行下、正文前，
  **收起/展开都显示** —— "图文同屏才能照着改"，藏进展开态等于没有；
- 尺寸策略：统一高 104dp + `aspectRatio(位图实际比例)`，横竖拍都不变形；
- null 整行不渲染。

### 14.3 全屏预览（`ui/PhotoPreview.kt`）

- "原图"= CapturedPhoto 本身（~1600px，与发给模型的同一份，没有更高清副本）；
- 变换只在 `graphicsLayer{}` lambda 读 —— 缩放/平移全程不重组（流畅性纪律）；
- **锚点缩放**：`offset' = offset + (p - center)·(scale - newScale)`，捏合中心不动；
  scale ≤1.01 强制归位 —— Fit 模式下允许平移只会把图拖出屏幕，看起来像卡死；
- 双击 1x↔2.5x，scale 和 offset **逐帧同时插值**（只动 scale 会看到图片瞬移）；
- 关闭只有右上 ✕：不加"点空白关闭"（缩放调整时轻点极易误触）；
  返回键不支持（浮层不是 Dialog），需要时再升级。

### 14.4 真机实测踩的坑（都已修/定位）

1. **✕ 点不动**：按钮 top=12dp 贴顶，中心落进系统状态栏下拉手势区，tap 被系统
   吃掉。修 = `statusBarsPadding()`。教训：**全屏浮层上的顶部控件必须避让状态栏**，
   "预览从屏幕 0,0 开始"不代表可以贴 0,0 摆控件；
2. **双击"没反应"的假象**：`adb shell input tap` 两条命令间隔 >300ms（双击窗口），
   重试多次才偶尔命中 —— 是**注入工具的间隔问题，不是代码 bug**（加日志确认
   onDoubleTap 正常触发）。真手指没有这个问题。给手势回调加临时日志是定位
   "注入没生效 vs 逻辑没生效"的最快手段；
3. 纯色墙面照片验证缩放不可辨 —— 用 **Fit 模式的上下黑边是否消失**当视觉证据。

### 14.5 验证（真机 TAS-AN00，端到端）

拍照 → 建议卡出现（含缩略图、分数、台词、参考线入口）→ 点缩略图 → 全屏原图 →
双击放大（黑边消失、纹理放大）→ ✕ 关闭回到取景，全程无 FATAL。截图
`build/shots/p1_hint.png` / `p8_preview2.png`。

---

## 15. 展开态建议卡溢出屏幕（修复，2026-11-12）

**现象**（真机实测复现）：点"展开"后整张卡片被顶出屏幕顶端 —— 分数、标题行、
"收起/✕"按钮全部被裁掉（UI dump 里它们的 bounds 贴着 y=0）。

**根因**：卡片是**底部锚定向上生长**的，旧实现只给正文 `heightIn(max=360dp)`，
但卡片总高 = 标题行 + 缩略图(104dp) + 正文(≤360dp) + 参考线条目 + 内边距 ≈ 570dp，
再叠上底部控制区（~230dp）后顶出屏外。**限正文 ≠ 限整卡**。

**修法**（`ui/HintCard.kt`）：

- 外层 `BoxWithConstraints` 包住整卡，总高 `heightIn(max = maxHeight·55%)`
  （可用高度的 55%：底部控制区约 25% + 顶栏约 12% + 呼吸空间）；
- 展开正文改为 `weight(1f, fill = false) + verticalScroll`：内容少时按内容收缩，
  超出时**只在剩余空间里滚** —— 标题行、缩略图、参考线条目恒定可见（固定不滚）；
- 缩略图/参考线放在滚动区**外**：它们是"建议的依据/出口"，滚没了等于没有。

**验证**（真机 TAS-AN00）：4 机位+3 要点+2 问题+2 警告的富内容展开，
标题行完整可见（y≈213，不再贴 0），正文可滚（滑动后第二条滚入视口），
收起恢复正常，无 FATAL。本轮分析 guideRecommendation 为 null，参考线条目
未渲染属数据驱动（该行为可选，非布局回归）。

**教训**：底部锚定的浮层限高必须限**总高**，只限内容区挡不住"固定块叠加"；
`weight(1f, fill=false)` 是"剩余空间滚动 + 内容少时收缩"的标准写法。

---

## 15.1 建议卡头部改版：标题并排缩略图（2026-11-12）

**需求**：标题单行截断；把标题挪到缩略图右侧，图片上移、整体更紧凑。

**改动**（`ui/HintCard.kt` 头部）：

- 旧结构：标题行（分数+标题+展开/✕ 横排抢宽）→ 图片独立一行 → 正文；
  标题被压成一行省略号，图片又把卡片上半截撑高 ~110dp；
- 新结构：`Row[缩略图 | Column(分数+标题 + 收起首句) | 竖排操作(展开/✕)]`；
- 标题 maxLines：有图 3 行（右侧整块宽度够），无图退回 2 行；
- **收起态首句/计数/警告也并进图片右侧**——图片旁边的高度不浪费，
  收起卡整体矮一截（实测：标题两行完整、首句、计数、警告全部同屏）；
- 展开/收起与 ✕ 改为**右上竖排**：并排后横向空间紧张，横排会把标题挤回截断；
- 无图时收起态首句回落到头部下方（有图时已在右侧渲染，避免画两遍）。

**验证**（真机 TAS-AN00）：收起态标题两行完整不截断、缩略图左侧、
操作竖排右上；展开态双栏正常、收起/✕ 在顶栏下方不被裁切、正文可滚、无 FATAL。
截图 `build/shots/g1_collapsed.png` / `g2_expanded.png`。

---

## 16. 拍摄历史记录（2026-11-12）

**需求**：加历史记录列表；**不进数据库，最多 20 条**（用户拍板）。

**数据模型**（`state/HistoryEntry.kt`）：

- 一条 = 拍摄时间戳(id/排序/列表 key 三合一) + 标题 + 评分 + 当时意图快照
  （场景标签 + 描述）+ 当时参考线 + 模型名 + **完整 `CompositionHint`** + 缩略图 base64；
- 完整存 hint 而不是只存标题：历史的意义是回看"当时两栏到底说了什么"，
  复用同一个 @Serializable DTO，不另造精简版（另造必漏字段）；
- 存储 = 一个 JSON 数组整体塞进**已有的** SettingsStore（`photo_history_v1`）。
  20 条、只追加、整体重写，引 Room/SQLDelight 属于纯负担；
- **缩略图必须另做小图**：原图 ~700KB，20 条 base64 后 14MB+，prefs 扛不住。
  长边 240px / 质量 60 → 单张 7~20KB，20 条 200~400KB，这是"不要数据库"成立的边界。

**缩略图生成**（`util/Thumbnail.kt` expect/actual）：

- commonMain 没有任何"位图→JPEG"的编码 API（Compose 的 ImageBitmap 只读），
  必须两端各写一份：Android = inSampleSize 粗降采样 + createScaledBitmap 精确到位 +
  compress(JPEG)，全程 recycle 临时位图；iOS = skia `Surface.makeRasterN32Premul` +
  `drawImageRect` 缩放重绘 + `encodeToData(JPEG)`；
- 失败返回 null：条目照常入库，列表显示「无图」占位（绝不静默，日志留痕）。

**配套工具**：

- `Base64.decode` 补齐（原来只有 encode）：宽容解析（忽略空白/填充），
  非法字符返回 null 不抛异常；
- `util/WallClock.kt`：`nowEpochMs()` expect/actual（System.currentTimeMillis /
  NSDate）。**刻意不做绝对日期格式化**——那要时区（kotlinx-datetime 不值得为它引入），
  历史列表用相对时间（"刚刚 / 12 分钟前 / 3 小时前 / 2 天前 / x 周前"）反而更好读。

**入库时机**（`AppState.recordHistory`）：分析**成功**才记（失败没有建议可回看）；
缩略图生成放 `Dispatchers.Default`（几十毫秒的 CPU 活，别卡"建议卡刚出现"那一帧）；
id 用 `maxOf(nowEpochMs(), 上一条 id + 1)` 保证严格递增——LazyColumn 重复 key 会直接崩，
正确性不押在"连拍不可能同毫秒"上；写入整体 runCatching，历史是旁路功能，
失败只记日志、绝不把一次成功的分析弄崩。

**UI**（`ui/HistoryScreen.kt`，全屏浮层，与设置页同款"固定顶栏+滚动区"）：

- 收起态卡片 = 时间·场景 / 分数徽标 / 标题 / 最有用一句；点卡片或「展开」给完整
  双栏 + 问题 + 警告 + 当时描述 + 参考线/模型名；
- 只读，**不提供"套用这条设置再拍"**——那会变成第二个意图入口，与底部面板
  抢同一份状态（"同一件事两条写入路径"是本项目反复踩的坑）；
- 清空 = 两段式确认（点一下变「确认清空」，4 秒不点自动收回）；
- 缩略图懒解码：base64→字节→位图都在 `Dispatchers.Default`，
  produceState 按条目 key 触发，只在可见时才跑。

**顶栏第四个按钮的空间问题**：历史进来后顶栏 4 chip + 标题，360dp 宽度下
「构图·黄金螺旋」这种会变长的标签会把标题压成省略号。解法：按钮标签固定「构图」，
**当前档位挪到左侧状态行**声明（"相机就绪 · 后置 · 对角线"）——换线时依旧
第一时间可见，信息没少，只是换到更能容纳它的位置；状态行 maxLines=1 防止换行撑高顶栏。

**验证**（真机 TAS-AN00）：空状态文案完整；拍照分析成功后 logcat
`历史 +1：共 1/20 条（缩略图 7KB）`；列表卡片（缩略图/时间/场景/分数/标题/首句）正常；
展开态「你（摄影师）」4 条 + 「画面要点」3 条完整可滚；「清空→确认清空→0/20 空状态」通过。

**未验证 / 已知边界**：

- iOS 侧 Thumbnail/WallClock actual 按既有惯例"写而不能编"（本机无 Xcode）；
- 跨启动持久化走 SettingsStore（与端点列表同一机制），未单独做重启用例；
- 相机状态行在"前置 + 开线"时可能截到省略号（信息降级可接受，未优化）。

### 16.1 修订：历史入口撤出一级界面（2026-11-12，§16 发布同日）

**用户新规则**：**以后新增按钮一律不加在一级界面（相机页），收纳进二级界面。**
相机顶栏固定为「标题 + 构图·档位 + 调试 + 设置」，不再扩容 —— 取景是主任务，
低频动作不配占一格；且顶栏宽度已到极限（第 4 个按钮曾把标题压成省略号）。

按此规则回滚 §16 的两处顶栏改动、迁移入口：

- CameraScreen：撤掉「历史」chip；「构图·xxx」标签与纯状态行**恢复原样**
  （§16 里"档位挪状态行"的让路理由已不存在）；
- SettingsScreen：顶部（模型服务之前）加一行 `HistoryEntryRow`：
  「拍摄记录 · 最近 x/20 条 · 查看 ›」，空态文案改"还没有记录 · 拍完自动留下最近 20 条"；
  放**最顶部**而不是最底部 —— 入口要翻一屏半才找得到就等于没有，一行紧凑卡片
  也不打乱下面既有的配置顺序；
- 历史页从设置进、「完成」回设置（App.kt 浮层叠放次序天然支持，无新逻辑）。

**验证**（真机 TAS-AN00）：顶栏恢复三按钮 + 档位回按钮标签；设置页首行入口渲染正确
（含空态文案）。设置→历史的点击走查被真机上的实际操作打断（设备当时在用户手里），
未重复自动化验证 —— 该入口与 §16 已验证的 `openHistory` 是同一条路径，无新逻辑。

### 16.2 历史缩略图支持点击全屏查看（2026-11-12）

**需求**：历史记录里的图片也支持点击查看。

**实现**：

- `HistoryThumb` 加 clickable（内层点击先被消费，与"点卡片其它地方展开"天然共存）；
- 预览浮层挂在**页面根节点**（`Box` 包住 Column），不挂进 LazyColumn 的 item ——
  挂 item 里会随滚动被裁切/回收，也盖不住顶栏；`previewId` 状态提在页面层；
- 复用 `PhotoPreviewOverlay`（捏合/双击/平移全套手势），新增可选参数 `note`：
  显示在底部手势提示之上的一行说明；
- **无图的条目不可点**（`thumbBase64 == null` 时不进预览）——宁可什么都不发生，
  也不弹一个空白全屏，那看起来像崩了。

⭐ **必须说明"这是缩略图不是原图"**（note = "这是历史里的缩略图（长边 240px），
放大后会糊；原图已存入系统相册"）：历史只存 240px 小图是既定的存储取舍，
放大必然糊；不写这行，用户只会觉得"这 App 拍照好糊"——
把取舍的代价伪装成画质问题，属于静默失败。列表页脚注也同步加了"点缩略图可全屏查看"。

**验证**（真机 TAS-AN00）：点缩略图 → 全屏预览（✕ 在状态栏下方、
底部说明行清晰）；✕ → 回列表（dump 确认浮层已关、2 条缩略图仍可点）。
