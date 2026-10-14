package com.photoguide.util

import kotlin.time.TimeSource

/**
 * 单调时钟（相对进程启动）。
 *
 * 用 kotlin.time.TimeSource.Monotonic 而不是系统墙钟：
 * 用户改系统时间不会让耗时统计出现负值。
 */
object Clock {
    private val origin = TimeSource.Monotonic.markNow()

    fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}
