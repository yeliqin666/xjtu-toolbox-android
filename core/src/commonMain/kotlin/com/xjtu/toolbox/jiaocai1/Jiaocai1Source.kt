package com.xjtu.toolbox.jiaocai1

/**
 * 教材全文库的**取数与页加载端口** —— `:core` 的屏/ViewModel 只认它，不认具体实现
 * （现在是 `:data` 的 `AppJiaocai1Source`，包住搬进 `:data` 的 `Jiaocai1Api` 与
 * `Jiaocai1PageLoader`）。
 *
 * ## 为什么它必须是一条缝
 *
 * 搬迁前这条链长在 `:app`：`Jiaocai1Api` 收发 okhttp（会话来自 `SiteSession`）、
 * `Jiaocai1PageLoader` 收一个 `android.content.Context`（只为 `cacheDir`）并用
 * `BitmapFactory` / `android.util.LruCache` 解页。而屏要能在桌面端复用 ⇒ 把两半都让出去：
 *  - **取数**（分类树 / 检索 / 打开一本书）由实现方包 IO 调度器；
 *  - **页字节**由实现方包磁盘缓存与预取窗口；**解码成可画的位图是 `:core` 的事**
 *    （走 `:core` 的图片缝，见 [Jiaocai1PageImages]）—— 这也是 `:data` 里不能出现
 *    `Bitmap` 的原因（它只有 jvm + android 两个目标，编不出公共的位图类型）。
 *
 * ## 会话形态（两端同一件事实）
 *
 * 站点是 `jiaocai1.lib.xjtu.edu.cn`，会话由 `/front/user/portalLogin` 建立、从 `jiaocai.lib`
 * 也就是说它是**要登录**的站点，桌面端那条路由套 `DesktopSiteGate(JIAOCAI1_SITE_KEY)`。
 * 与其余站点不同的是：它**没有自己的 `*Login`** —— `:app` 的 `AppRoute.Jiaocai1` 的
 * `loginType` 是 `LoginType.JIAOCAI`，走的是**教材中心那个站点**（`jiaocai.lib` 的 `JiaocaiSession`
 * + `JiaocaiLogin`：入口 `/entry/login` → CAS → 超星 SSO → 回写教材平台的 cookie）。
 * 本轮把这两个类也搬进了 `:data`（同一个包、同一个类名），桌面端那条路由才能真登上去。
 */
interface Jiaocai1Source : Jiaocai1PageBytes {
    /**
     * 四级中图法分类树，返回顶层节点。取不到（网络/解析失败）返回空表 ——
     * 搬迁前就是吞掉异常给空表，屏据此显示「分类目录暂时取不到」。
     */
    suspend fun classifyTree(): List<Jiaocai1Category>

    /**
     * 检索。[keyword] 留空 + [cls] 非空即为按分类浏览；[page] 从 1 起。
     * 失败返回空结果（搬迁前同样是吞异常）。
     */
    suspend fun search(
        keyword: String,
        field: Jiaocai1SearchField,
        cls: String,
        page: Int,
    ): Jiaocai1SearchResult

    /**
     * 用 [ssno] 换阅读句柄；打不开（没有开放全文 / 令牌过期）返回 null。
     * 同一本书并发 open 由实现方保证单飞（搬迁前是 `openMutex` + `CompletableDeferred`）。
     */
    suspend fun openBook(ssno: String): Jiaocai1BookHandle?

}

/**
 * 页字节那一半（取图 + 磁盘缓存 + 预取窗口）—— 单独一个接口只是为了让实现方能分文件装：
 * `:data` 的 `Jiaocai1PageLoader` 只实现它，`AppJiaocai1Source` 再把取数那一半接上。
 */
interface Jiaocai1PageBytes {

    /**
     * 取一页的**原始字节**（实现方包住磁盘缓存与并发闸门）。
     *
     * 上游令牌失效或资源不存在时服务端不给 4xx，而是 200 + 空体 ⇒ 靠图片魔数判成败，
     * 不是图片一律返回 null（搬迁前 `looksLikeImage` 那段判据）。
     */
    suspend fun pageBytes(handle: Jiaocai1BookHandle, page: Jiaocai1Page): ByteArray?

    /** 取封面字节（书架与检索结果的小图）；[url] 为空或取不到返回 null。 */
    suspend fun coverBytes(url: String): ByteArray?

    /**
     * 设置预取窗口：以 [center] 为中心 ±2 页，窗口外的预取任务取消（搬迁前 `setPrefetchWindow`）。
     * 非挂起：它只是起/停后台任务，等它没有意义。
     */
    fun prefetchWindow(handle: Jiaocai1BookHandle, center: Int)

    /**
     * 把一本书的本地页缓存整本丢掉（书架里「移出」时调，搬迁前是 `Jiaocai1PageLoader.evictBook`）。
     */
    suspend fun evictBook(ssno: String)

    /** 离开阅读器时收尾（取消预取作用域）。搬动前是 `Jiaocai1PageLoader.close()`。 */
    fun closePages()
}
