@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import com.xjtu.toolbox.jiaocai1.Jiaocai1BookHandle
import com.xjtu.toolbox.jiaocai1.Jiaocai1Category
import com.xjtu.toolbox.jiaocai1.Jiaocai1Page
import com.xjtu.toolbox.jiaocai1.Jiaocai1SearchField
import com.xjtu.toolbox.jiaocai1.Jiaocai1SearchResult
import com.xjtu.toolbox.jiaocai1.Jiaocai1Source
import com.xjtu.toolbox.jiaocai1.ServeJiaocai1Api
import com.xjtu.toolbox.platform.base64UrlDecode
import kotlinx.coroutines.CompletableDeferred

/**
 * 教材全文库（[Jiaocai1Source]）在**浏览器这一端**的宿主（契约 §5.14）。
 *
 * 屏是 `:feature:jiaocai` 那两份（`Jiaocai1LibraryScreen` / `Jiaocai1ReaderScreen`，与 Android /
 * 桌面同一份），宿主走 serve 的 `/api/jiaocai1/{classify,search,book,page,cover,shelf}`：
 * **检索、页表、页图、书架都真的发生在运行
 * serve 的那台机器上**，浏览器只是界面（会议 #9 = 丙「做法①」）。
 *
 * 两处如实差异：
 *
 * 1. **页图 / 封面是一发一发同源 `fetch`**（见 [Jiaocai1Bytes]）—— 不是「本机磁盘缓存 + 预取窗口」
 *    那一套：预取是「一台设备上本地磁盘 + 内存 LRU」的优化，契约 §5.14 也明说了不为它多开端点
 *    ⇒ [prefetchWindow] 与 [evictBook] 在这一端是**空操作**（页缓存与句柄都在 serve 那侧，
 *    移出书架时是服务端自己丢的）。
 * 2. **会话过期由 `WebAuthEvents` 接住**（那几个 JSON 端点会回 401，见 `Data.kt` 的钩子），
 *    屏上那条 `authExpired` 通路是给 `:data` 的 `AuthExpiredException` 用的 —— 浏览器这一端
 *    不产生那个异常（与其余 serve 屏同一条）。
 */
internal class WebJiaocai1Source(
    private val api: ServeJiaocai1Api,
    private val baseUrl: String = API_BASE,
) : Jiaocai1Source {

    override suspend fun classifyTree(): List<Jiaocai1Category> = api.classify()

    override suspend fun search(
        keyword: String,
        field: Jiaocai1SearchField,
        cls: String,
        page: Int,
    ): Jiaocai1SearchResult = api.search(keyword = keyword, field = field, cls = cls, page = page)

    override suspend fun openBook(ssno: String): Jiaocai1BookHandle? = api.book(ssno)

    override suspend fun pageBytes(handle: Jiaocai1BookHandle, page: Jiaocai1Page): ByteArray? =
        Jiaocai1Bytes.fetch("$baseUrl${api.pagePath(handle.ssno, page.index)}")

    override suspend fun coverBytes(url: String): ByteArray? =
        if (url.isBlank()) null else Jiaocai1Bytes.fetch("$baseUrl${api.coverPath(url)}")

    /** 见类 KDoc 第 1 条：浏览器这一端一页一发请求，不做预取窗口。 */
    override fun prefetchWindow(handle: Jiaocai1BookHandle, center: Int) = Unit

    /** 本端没有页缓存可丢（缓存与句柄都在 serve 那侧）⇒ 空操作。 */
    override suspend fun evictBook(ssno: String) = Unit

    override fun closePages() = Unit
}

/**
 * 取一组字节（页图 / 封面）—— 契约 §5.14 的 `page` / `cover` 两条。
 *
 * 为什么走 JS `fetch` 而不是 Ktor：这两条**成功时回 `image/jpeg` 字节**（§4 第四条例外），
 * 失败时才回信封 —— 在浏览器里最直接的做法就是原样取那份响应体。顺带两件事：
 *
 * - `credentials: 'same-origin'`：`serve_token` 是 `Path=/` 的 cookie，同源请求自动带上，
 *   不必自己塞头；
 * - 失败（非 2xx / 网络断 / 不是图）一律回**空串** ⇒ Kotlin 这一侧给 `null`，屏上按「这一张取不到」
 *   画占位（页图那张还能点重试）。**不把失败伪装成字节**：`Image.makeFromEncoded` 拿到信封 JSON
 *   也是解不出来的。
 *
 * 字节过边界走 URL-safe Base64（无填充）—— 与分享码那一族同一套编解码（`:shared:model` 的
 * [base64UrlDecode]），不自己写第二份。
 */
internal object Jiaocai1Bytes {

    /** 取一份字节；取不到返回 null。 */
    suspend fun fetch(url: String): ByteArray? {
        val waiter = CompletableDeferred<String>()
        fetchBase64Url(url) { text -> if (!waiter.isCompleted) waiter.complete(text) }
        val text = waiter.await()
        return if (text.isEmpty()) null else base64UrlDecode(text)
    }
}

/** `fetch` → `ArrayBuffer` → URL-safe Base64（失败回空串），见 [Jiaocai1Bytes] 的 KDoc。 */
@JsFun(
    """
(url, onResult) => {
  fetch(url, { credentials: 'same-origin', cache: 'no-store' })
    .then(async (resp) => {
      if (!resp.ok) { onResult(''); return; }
      const bytes = new Uint8Array(await resp.arrayBuffer());
      let binary = '';
      const chunk = 0x8000;
      for (let i = 0; i < bytes.length; i += chunk) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
      }
      onResult(btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''));
    })
    .catch(() => onResult(''));
}
""",
)
external fun fetchBase64Url(url: String, onResult: (String) -> Unit)
