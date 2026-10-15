package com.xjtu.toolbox.inbox

/** 消息收纳的取数端口：把「学校那几路」拉回来写进 [InboxStore]。 */
interface InboxSource {
    /** 拉一轮并写进 [InboxStore]；各接口互不影响（失败的保留上次结果）。 */
    suspend fun refresh(account: String?)

    /** 距上次成功拉取是否已过 TTL。 */
    fun isDue(account: String?, now: Long): Boolean

    /** 刷新之后本端专属的补拉（默认什么都不做）。 */
    suspend fun afterRefresh(data: InboxData) {}
}
