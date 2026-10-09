package com.xjtu.toolbox.inbox

/**
 * 消息收纳的取数端口：**把「学校那几路」拉回来写进 [InboxStore]**。
 *
 * 为什么是「写进 store」而不是「返回数据」：收纳是**跨页面的全局状态**
 * （首页红点、屁岱冒泡、桌面小组件都在读 [InboxStore]），学校那几路只是它的一个写入方。
 * 屏上的下拉刷新、首页的定时刷新走的是同一条路。
 *
 * - `:app` = 原来的 `SchoolInbox`（一网通办 `x-id-token` 打 4 个域：消息 / 事务中心 / 预约 / 校车）
 *   + 图书馆座位的现查；
 * - `:web` = campus-api 的 `/api/inbox`（同样四路，但它把预约与校车**只归成计数**）。
 *
 * 三个方法都是 suspend；实现方自己负责 IO 调度器。
 */
interface InboxSource {
    /** 拉一轮并写进 [InboxStore]；各接口互不影响（失败的保留上次结果）。 */
    suspend fun refresh(account: String?)

    /** 距上次成功拉取是否已过 TTL。 */
    fun isDue(account: String?, now: Long): Boolean

    /**
     * 刷新**之后**本端专属的补拉（默认什么都不做）。
     *
     * `:app` 用它现查一次图书馆座位（有座位待办时「签过到的马上消失」）；
     * Web 端没有那一路 ⇒ 保持默认实现。
     */
    suspend fun afterRefresh(data: InboxData) {}
}
