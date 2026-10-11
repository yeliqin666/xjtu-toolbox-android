package com.xjtu.toolbox.jiaocai

/**
 * 教材中心的**取数端口** —— `:core` 的屏/ViewModel 只认它，不认具体实现
 * （现在是 `:data` 的 `AppJiaocaiSource`，包住搬进 `:data` 的 `JiaocaiApi`）。
 *
 * ## 为什么它必须是一条缝
 *
 * 搬迁前这条链长在 `:app`：`JiaocaiApi` 收发 okhttp（会话来自 `SiteSession`），
 * ViewModel 自己 `withContext(Dispatchers.IO)` 包一层。屏要能在桌面端复用 ⇒ 取数让出去，
 * **IO 调度器由实现方包**（`:core` 编不到 wasm，没有 `Dispatchers.IO` 这一档；
 * 与 [com.xjtu.toolbox.jiaocai1.Jiaocai1Source] 同一条规矩）。
 *
 * ## 会话形态
 *
 * 站点是**教材中心**（`jiaocai.lib.xjtu.edu.cn`，`:data` 的 `JiaocaiSession` +
 * `JiaocaiLogin`，站点 key `jiaocai`）：`/entry/login` → CAS → 超星 SSO。两个接口都受
 * 「JS 跳转登录链」保护，靠 `PortalRedirect` 自己跟完那几跳（那是取数那一半的事，
 * 端口这边看不见）。
 */
interface JiaocaiSource {

    /**
     * 按关键词检索教材（全校范围）。分页固定在接口默认的 20 条一页、只取第一页 ——
     * 搬迁前就是这样（`JiaocaiApi.search(keyword)`），这一屏没有翻页。
     *
     * 失败（网络/解析）返回空表：搬运前 `JiaocaiApi.search` 自己就把异常吞成空结果了，
     * 屏据此显示「未找到相关教材」。
     */
    suspend fun search(keyword: String): List<JiaocaiBook>

    /**
     * 取一本教材的全文库书目号（进详情页时补一次；`search-list` 的 `content` 里没有链接）。
     * 取不到返回 null（没有开放全文 / 令牌过期）。
     */
    suspend fun fetchSsno(book: JiaocaiBook): String?
}

/**
 * 「本学期我的教材」那一段的数据端口（它在**教务**的缓存里，不在教材中心）。
 *
 * `:app` 的实现包住 `DataCache` + `ScheduleCache.readTextbooks`（同一个键、同一个 TTL，
 * 且滤掉「无教材」那种空信息）；桌面端没有课表缓存 ⇒ 回空表，那一段整段不显示
 * （屏上本来就写着「读完了但没有」与「还没读」是两码事，这里就是前者）—— 如实降级。
 */
interface JiaocaiTermTextbooks {

    /** 本学期课程选用的教材；读不到（没有缓存 / 没有学期）返回空表。 */
    suspend fun currentTerm(): List<JiaocaiTermTextbook>
}
