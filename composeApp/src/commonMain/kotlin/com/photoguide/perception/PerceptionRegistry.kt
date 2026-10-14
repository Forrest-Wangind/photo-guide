package com.photoguide.perception

/**
 * 感知源注册表。
 *
 * Phase 1 只有 NoPerception。Phase 2 在这里追加实现即可，
 * UI 的设置页会自动多出一个选项，不需要改 UI 代码。
 */
object PerceptionRegistry {
    private val factories: LinkedHashMap<String, () -> PerceptionSource> = linkedMapOf(
        NoPerception.ID to { NoPerception() },
        // ---- Phase 2 预留（实现落地后取消注释即可）----
        // "yolo26n"     to { Yolo26nPerception() },     // 检测 + 归一化框
        // "movenet"     to { MoveNetPerception() },     // 单人姿态（最快）
        // "yolo26n-pose" to { Yolo26nPosePerception() },// 多人姿态
    )

    fun default(): PerceptionSource = NoPerception()

    fun byId(id: String): PerceptionSource =
        (factories[id] ?: factories.getValue(NoPerception.ID)).invoke()

    fun all(): List<PerceptionSource> = factories.values.map { it() }
}
