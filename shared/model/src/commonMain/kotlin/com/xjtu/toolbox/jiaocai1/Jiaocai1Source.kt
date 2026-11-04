package com.xjtu.toolbox.jiaocai1

/** 教材全文库的取数与页加载端口。 */
interface Jiaocai1Source : Jiaocai1PageBytes {
    /** 四级中图法分类树，返回顶层节点。 */
    suspend fun classifyTree(): List<Jiaocai1Category>

    /** 检索。 */
    suspend fun search(
        keyword: String,
        field: Jiaocai1SearchField,
        cls: String,
        page: Int,
    ): Jiaocai1SearchResult

    /** 用 [ssno] 换阅读句柄；打不开（没有开放全文 / 令牌过期）返回 null。 */
    suspend fun openBook(ssno: String): Jiaocai1BookHandle?

}

/** 页字节那一半（取图 + 磁盘缓存 + 预取窗口） */
interface Jiaocai1PageBytes {

    /** 取一页的原始字节（实现方包住磁盘缓存与并发闸门）。 */
    suspend fun pageBytes(handle: Jiaocai1BookHandle, page: Jiaocai1Page): ByteArray?

    /** 取封面字节（书架与检索结果的小图）；[url] 为空或取不到返回 null。 */
    suspend fun coverBytes(url: String): ByteArray?

    /** 本地缓存的首页字节，无缓存返回 null，不联网。 */
    suspend fun cachedFirstPageBytes(ssno: String): ByteArray? = null

    /** 设置预取窗口：以 [center] 为中心 ±2 页，窗口外的预取任务取消。 */
    fun prefetchWindow(handle: Jiaocai1BookHandle, center: Int)

    /** 把一本书的本地页缓存整本丢掉（书架里「移出」时调）。 */
    suspend fun evictBook(ssno: String)

    /** 离开阅读器时收尾（取消预取作用域）。 */
    fun closePages()
}
