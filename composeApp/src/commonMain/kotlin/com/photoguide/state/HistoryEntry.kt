package com.photoguide.state

import com.photoguide.decision.CompositionHint
import com.photoguide.decision.ShootingIntent
import kotlinx.serialization.Serializable

/**
 * 一条拍摄记录。
 *
 * 需求边界（用户 2026-11-12 拍板）：**不进数据库，最多 20 条**。
 * 所以这里刻意做成"值对象 + 一个 JSON 列表"的形态，整体塞进已有的
 * SettingsStore（Android SharedPreferences / iOS NSUserDefaults），
 * 不引入 Room / SQLDelight / 文件管理器那一整套。
 *
 * 三个设计取舍：
 *
 * ① 完整存下 [hint]（而不是只存标题一句话）：历史的意义是"回看我当时到底该怎么改"，
 *    只留标题等于把最有价值的两栏（机位动作 / 对她说）丢掉。
 *    CompositionHint 本来就是 @Serializable 的，复用同一个 DTO 而不是另造精简版 ——
 *    另造一份就得跟着主结构同步改，迟早漏字段（"同一件事存两份"的老坑）。
 *
 * ② 同时存下**当时的意图快照**（[sceneLabel] / [freeText]）：过几天回看时，
 *    "这条是选了'人像半身'还写了一句'傍晚逆光'才出来的"本身就是有用信息。
 *
 * ③ 缩略图存 base64 字符串而不是字节：键值存储只能放文本。压到长边 240px 之后
 *    单张 8~20KB，20 条约 200~400KB —— 这是"不要数据库"在多大数据量下依然成立的边界。
 *    null 表示生成/解码失败，列表里明确显示"无图"，不假装有。
 */
@Serializable
data class HistoryEntry(
    /** 单调递增的拍摄时间戳（ms），同时用作列表 key 与排序依据。 */
    val id: Long,
    val createdAtMs: Long,

    /** 建议标题（模型给的一句话结论）。 */
    val headline: String = "",

    /** 0~100 构图评分；模型没给就为 null，历史里也不显示。 */
    val score: Int? = null,

    /** 拍摄时的意图档位："人像半身" / "自由描述"。 */
    val sceneLabel: String = "",

    /** 拍摄时输入框里那句话（可为空）。 */
    val freeText: String = "",

    /** 本次建议对应的参考线；空 = 当时没推荐出来。 */
    val guideLabel: String = "",

    /** 出这条建议的模型名。回看时能区分"换了端点之后风格为什么变了"。 */
    val modelLabel: String = "",

    /** 完整建议内容（双栏 / 问题 / 警告都在里面）。 */
    val hint: CompositionHint = CompositionHint(),

    /** 缩略图 JPEG 的 base64；null = 无图（生成失败），列表显示占位。 */
    val thumbBase64: String? = null,
) {
    /** 时长会随"现在"变化，所以不缓存成字段，渲染时按需算。 */
    fun timeLabel(nowMs: Long): String = com.photoguide.util.relativeTimeLabel(createdAtMs, nowMs)
}

/** 历史记录条数上限。到顶后最老的一条被挤掉（FIFO）。 */
const val MAX_HISTORY = 20

/** 意图 → 历史列表里那个标签。与 chip 上看到的一致，避免"记录里叫法不一样"。 */
fun sceneLabelOf(intent: ShootingIntent): String =
    intent.preset?.displayName ?: "自由描述"
