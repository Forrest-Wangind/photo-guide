# iosApp 说明

## 为什么这里没有 .xcodeproj

`.xcodeproj`（project.pbxproj）是 Xcode 自动生成的二进制化配置，手写极易出错且无法在
Windows 上验证。所以这个仓库**只提交 iOS 侧的源码**，工程壳在 Mac 上生成一次即可。

**iOS 构建必须用 macOS + Xcode**，这是苹果生态的硬限制，不是本项目的选择。

## 在 Mac 上补齐工程（约 2 分钟）

1. 用 Android Studio（装 Kotlin Multiplatform 插件）打开仓库根目录，
   或用 IntelliJ IDEA；让它同步一次 Gradle。
2. 打开 `iosApp/` 目录，新建一个 iOS App 工程，Product Name 用 `iosApp`，
   Interface 选 **SwiftUI**，Language 选 **Swift**。
3. 把本目录下已有的三个文件加进工程（覆盖 Xcode 自动生成的同名文件）：
   - `iosApp/iOSApp.swift`
   - `iosApp/ContentView.swift`
   - `iosApp/Info.plist`（在 Target → Info 里确认 `NSCameraUsageDescription` 存在）
4. 在 Target → Build Phases 里加一个 **Run Script**（放在 "Compile Sources" 之前）：

   ```sh
   cd "$SRCROOT/.."
   ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
   ```

5. Target → Build Settings：
   - `FRAMEWORK_SEARCH_PATHS` = `$(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`
   - `OTHER_LDFLAGS` = `$(inherited) -framework ComposeApp`

6. 选一台真机（**必须真机**，模拟器没有摄像头）运行。

## 真机运行前

- Target → Signing & Capabilities 里选上自己的开发者账号（免费账号即可）。
- 首次运行后到「设置 → 隐私与安全性 → 相机」确认权限已授予。

## iOS 侧代码状态

| 文件 | 状态 |
|---|---|
| `composeApp/src/iosMain/.../MainViewController.kt` | 已写 |
| `composeApp/src/iosMain/.../capture/CaptureController.ios.kt` | 已写，**未编译验证**（本机无 Xcode） |
| `composeApp/src/iosMain/.../platform/SettingsStore.ios.kt` | 已写 |
| `composeApp/src/iosMain/.../llm/HttpClientFactory.ios.kt` | 已写 |

第一次在 Mac 上编译时，如果 `CaptureController.ios.kt` 里某个 AVFoundation 的
cinterop 签名对不上（Kotlin/Native 的 ObjC 映射偶尔会与文档写法有出入），
按编译器提示改一下参数名或可空性即可，**逻辑结构不需要动**。
