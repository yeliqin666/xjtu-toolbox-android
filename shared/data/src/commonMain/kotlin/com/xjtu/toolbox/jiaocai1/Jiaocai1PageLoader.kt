package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Jiaocai1Page"

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
                ?: gate.withPermit { download(handle, page) }
        }

    override suspend fun coverBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit { fetchBytes(url, "${Jiaocai1Api.BASE}/front/") }
        }
    }

    override fun prefetchWindow(handle: Jiaocai1BookHandle, center: Int) {
        val pages = handle.pages
        val keep = (center - 2..center + 2)
            .mapNotNull { pages.getOrNull(it)?.fileName }
            .toSet()
        prefetchJobs.entries.removeAll { (name, job) ->
            if (name in keep) false else {
                job.cancel()
                true
            }
        }
        for (i in center - 2..center + 2) {
            val page = pages.getOrNull(i) ?: continue
            if (prefetchJobs[page.fileName]?.isActive == true) continue
            prefetchJobs[page.fileName] = prefetchScope.launch {
                if (diskFile(handle.ssno, page.fileName).exists()) return@launch
                gate.withPermit { download(handle, page) }
            }
        }
    }

    override suspend fun evictBook(ssno: String) {
        withContext(Dispatchers.IO) {
            runCatching { File(diskRoot, ssno).deleteRecursively() }
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
        val request = Request.Builder().url(url).get()
            .header("Accept", "image/avif,image/webp,image/png,image/*;q=0.8,*/*;q=0.5")
            .header("Referer", referer)
            .build()
        site.executeWithReAuth(request).use { resp ->
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

    private fun diskFile(ssno: String, fileName: String): File =
        File(File(diskRoot, ssno).apply { mkdirs() }, fileName.sanitize() + ".img")

    private fun readDisk(ssno: String, fileName: String): ByteArray? = try {
        diskFile(ssno, fileName).takeIf { it.isFile && it.length() > 0 }?.readBytes()
    } catch (_: Exception) {
        null
    }

    private fun writeDisk(ssno: String, fileName: String, bytes: ByteArray) {
        try {
            val target = diskFile(ssno, fileName)
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
