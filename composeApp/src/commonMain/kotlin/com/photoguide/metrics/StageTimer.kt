package com.photoguide.metrics

import com.photoguide.util.Clock

/** 单个阶段的耗时。Debug 浮层要逐段显示，而不是只给一个总数。 */
data class StageTiming(val stage: String, val ms: Long)

/**
 * 分段计时器。
 *
 * 为什么从 Phase 1 就要有它：主方案 §5.1.1 把"流畅性"定义为**帧时间的确定性**，
 * 而确定性只能靠测量。Phase 1 虽然是单图形态（没有帧率概念），
 * 但"拍照 → 感知 → 上传 → 模型 → 解析"这条链路的每一段耗时都必须现在就开始采集，
 * 否则 Phase 2 谈 P99 的时候没有任何历史基线可比。
 */
class StageTimer {
    private val origin = Clock.nowMs()
    private var last = origin
    private val marks = mutableListOf<StageTiming>()

    fun mark(stage: String) {
        val now = Clock.nowMs()
        marks += StageTiming(stage, now - last)
        last = now
    }

    fun snapshot(): List<StageTiming> = marks.toList()

    fun totalMs(): Long = Clock.nowMs() - origin
}

/**
 * 延迟趋势（最近 N 次）。
 *
 * Phase 2 会升级成逐帧的 P50/P99/最大连掉帧统计（§10.6 流畅性验收基线）；
 * 现在先用最小实现，保证浮层的位置和数据结构不返工。
 */
class LatencyTrend(private val capacity: Int = 30) {
    private val samples = ArrayDeque<Long>()

    fun add(totalMs: Long) {
        if (samples.size >= capacity) samples.removeFirst()
        samples.addLast(totalMs)
    }

    fun samples(): List<Long> = samples.toList()

    fun p50(): Long? = percentile(0.50)
    fun p99(): Long? = percentile(0.99)

    private fun percentile(q: Double): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
