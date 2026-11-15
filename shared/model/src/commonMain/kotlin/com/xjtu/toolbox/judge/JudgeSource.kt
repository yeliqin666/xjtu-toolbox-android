package com.xjtu.toolbox.judge

/** 列表里一门课的展示内容。 */
data class JudgeCard(val key: String, val course: String, val teacher: String, val tag: String)

/** 评教的数据来源：QMS（本科研究生共用），旧的教务 / gste + gmis 源仅桌面端保留。 */
interface JudgeSource<Q> {
    /** 这一端能不能提交评教。 */
    val canSubmit: Boolean get() = true

    /** 确认框里「将为 N 门课程全部提交好评」之后的话。 */
    val confirmText: String
    /** (未评, 已评)。 */
    suspend fun load(): Pair<List<Q>, List<Q>>
    fun card(q: Q): JudgeCard
    /** 一键评教前的准备（研究生要先拿一次学位课清单）。 */
    suspend fun prepare() {}
    suspend fun judge(q: Q)
    /** 能撤回时返回撤回函数，失败返回原因。 */
    val undo: (suspend (Q) -> String?)? get() = null
}
