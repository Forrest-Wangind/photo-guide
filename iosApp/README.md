# iosApp · iOS 宿主工程

> SwiftUI 外壳，负责把 `composeApp` 编出来的 `ComposeApp.framework` 挂起来。
> 这里**一行界面代码都没有**——取景器、双栏建议卡、设置页全在 `commonMain` 的 Compose 里，
> iOS 侧唯一做的事是把 `MainViewController()` 塞进一个 `UIViewControllerRepresentable`。

---

## 目录结构

```
iosApp/
├── iosApp.xcodeproj/
│   ├── project.pbxproj                          工程定义
│   └── xcshareddata/xcschemes/iosApp.xcscheme   ★ 共享 scheme（命令行构建也靠它）
├── Configuration/
│   └── Config.xcconfig                          TEAM_ID / BUNDLE_ID / APP_NAME
├── scripts/
│   └── compile-kotlin-framework.sh              Run Script 阶段调它：找 JDK + 跑 gradle
└── iosApp/
    ├── iOSApp.swift                             @main 入口
    ├── ContentView.swift                        UIViewControllerRepresentable → MainViewControllerKt.MainViewController()
    ├── Assets.xcassets                          AppIcon / AccentColor 占位（还没放图）
    └── Info.plist                               NSCameraUsageDescription + ATS 本地明文放行
```

`scheme` 那一项值得单独说：Xcode 默认把 scheme 存成**用户级**（`xcuserdata/`），
那个目录在 `.gitignore` 里。所以这里显式放了一份 `xcshareddata/` 的共享 scheme——
不这么做的话，`xcodebuild -scheme iosApp` 在别人的机器 / CI 上会直接找不到 scheme。

---

## 打开与构建

```bash
open iosApp/iosApp.xcodeproj
```

命令行（不开 Xcode，适合脚本 / CI）：

```bash
xcodebuild -project iosApp/iosApp.xcodeproj \
           -scheme iosApp \
           -sdk iphonesimulator -configuration Debug build
```

> 模拟器只能跑通 UI。**相机链路必须真机**——模拟器没有摄像头。

---

## 构建链路：为什么不用手工配 framework

工程里有一个 Run Script 阶段，名字叫 **`Compile Kotlin Framework`**，
排在 `Compile Sources` **之前**（顺序是关键，framework 得先就位才能链接）。

它的内容只有一行：

```sh
"$SRCROOT/scripts/compile-kotlin-framework.sh"
```

真正的逻辑放在 [`scripts/compile-kotlin-framework.sh`](scripts/compile-kotlin-framework.sh) 里，
而不是塞进 Run Script 文本框——两个原因：一是脚本不短，塞进 `project.pbxproj`
就变成一整行转义字符串，以后改它得跟 `\n` 和 `\"` 搏斗；二是这个脚本可以做点
Run Script 一行写不下的判断（见下）。

脚本做两件事：

**① 自己找一个 JDK 11+。** 这是踩过的坑，值得完整说一下：

Xcode 的构建环境**不继承你的 shell 配置**（不读 `.zshrc` / `.zprofile`），
所以 `JAVA_HOME` 通常是空的，`gradlew` 只好退回到 PATH 上碰巧存在的 `java`。
本机 PATH 上那个是 JDK 8，于是构建失败并报：

```
> Dependency requires at least JVM runtime version 11. This build uses a Java 8 JVM.
Command PhaseScriptExecution failed with a nonzero exit code
```

**这报错极具误导性**——它看起来像项目依赖配置有问题，实际只是"没找到合适的 JDK"。
所以脚本按 `JAVA_HOME` → Android Studio 自带 JBR → `java_home -v 21 / 17 / 11`
的顺序显式挑一个，并把它导出给 Gradle。

**② 执行 `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`。**

`embedAndSignAppleFrameworkForXcode` 是 KMP 官方任务，它从 Xcode 注入的环境变量
（`CONFIGURATION` / `SDK_NAME` / `ARCHS` / `BUILT_PRODUCTS_DIR` …）里读出本次构建的目标形态，
挑对应的 Kotlin/Native target 编出 `ComposeApp.framework`，再放进 Xcode 的产物目录。
**也因此这个任务不能在终端里单独跑**——没有那些环境变量，它无从判断该编哪个 target。

配套的三项设置**已经写进工程，不需要手改**（`xcodebuild -showBuildSettings` 可自查）：

| 设置 | 值 | 作用 |
|---|---|---|
| `FRAMEWORK_SEARCH_PATHS` | `$(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` | 去哪找 framework |
| `OTHER_LDFLAGS` | `$(inherited) -framework ComposeApp` | 链接它 |
| `INFOPLIST_FILE` | `iosApp/Info.plist` | 用仓库里这份，而不是 Xcode 自动生成的 |

