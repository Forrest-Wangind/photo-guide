package com.photoguide.decision

/**
 * L0 用户意图：预设场景。
 * 对应需求里的"三档意图"中的第一档——零输入成本，点一下就开拍。
 *
 * [subjectIsPerson] 用来在提示词里直接告知模型"本场景画面里有没有人"，
 * 避免它对着一盘菜、一间空屋子输出"对她说"的台词。
 */
enum class ScenePreset(
    val displayName: String,
    val guidance: String,
    val subjectIsPerson: Boolean = true,
) {
    PORTRAIT(
        "人像半身",
        "主体是人物的上半身。重点是脸部清晰、构图有呼吸感、背景不抢戏。",
    ),
    FULL_BODY(
        "全身",
        "主体是人物全身。重点是把人拍高：机位略低、留出头顶空间、脚不要贴边被裁。",
    ),
    COUPLE(
        "双人合影",
        "画面里有两个人，通常是情侣。重点是两人的相对位置、眼神方向和身体朝向是否协调。",
    ),
    GROUP(
        "多人合影",
        "画面里三个人以上。重点是人脸不要互相遮挡、所有人都在画面内、队伍有层次。",
    ),
    PET(
        "宠物",
        "主体是宠物，画面里**没有人物**。重点是机位降到它的高度、抓住直视镜头的瞬间、避免杂物抢镜。",
        subjectIsPerson = false,
    ),
    FOOD(
        "美食",
        "主体是食物，画面里**没有人物**。重点是俯拍或 45 度、避开杂乱背景、利用侧光。",
        subjectIsPerson = false,
    ),
    INTERIOR(
        "静物/空间",
        "主体是室内一角、空间或静物，画面里**没有人物**（例：\"室内温馨一角\"）。" +
            "重点是光线的方向与色温、前中后景层次、留白与杂物规避。",
        subjectIsPerson = false,
    ),
    SCENERY_WITH_SUBJECT(
        "风景人像",
        "人物相对较小，风景是主体。重点是人物在画面中的位置和与背景的比例关系。",
    ),
}

/**
 * 用户意图：L0 预设 + L1 一句话文字。
 * L2 参考图留给 Phase 2 之后。
 *
 * preset = null 表示"不限定场景，完全由 freeText 决定"。
 * 预设是**省输入成本的快捷方式，不是必须项**：用户想拍的东西预设里没有时
 * 必须能取消掉，否则预设会与文字描述互相打架
 * （典型的坏例：选了"人像半身" + 描述"傍晚拍逆光剪影"，模型收到两条冲突指令）。
 */
data class ShootingIntent(
    val preset: ScenePreset? = ScenePreset.PORTRAIT,
    val freeText: String = "",
) {
    /** 完全没有意图输入：既没选场景也没写字 —— 提示词里要显式告知模型并降级为通用指引。 */
    val isEmpty: Boolean get() = freeText.isBlank() && preset == null
}
