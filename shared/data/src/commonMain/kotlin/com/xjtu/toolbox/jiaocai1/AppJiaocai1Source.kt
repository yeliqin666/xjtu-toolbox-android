package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** `:core` 的 [Jiaocai1Source] 在 `:data` 里的实现（Android 与桌面共用） */
class AppJiaocai1Source(
    site: SiteSession,
    cacheRoot: File,
) : Jiaocai1Source {

    private val api = Jiaocai1Api(site)
    private val pages = Jiaocai1PageLoader(site, cacheRoot)

    override suspend fun classifyTree(): List<Jiaocai1Category> =
        withContext(Dispatchers.IO) { api.classifyTree() }

    override suspend fun search(
        keyword: String,
        field: Jiaocai1SearchField,
        cls: String,
        page: Int,
    ): Jiaocai1SearchResult = withContext(Dispatchers.IO) {
        api.search(keyword = keyword, field = field, cls = cls, page = page)
    }

    override suspend fun openBook(ssno: String): Jiaocai1BookHandle? =
        withContext(Dispatchers.IO) { api.openBook(ssno) }

    override suspend fun pageBytes(handle: Jiaocai1BookHandle, page: Jiaocai1Page): ByteArray? =
        pages.pageBytes(handle, page)

    override suspend fun coverBytes(url: String): ByteArray? = pages.coverBytes(url)

    override suspend fun cachedFirstPageBytes(ssno: String): ByteArray? = pages.cachedFirstPageBytes(ssno)

    override fun prefetchWindow(handle: Jiaocai1BookHandle, center: Int) =
        pages.prefetchWindow(handle, center)

    override suspend fun evictBook(ssno: String) = pages.evictBook(ssno)

    override fun closePages() = pages.closePages()
}
