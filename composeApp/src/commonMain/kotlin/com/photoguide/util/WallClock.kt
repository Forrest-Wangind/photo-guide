package com.photoguide.util

/**
 * 墙钟时间（epoch 毫秒）。
 *
 * 为什么不能复用 [Clock]：那是单调时钟（相对进程启动），算耗时很棒，
 * 但历史记录要回答的是"这张是哪天拍的"，单调时钟给不了日期，
 * 而且进程一重启就归零 —— 存进记录里会变成一堆互相矛盾的"天数"。
 *
 * 反过来也不用它算耗时：改系统时间会让差值为负，所以两套时钟各管一段。
 */
expect fun nowEpochMs(): Long

/**
 * 相对时间标签。**不做绝对日期格式化**是刻意的：
 * 格式化绝对日期要时区（Kotlin 标准库没有，引入 kotlinx-datetime 只为这一行不值），
 * 而历史列表里"3 小时前"本身就比"11-12 16:22"更好读 —— 用户判断"这是不是刚才那张"
 * 靠的正是相对感。
 */
fun relativeTimeLabel(createdAtMs: Long, nowMs: Long = nowEpochMs()): String {
    val diff = (nowMs - createdAtMs).coerceAtLeast(0)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        diff < 30 * day -> "${diff / (7 * day)} 周前"
        else -> "${diff / (30 * day)} 个月前"
    }
}