> 脚本开头那段 `OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED` 判断是 KMP 模板的标准做法：
> IDE 想跳过 Gradle 调用时可以置这个变量，免得 IDE 里点一下就跑一次完整 Kotlin/Native 编译。

### 另外两个容易踩的地方

**① `gradlew` 必须有可执行位。** 脚本里调用的是 `./gradlew`，文件权限若是 644，
会以 `permission denied` 失败，而报错看起来像是 Gradle 的问题、很容易查错方向。
（本仓库的 `gradlew` 曾经就是 644，已修。）

**② `ENABLE_USER_SCRIPT_SANDBOXING = NO`。** Xcode 15 起该开关默认为 `YES`，
沙箱会挡住 Run Script 去访问工程目录之外的 Gradle 缓存，导致构建失败。
工程里已显式关掉；在 Xcode 14 上这个键无副作用。

---

## Config.xcconfig

换人 / 换机器时**只需要动这一个文件**，不用碰 `project.pbxproj`：

| 键 | 当前值 | 说明 |
|---|---|---|
| `TEAM_ID` | 空 | 模拟器不需要签名。上真机时填开发者 Team ID，或在 Signing & Capabilities 里勾自动签名选账号 |
| `BUNDLE_ID` | `com.photoguide.iosApp` | 与 Android 的 `com.photoguide` 区分开，两端可同时装在一台机器上 |
| `APP_NAME` | `iosApp` | 产物名 `iosApp.app`。**桌面图标下显示的名字不是它** —— 那是 `Info.plist` 里的 `CFBundleDisplayName`（「拍摄指导」） |

---

## 环境前提

- **macOS + Xcode**（苹果生态硬限制，没有替代路径）。**Xcode 版本有硬门槛，见下一节。**
- **JDK 21** —— Gradle 需要。从 Xcode 构建**不需要**你手动设 `JAVA_HOME`：
  `scripts/compile-kotlin-framework.sh` 会自己找（见「构建链路」）。
  但从终端直接跑 `./gradlew` 时需要，若你的 `java -version` 是 8/11：
  ```sh
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
- 首次构建会下载 Kotlin/Native 工具链（Gradle 依赖缓存 + `~/.konan`，约 1GB），
  之后增量构建不再重复下载。

---

## iOS 16.2 SDK 兼容处理（CMP 1.8.2）

CMP 1.11.1 引用了 iOS 26 的 `UIViewLayoutRegion`，iOS 16.2 SDK 无法链接。
把 CMP 降到 **1.8.2** 后，用 `nm -u` 检查 framework，缺符号从 5 个减到 2 个：

| 符号 | 引入方 | 最低 iOS | 1.8.2 后 |
|---|---|---|---|
| `UIViewLayoutRegion` | CMP 1.11.x | 26.0 | ✅ 消失 |
| `UITextSelectionDisplayInteraction` | CMP 1.11.x | 17.0 | ✅ 消失 |
| `UITraitUserInterfaceStyle` | CMP 1.11.x | 17.0 | ✅ 消失 |
| `UITextLoupeSession` | Kotlin/Native 平台库 + Compose 放大镜 | 17.0 | ⚠️ 仍在 |
| `UIAccessibilityTraitToggleButton` | Kotlin/Native UIKit 平台库 | 17.0 | ⚠️ 仍在 |

剩下两个来自 **Kotlin/Native 2.3.21 的平台库**，不是降级 Compose 能消掉的。
在 `Configuration/Config.xcconfig` 里让它们走动态查找：

```
OTHER_LDFLAGS = $(inherited) -Wl,-U,_OBJC_CLASS_$_UITextLoupeSession -Wl,-U,_UIAccessibilityTraitToggleButton
```

Compose 内部对放大镜有降级分支（字符串 `UITextLoupeSession is not available`），
iOS 16 上不会真正调用；App 链接已验证通过：

```sh
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO build -quiet     # BUILD SUCCEEDED
```

**升级到 Xcode 26 / iOS 26 SDK 后必须删掉这两行 `-Wl,-U`**，让系统正常提供符号。

核验缺符号的方法（阳性对照不能省）：

```sh
F=composeApp/build/bin/iosArm64/debugFramework/ComposeApp.framework/ComposeApp
nm -u "$F" | grep -E 'UIViewLayoutRegion|UITextLoupeSession|UIAccessibilityTraitToggleButton'
```

---

## 真机运行

1. `Configuration/Config.xcconfig` 填上 `TEAM_ID`（免费 Apple ID 也行）。
2. 数据线连真机，Xcode 里选这台设备（**不要选模拟器**）。
3. 首次运行后到「设置 → 隐私与安全性 → 相机」确认权限已授予。
   `Info.plist` 里已有 `NSCameraUsageDescription`，缺了它调用 `AVCaptureDevice` 会**直接崩溃**而非弹权限框。

> 本机当前没有连接 iPhone，因此只验证到“App 链接成功”，尚未在真机上启动。

---

## 模拟器

CMP 1.8.2 仍发布 `iosX64`，所以 `composeApp/build.gradle.kts` 已恢复三个 iOS target
（`iosX64` / `iosArm64` / `iosSimulatorArm64`）。已验证：

```sh
./gradlew :composeApp:linkDebugFrameworkIosX64
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 14' \
  CODE_SIGNING_ALLOWED=NO build
