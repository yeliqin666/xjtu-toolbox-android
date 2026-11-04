package com.xjtu.toolbox.home

import kotlinx.serialization.Serializable

/** 一条首页状态：[value] 是大字主数据，[detail] 是补充说明，[lines] 是大尺寸小组件里列出的明细（最近几笔、今天的课……）。 */
@Serializable
data class HomeStat(val value: String = "", val detail: String? = null, val lines: List<HomeLine> = emptyList())

/** 小组件里的一条明细；[link] 非空时点这一行直接打开它（如某条通知的原文）。 */
@Serializable
data class HomeLine(val text: String, val link: String? = null, val detail: String? = null)

/** 最近一场考试（名称 + 倒计时）在 `HomeStats.collect` 结果里的键。 */
const val EXAM_KEY = "exam_next"
