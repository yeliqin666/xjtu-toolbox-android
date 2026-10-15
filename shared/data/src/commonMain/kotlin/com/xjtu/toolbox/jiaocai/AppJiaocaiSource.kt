package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `:core` 的 [JiaocaiSource] 在 `:data` 里的实现（Android 与桌面共用） */
class AppJiaocaiSource(site: SiteSession) : JiaocaiSource {

    private val api = JiaocaiApi(site)

    override suspend fun search(keyword: String): List<JiaocaiBook> =
        withContext(Dispatchers.IO) { api.search(keyword) }

    override suspend fun fetchSsno(book: JiaocaiBook): String? =
        withContext(Dispatchers.IO) { api.fetchSsno(book) }
}