xcrun simctl install booted <DerivedData>/Debug-iphonesimulator/iosApp.app
xcrun simctl launch booted com.photoguide.iosApp
```

App 已在 iPhone 14 模拟器启动成功，主界面（顶栏、构图参考线、意图面板、快门）完整显示。
注意：模拟器**没有真实摄像头**，顶栏会如实显示「相机不可用」，快门禁用 ——
本 App 的核心链路（取景 → 拍照）依然必须真机。

### 启动时 SIGABRT：Info.plist 缺 `CADisableMinimumFrameDurationOnPhone`

第一次在模拟器跑起来时，App 启动约 1.5 秒后闪退，控制台原文：

```
Uncaught Kotlin exception: kotlin.IllegalStateException:
  `Info.plist` doesn't have a valid `CADisableMinimumFrameDurationOnPhone` entry...
com.photoguide.iosApp: ... (signal SIGABRT)
```

这是 Compose Multiplatform 1.8.x 引入的**启动期强校验**（`PlistSanityCheck`），
要求 App 显式声明不禁用高刷新率；缺这项直接抛异常，不是相机/代码逻辑问题。
修法：在 `iosApp/Info.plist` 加

```xml
<key>CADisableMinimumFrameDurationOnPhone</key>
<true/>
```

> 排查方法：`xcrun simctl launch --console booted com.photoguide.iosApp` 会把
> Kotlin/Native 的 `Uncaught Kotlin exception` 直接打到终端 —— 比翻系统日志快得多。
> 配置改完重新 `xcodebuild -sdk iphonesimulator ... build` + `simctl install` 即可，无需重编 framework。

---

## iOS 侧代码状态

| 文件 | 状态 |
|---|---|
| `composeApp/src/iosMain/.../MainViewController.kt` | ✅ 已编译通过 |
| `composeApp/src/iosMain/.../platform/SettingsStore.ios.kt` | ✅ 已编译通过（NSUserDefaults + `platformLog`） |
| `composeApp/src/iosMain/.../llm/HttpClientFactory.ios.kt` | ✅ 已编译通过（Darwin / NSURLSession） |
| `composeApp/src/iosMain/.../capture/CaptureController.ios.kt` | ✅ 已编译通过（AVCaptureSession + AVCapturePhotoOutput） |
| `iosApp/iosApp/*.swift` / `Info.plist` | ✅ Swift 编译通过（`import ComposeApp` 打通） |
| `iosApp/iosApp.xcodeproj` | ✅ 已生成，`xcodebuild -list` / `-showBuildSettings` 均正常 |
| `ComposeApp.framework`（iosArm64） | ✅ 编译 + 链接通过（211 MB，debug 静态库，CMP 1.8.2） |
| **App 链接（Swift + framework → .app）** | ✅ 链接通过（iOS 16.2 SDK + `-Wl,-U` 兼容层，见上） |

### 首次编译时修掉的两处 cinterop 问题

都不是"写错了"，而是 Kotlin/Native 的 ObjC 映射与直觉写法不一致：

1. **`AVCaptureVideoPreviewLayer` 是 `CALayer` 的子类，不是 `UIView`。**
   所以给容器加预览层必须 `container.layer.addSublayer(...)`，
   用 `addSubview(...)` 会因参数类型不匹配编不过。

2. **`fileDataRepresentation` 在 ObjC 里声明在 category 上，不是主 `@interface`。**
   于是 Kotlin/Native 把它生成为**包级扩展函数**
   （`fun AVCapturePhoto.fileDataRepresentation(): NSData?`）而不是类成员。
   后果：必须 `import platform.AVFoundation.fileDataRepresentation`，
   否则报 `Unresolved reference`——**可它明明就在 klib metadata 里**，
   只看报错很容易往"方法名写错"的方向查。

排查这两处用的方法（下次遇到疑似 cinterop 签名问题可以直接套）：

```sh
cd ~/.konan/kotlin-native-prebuilt-macos-x86_64-<版本>
./bin/klib dump-metadata klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.AVFoundation > /tmp/av.txt
grep -n "fileDataRepresentation" /tmp/av.txt
```

判据是**有没有接收者前缀**：`public open external fun capturePhotoWithSettings(...)`
是类成员；`public final external fun platform/AVFoundation/AVCapturePhoto.xxx()`
是包级扩展函数，必须 import。
