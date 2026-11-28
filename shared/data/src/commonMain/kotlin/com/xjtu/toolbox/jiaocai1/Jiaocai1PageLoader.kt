package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Jiaocai1Page"

/** 封面重定向最多跟几跳（真站点上封面一般不跳，留两跳给 WebVPN 网关与静态资源兜底）。 */
private const val MAX_COVER_HOPS = 2

/** 要自己处理的「重定向」码：301 / 302 / 303 / 307 / 308（304 不在内，它没有 Location）。 */
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/** 全文库的域名（与 `Jiaocai1Api.BASE` 同一个 host，从那一处取，避免两处各写一份）。 */
private val JIAOCAI1_UPSTREAM_HOST: String =
    Jiaocai1Api.BASE.substringAfter("://").substringBefore('/')

/**
 * 教材页面的**字节加载**：取图、磁盘缓存、预取窗口、并发闸门（[Jiaocai1PageBytes] 的实现）。
 *
 * 服务端给的是干净的标准 JPEG：阅读器网页上那层水印是 reader.js 用 canvas 叠的，
 * 不在图像数据里。
 *
 * 缓存在 `cacheRoot/jiaocai1/<ssno>/` 下的 `.img`（Android 上沿用旧页缓存）；这里只到字节，解码在 `:core` 的 [Jiaocai1PageImages]。
 * 令牌失效时上游给 200 + 空体，只能靠图片魔数（`looksLikeImage`）判成败。
 */
