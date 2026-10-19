package com.photoguide.platform

import androidx.compose.ui.Modifier

/**
 * 把一小块 UI 申请为"系统手势豁免区"。
 *
 * 为什么需要：折叠后的意图面板把手贴着屏幕左边缘 —— 那里是 Android 10+
 * 侧滑返回的手势区，从把手起手的横拖会被系统整个截走（表现为"一拖就退出 App"）。
 * 实测踩过（2026-10-19）。
 *
 * Android：映射到 foundation 的 systemGestureExclusion()，系统会尽量不在这个矩形里
 * 拦截手势（系统对每侧的豁免高度有总量限制，~200dp，92dp 的把手在限额内）。
 */
expect fun Modifier.excludeFromSystemGestures(): Modifier
