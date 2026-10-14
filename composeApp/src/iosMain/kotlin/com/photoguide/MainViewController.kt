package com.photoguide

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS 入口。Swift 侧通过 `MainViewControllerKt.MainViewController()` 拿到它。
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