class Jiaocai1PageLoader(
    site: SiteSession,
    /** 缓存根目录（Android = `context.cacheDir`）；下面固定再建 `jiaocai1/` 子目录。 */
    private val cacheRoot: File,
) : Jiaocai1PageBytes {

    private val site: SiteSession = site
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchJobs = mutableMapOf<String, Job>()

    private val gate = Semaphore(3)
    private val writesSinceTrim = AtomicInteger(0)
    private val primed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val diskRoot: File
        get() = File(cacheRoot, "jiaocai1").apply { mkdirs() }

    override suspend fun pageBytes(handle: Jiaocai1BookHandle, page: Jiaocai1Page): ByteArray? =
        withContext(Dispatchers.IO) {
            readDisk(handle.ssno, page.fileName)
                ?.also { if (page.index == 0) writeFirstPageName(handle.ssno, page.fileName) }
                ?: gate.withPermit { download(handle, page) }
        }

    override suspend fun coverBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit { fetchCover(url, "${Jiaocai1Api.BASE}/front/") }
        }
    }

    override suspend fun cachedFirstPageBytes(ssno: String): ByteArray? = withContext(Dispatchers.IO) {
        // 优先按下载首页时记下的文件名查，与阅读器页表的真实首页一致
        readFirstPageName(ssno)?.let { name -> readDisk(ssno, name)?.let { return@withContext it } }
        // 兼容没有记录的旧缓存：按页型顺序查各页型第 1 页；封底与封面同为 cov001，跳过
        (0 until Jiaocai1Paging.TYPE_COUNT - 1).firstNotNullOfOrNull { type ->
            readDisk(ssno, Jiaocai1Paging.fileName(1, type))
        }
    }

    override fun prefetchWindow(handle: Jiaocai1BookHandle, center: Int) {
        val pages = handle.pages
        val keep = (center - 2..center + 2)
            .mapNotNull { pages.getOrNull(it)?.fileName }
            .toSet()
        // 首页供书架缩略图（#206），滚出窗口也不取消，让它下完
        val first = pages.firstOrNull()?.fileName
        prefetchJobs.entries.removeAll { (name, job) ->
            if (name in keep || (name == first && job.isActive)) false else {
                job.cancel()
                true
            }
        }
        for (i in center - 2..center + 2) {
            val page = pages.getOrNull(i) ?: continue
            if (prefetchJobs[page.fileName]?.isActive == true) continue
            prefetchJobs[page.fileName] = prefetchScope.launch {
                if (diskFile(handle.ssno, page.fileName).exists()) {
                    if (page.index == 0) writeFirstPageName(handle.ssno, page.fileName)
                    return@launch
                }
                gate.withPermit { download(handle, page) }
            }
        }
    }

    override suspend fun evictBook(ssno: String) {
        withContext(Dispatchers.IO) {
            runCatching { File(diskRoot, ssno.sanitize()).deleteRecursively() }
        }
    }

    override fun closePages() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        prefetchScope.cancel()
    }

    private suspend fun download(handle: Jiaocai1BookHandle, page: Jiaocai1Page): ByteArray? {
        prime(handle)
        val bytes = fetchBytes(
            Jiaocai1Api.pageUrl(handle, page),
            "${Jiaocai1Api.BASE}/jpath/reader/reader.shtml",
        ) ?: return null
        writeDisk(handle.ssno, page.fileName, bytes)
        if (page.index == 0) writeFirstPageName(handle.ssno, page.fileName)
        return bytes
    }

    private suspend fun prime(handle: Jiaocai1BookHandle) {
        val first = handle.pages.firstOrNull() ?: return
        if (!primed.add(handle.jpgPath)) return
        runCatching {
            val url = Jiaocai1Api.pageUrl(handle, first).replace("?zoom=0", "?pi=2&zoom=0")
            val req = Request.Builder().url(url)
                .header("Referer", "${Jiaocai1Api.BASE}/jpath/reader/reader.shtml")
                .get().build()
            site.executeWithReAuth(req).use { it.body?.close() }
        }
    }

    /**
     * 令牌失效或资源不存在时服务端不给 4xx，而是 200 + 空体，只能靠图片魔数判成败。
     *
     * Referer 交给 WebVpnInterceptor 改写成网关形式——它是取图能否成功的关键，
     * 但那是全站共性问题，不该在这里单独兜。
     */
    private suspend fun fetchBytes(url: String, referer: String): ByteArray? = try {
        site.executeWithReAuth(imageRequest(url, referer)).use { resp ->
            val body = resp.body?.bytes()
            if (!resp.isSuccessful || body == null || !body.looksLikeImage()) {
                Log.w(TAG, "非图像响应 code=${resp.code} len=${body?.size} headers=${resp.headers}")
                null
            } else body
        }
    } catch (e: Exception) {
        Log.w(TAG, "fetch failed: ${e.message}")
        null
    }

    /**
     * 封面取字节：**自己跟重定向，只跟同主机的跳**（最多 [MAX_COVER_HOPS] 跳）。
     *
     * 为什么不能直接复用 [fetchBytes]（2026-11-27 #597）：`?url=` 是客户端带回来的，serve 那一层虽然
     * 用 `isJiaocai1Url` 判过「只认全文库」，但那判的是**第一发**的 URL；OkHttp 默认跟随重定向
     * ⇒ 一个合法的全文库地址只要 302 到别处（内网、云元数据），serve 就替调用方去取了。
     *
     * 做法：本发请求关掉自动跟随（`followRedirects = false`），拿 `Location` 自己解析，
     * 目标主机复核通过才跟下一发。WebVPN 形态先解码回原域名再比 —— 网关自己那一跳算同主机，
     * 否则校外（WEBVPN 模式）会把封面全弄坏。
     *
     * 页图那条路（[download]）不套这层：它的 URL 由服务端句柄拼出来，不是调用方能决定的输入。
     */
    private suspend fun fetchCover(url: String, referer: String): ByteArray? {
        var current = url
        var hops = 0
        while (true) {
            val response = try {
                site.executeWithReAuth(imageRequest(current, referer), followRedirects = false)
            } catch (e: Exception) {
                Log.w(TAG, "cover fetch failed: ${e.message}")
                return null
            }
            // 重定向那一档连「基址」一起带出来：要按**发出去那一发**的 URL 解相对 Location，
            // 不能按 `current`（见 [resolveCoverRedirect]）。
            val redirect: Pair<String, String>? = response.use { resp ->
                if (resp.code in REDIRECT_CODES) {
                    resp.header("Location")?.let { resp.request.url.toString() to it }
                } else {
                    // 200 之后正文仍可能读断（连接被掐 / 网关截流）：那是「这一张取不到」，
                    // 不是要把异常抛到调用方（旧 `fetchBytes` 就是这么兜的）。
                    val body = try {
                        resp.body?.bytes()
                    } catch (e: Exception) {
                        Log.w(TAG, "封面正文读取失败: ${e.message}")
                        null
                    }
                    return if (!resp.isSuccessful || body == null || !body.looksLikeImage()) {
                        Log.w(TAG, "封面非图像响应 code=${resp.code} len=${body?.size}")
                        null
                    } else {
                        body
                    }
                }
            }
            if (redirect == null) {
                Log.w(TAG, "封面重定向没有 Location，放弃")
                return null
            }
            if (hops++ >= MAX_COVER_HOPS) {
                Log.w(TAG, "封面重定向超过 $MAX_COVER_HOPS 跳，放弃")
                return null
            }
            val (base, location) = redirect
            current = resolveCoverRedirect(base, location) ?: run {
                // 只记目标主机名（不记整条 Location）：跳转目标里可能有签名的查询串 / 网关密文，
                // 日志不落原文；主机名足够定位「到底被带去了哪」。
                val targetHost = runCatching { base.toHttpUrlOrNull()?.resolve(location)?.host }.getOrNull()
                Log.w(TAG, "封面重定向到别的主机，放弃：host=${targetHost ?: "?"}")
                return null
            }
        }
    }

    private fun imageRequest(url: String, referer: String): Request = Request.Builder().url(url).get()
        .header("Accept", "image/avif,image/webp,image/png,image/*;q=0.8,*/*;q=0.5")
        .header("Referer", referer)
        .build()

    private fun diskFile(ssno: String, fileName: String): File =
        File(File(diskRoot, ssno.sanitize()), fileName.sanitize() + ".img")

    private fun readDisk(ssno: String, fileName: String): ByteArray? = try {
        diskFile(ssno, fileName).takeIf { it.isFile && it.length() > 0 }?.readBytes()
    } catch (_: Exception) {
        null
    }

    private fun writeDisk(ssno: String, fileName: String, bytes: ByteArray) {
        try {
            val target = diskFile(ssno, fileName)
            target.parentFile.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) tmp.delete()
            if (writesSinceTrim.incrementAndGet() >= TRIM_EVERY) {
                writesSinceTrim.set(0)
                trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeDisk failed: ${e.message}")
        }
    }

    /** 首页文件名记在书目录下，书架只凭 ssno 就能找到真实首页。 */
    private fun firstPageMarker(ssno: String): File =
        File(File(diskRoot, ssno.sanitize()), FIRST_PAGE_MARKER)

    private fun readFirstPageName(ssno: String): String? = try {
        firstPageMarker(ssno).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    private fun writeFirstPageName(ssno: String, fileName: String) {
        try {
            val marker = firstPageMarker(ssno)
            if (marker.isFile && marker.readText() == fileName) return
            marker.writeText(fileName)
        } catch (e: Exception) {
            Log.e(TAG, "writeFirstPageName failed: ${e.message}")
        }
    }

    private fun trim() {
        try {
            val books = diskRoot.listFiles()?.filter { it.isDirectory } ?: return
            val sized = books.map { dir ->
                val size = dir.listFiles()?.sumOf { it.length() } ?: 0L
                Triple(dir, size, dir.lastModified())
            }
            var total = sized.sumOf { it.second }
            if (total <= DISK_CAP_BYTES) return
            sized.sortedBy { it.third }.forEach { (dir, size, _) ->
                if (total <= DISK_CAP_BYTES) return
                dir.deleteRecursively()
                total -= size
            }
        } catch (e: Exception) {
            Log.e(TAG, "trim failed: ${e.message}")
        }
    }

    companion object {
        private const val DISK_CAP_BYTES = 160L * 1024 * 1024
        private const val TRIM_EVERY = 40
        private const val FIRST_PAGE_MARKER = "first-page"

        private fun ByteArray.looksLikeImage(): Boolean {
            if (size < 8) return false
            val jpeg = this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()
            val png = this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
                this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
            val gif = this[0] == 0x47.toByte() && this[1] == 0x49.toByte() && this[2] == 0x46.toByte()
            val webp = this[0] == 0x52.toByte() && this[1] == 0x49.toByte() &&
                this[2] == 0x46.toByte() && this[3] == 0x46.toByte()
            val bmp = this[0] == 0x42.toByte() && this[1] == 0x4D.toByte()
            return jpeg || png || gif || webp || bmp
        }

        private fun String.sanitize(): String = replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}

/**
 * 封面重定向的下一跳：目标与 [base] **同主机**才放行，否则 `null`。
 *
 * [base] 必须是**这一发真正发出去的 URL**（`response.request.url`），不是我们自己记的 `current`：
 * WEBVPN 模式下拦截器会把原站地址改写成 `https://webvpn.xjtu.edu.cn/https/<密文>/<路径>`，
 * 网关给的相对 `Location`（`/https/<密文>/cover/b.jpg`）只有相对网关才解得对。
 * 拿原站地址当基址会拼出 `https://jiaocai1.lib.xjtu.edu.cn/https/…`，拦截器再包一层 ⇒ 路径错、取不到图。
 * 直连模式下两者是同一个地址，相对跳转照旧。
 *
 * 判主机时先把跳转目标解码回原域名（网关那一跳算同主机，否则校外（WEBVPN）封面全坏），
 * 再与全文库域名严格相等比 —— 这样 `https://evil.com#@jiaocai1…` 这类构造一律过不去。
 */
internal fun resolveCoverRedirect(base: String, location: String): String? {
    val from = base.toHttpUrlOrNull() ?: return null
    val next = from.resolve(location) ?: return null
    if (next.scheme != "http" && next.scheme != "https") return null
    val original = WebVpnUtil.getOriginalUrl(next.toString())?.toHttpUrlOrNull() ?: next
    return next.toString().takeIf { original.host.equals(JIAOCAI1_UPSTREAM_HOST, ignoreCase = true) }
}
