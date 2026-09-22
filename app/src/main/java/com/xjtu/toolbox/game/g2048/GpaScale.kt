package com.xjtu.toolbox.game.g2048

/**
 * 绩点等级表：11 级，和标准 2048 从 2 合到 2048 的 11 次合并一一对应，
 * 难度不因为换了皮而变化。
 *
 * 按西交大 4.3 制：F(0) → D(1.3) → C- … → A+(4.3)，正好 11 档。
 * 开局满盘是 F，两个挂科合成一个 D——越合越好，也算一种励志。
 */
object GpaScale {

    /** 从低到高 11 级，下标即「等级序号」（0-based），合并、计分都按这个序号算。 */
    val LEVELS: List<GpaLevel> = listOf(
        GpaLevel(0.0, "F"),
        GpaLevel(1.3, "D"),
        GpaLevel(1.7, "C-"),
        GpaLevel(2.0, "C"),
        GpaLevel(2.3, "C+"),
        GpaLevel(2.7, "B-"),
        GpaLevel(3.0, "B"),
        GpaLevel(3.3, "B+"),
        GpaLevel(3.7, "A-"),
        GpaLevel(4.0, "A"),
        GpaLevel(4.3, "A+"),
    )

    /** 胜利条件：合成出满级（A+ / 4.3）。 */
    val WIN_INDEX: Int = LEVELS.lastIndex

    /** 新格只会是最低两级之一：90% 一级（F），10% 二级（D）。 */
    val SPAWN_INDEX_0 = 0
    val SPAWN_INDEX_1 = 1
    const val SPAWN_INDEX_1_PROBABILITY = 0.1
}

/** 单个等级：绩点数值 + 展示用字母。 */
data class GpaLevel(val gpa: Double, val letter: String)
