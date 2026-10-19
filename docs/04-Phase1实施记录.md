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
| 交互层 | 相机页 / 主次分层提示卡（原双栏，见 2.3 ⑧） / 设置页 / Debug 浮层 | ✅ |

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

**⑧ 提示卡从"一张卡装全部"改成主次分层的执行序列（2026-10-17）**

用户反馈很直接：**"提示就一个弹窗，体验不好"**。原形态收起时只给标题 + 一句预览，
其余 5~7 条全塞进「展开」后的 360dp 滚动区 —— 问题不在"弹窗"，在于**没有层级**：
`headline / 机位 / 台词 / 画面要点 / 问题` 权重相同地堆在一起，而"展开/收起"
本身是个要用户当场做的决策。

先考虑了"一次一条 + 翻页"，最终否掉。理由是**屏幕一次只显示得下一条时，切换必须有触发源**，
而三条触发源在当前都不成立：

| 触发源 | 为什么不行 |
|---|---|
| 用户点「下一条」 | 单手举着手机拍摄，没人愿意翻页 —— 与"尽量减少手动操作"直接冲突 |
| 定时自动翻（5s） | 读到一半字被换掉，比不显示更糟 |
| 画面变化（做完自动进） | 需要本地感知层，`PerceptionRegistry` 现在还是 `NoPerception` |

所以改成**一次呈现、视觉分层**：不翻页，靠字号与权重分出"现在做"和"还有"。

- `CompositionHint.steps: List<HintStep>` —— **派生只读**属性（`HintStep(kind, text)`），
  由现有四个数组按**执行时序**拼出：机位 → 台词/画面要点 → 问题诊断。
  不改提示词、不加字段，模型输出结构与存量数据都不受影响。
- 排序依据是执行时序而非主观重要度：**台词是差异化最强的一条，但不能排第一** ——
  机位错了还念台词等于白念。
- `warnings` **刻意不进 steps**：它是可信度信息（"输出被截断""没收到画面"），
  不是动作。混进序列里用户一翻就翻过去了，而它恰恰最不该被翻过去 ——
  改为卡片顶部常驻细条，展开/收起都不消失。
- 收起态 = 1 条主建议（17sp + 类型色条 + 类型标签）+ 2 条压暗小字（12sp，
  只负责让人知道"还有"）+ 「还有 N 条 ›」。**主条 0 次点击即可执行**，
  想看得更全才点。
- 类型配色固定语义：蓝 = 手（机位）、黄 = 嘴（台词）、绿 = 眼（画面要点）、灰 = 诊断。

> 顺带记住一个判断标准：**"一次一条"只有在能自动判定"这条做完了"时才成立**。
> 做不到自动判定，就只能靠用户翻页或定时翻，两者在拍摄场景都是负收益。

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
