package com.photoguide.platform

import androidx.compose.ui.Modifier

/**
 * iOS 侧暂无等效的系统手势豁免 API（UIKit 的 interactivePopGestureRecognizer 是
 * UINavigationController 的行为，Compose iOS 的普通视图不触发）。先做成 no-op。
 *
 * ⚠️ 本项目无 macOS/Xcode，iOS 侧从未编译运行过 —— 将来若真机发现从左缘拖把手
 * 会触发什么系统行为，再回来补 actual，不要默认它已经"验证过"。
 */
actual fun Modifier.excludeFromSystemGestures(): Modifier = this
