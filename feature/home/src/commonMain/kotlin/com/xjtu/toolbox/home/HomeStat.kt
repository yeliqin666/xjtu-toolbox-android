package com.xjtu.toolbox.home

import kotlinx.serialization.Serializable

/** 一条首页状态：[value] 是大字主数据，[detail] 是补充说明。 */
@Serializable
data class HomeStat(val value: String = "", val detail: String? = null)

/** 最近一场考试（名称 + 倒计时）在 `HomeStats.collect` 结果里的键。 */
const val EXAM_KEY = "exam_next"
