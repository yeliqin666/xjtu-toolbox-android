package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `:core` 的 [Jiaocai1Source] 在 `:data` 里的实现（Android 与桌面共用）—— 包住原来的
 * [Jiaocai1Api]（分类树 / 检索 / 打开一本书）与 [Jiaocai1PageLoader]（页字节 + 磁盘缓存 +
 * 预取窗口）。
 *
 * 两种实现的形状：
 *  - **取数那三发**（`classifyTree` / `search` / `openBook`）：搬迁前是 ViewModel 自己
 *    `withContext(Dispatchers.IO)`；现在 VM 不再替实现挑调度器（见 [Jiaocai1Source] 的 KDoc），
 *    所以由本类包 —— 同一层、同一个调度器，行为不变；
 *  - **页字节那一半**：直接委托给 [Jiaocai1PageLoader]（它自己已经包了 IO 调度器与并发闸门，
 *    搬迁前就是这样）。
 *
 * 会话是 `:data` 的 [com.xjtu.toolbox.auth.JiaocaiSession]（站点 key `jiaocai`，与 `:app`
 * 同一个类、同包同类型名）：`jiaocai1.lib` 借教材中心的会话（见 [Jiaocai1Source] 的 KDoc）。
 *
 * [cacheRoot] 是页图缓存根目录（Android = `context.cacheDir`，桌面 = 用户缓存目录）——
 * 类里固定再建 `jiaocai1/` 子目录，与搬迁前同一个路径。
 */
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

    override fun prefetchWindow(handle: Jiaocai1BookHandle, center: Int) =
        pages.prefetchWindow(handle, center)

    override suspend fun evictBook(ssno: String) = pages.evictBook(ssno)

    override fun closePages() = pages.closePages()
}
