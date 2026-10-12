package com.xjtu.toolbox.home

import kotlinx.serialization.Serializable

/**
 * 一条首页状态：[value] 是大字主数据，[detail] 是补充说明。
 *
 * 从 `:app` 的 `home/HomeStats.kt` 搬进 `:core`（同包同名，逐字未动）。它是首页的**值对象**：
 * 各功能页在「已经拿到数据」的地方顺手写一条，首页只读出来画 —— 所以它的形状是两端共用的
 * 契约，而不是某一端的实现细节（`:app` 那份落在 `DataCache` 的 `home_stat_*` 键上，桌面那份
 * 由各卡片自己现算，但**值对象与键名只有一个说法**）。
 *
 * `@Serializable` 留着：`:app` 那份老数据就是按这个形状序列化在 `DataCache` 里的，
 * 改形状等于把老用户的摘要读坏。
 */
@Serializable
data class HomeStat(val value: String = "", val detail: String? = null)

/**
 * 最近一场考试（名称 + 倒计时）在 `HomeStats.collect` 结果里的键。
 *
 * 单独一项而不是塞进 `schedule` 那一格：首页 Hero 的考试倒计时读它
 * （见 `:app` 的 `HomeStats`）。键名进 `:core` 是因为**消费方也跨端**（卡片模型读它）。
 */
const val EXAM_KEY = "exam_next"
