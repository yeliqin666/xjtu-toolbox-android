package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:core` 的 [JiaocaiSource] 在 `:data` 里的实现（Android 与桌面共用）—— 包住原来的
 * [JiaocaiApi]（教材中心检索 + 取 ssno）。
 *
 * 搬迁前那两个 `withContext(Dispatchers.IO)` 在 `:app` 的 `JiaocaiViewModel` 里；现在 VM
 * 不再替实现挑调度器（见 [JiaocaiSource] 的 KDoc），所以由本类包 —— 同一层、同一个调度器，
 * 行为不变。
 *
 * 会话是 `:data` 的 [com.xjtu.toolbox.auth.JiaocaiSession]（站点 key `jiaocai`，与 `:app`
 * 同一个类、同包同类型名）。
 */
class AppJiaocaiSource(site: SiteSession) : JiaocaiSource {

    private val api = JiaocaiApi(site)

    override suspend fun search(keyword: String): List<JiaocaiBook> =
        withContext(Dispatchers.IO) { api.search(keyword) }

    override suspend fun fetchSsno(book: JiaocaiBook): String? =
        withContext(Dispatchers.IO) { api.fetchSsno(book) }
}
