package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.bulletin.Bulletin

/**
 * 反馈页「投票」区的数据端口。投票就是带选项的公告（见 [Bulletin.isPoll]），票写进反馈后端，
 * 结果每 4 天由 Action 写回公告。宿主不提供（桌面 / Web）时页面不显示投票区。
 */
interface FeedbackPolls {
    /** 缓存里该给这台设备看的投票：进行中的，加往期的（截止看 [Bulletin.isClosedAt]）。 */
    fun cached(): List<Bulletin>

    /** 重新拉一次公告，返回同 [cached] 口径的列表；失败返回 null。 */
    suspend fun refresh(): List<Bulletin>?

    /** 这台设备投过的选项；没投过返回 null。 */
    fun myVote(id: String): List<String>?

    /** 投票；可以重投，以最后一次为准。 */
    suspend fun vote(poll: Bulletin, selected: List<String>)
}
