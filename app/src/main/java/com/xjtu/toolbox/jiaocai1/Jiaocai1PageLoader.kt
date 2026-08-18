package com.xjtu.toolbox.jiaocai1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.xjtu.toolbox.auth.SiteSession
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

enum class Jiaocai1DecodeTier { SCREEN, DETAIL }

/**
 * 教材页面图像加载。
 *
 * 服务端给的是干净的标准 JPEG：阅读器网页上那层水印是 reader.js 用 canvas 叠的，
 * 不在图像数据里。两层缓存：解码后的 Bitmap 进内存 LRU，原始字节落磁盘。
 */
class Jiaocai1PageLoader(
    context: Context,
    private val site: SiteSession,
) {
    private val appContext = context.applicationContext
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchJobs = mutableMapOf<String, Job>()

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val gate = Semaphore(3)
    private val writesSinceTrim = AtomicInteger(0)
    private val coverMisses = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val primed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val diskRoot: File
        get() = File(appContext.cacheDir, "jiaocai1").apply { mkdirs() }

    suspend fun load(
        handle: Jiaocai1BookHandle,
        page: Jiaocai1Page,
        targetWidthPx: Int,
        tier: Jiaocai1DecodeTier = Jiaocai1DecodeTier.SCREEN,
    ): Bitmap? {
        val memKey = "${handle.ssno}/${page.fileName}@$targetWidthPx/$tier"
        memory.get(memKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bytes = readDisk(handle.ssno, page.fileName)
                ?: gate.withPermit { download(handle, page) }
                ?: return@withContext null
            val decoded = decode(bytes, targetWidthPx, tier) ?: return@withContext null
            memory.put(memKey, decoded)
            decoded
        }
    }

    suspend fun loadCover(url: String, targetWidthPx: Int): Bitmap? {
        if (url.isBlank()) return null
        val memKey = "cover:$url@$targetWidthPx"
        memory.get(memKey)?.let { return it }
        if (url in coverMisses) return null
        return withContext(Dispatchers.IO) {
            val bytes = gate.withPermit { fetchBytes(url, "${Jiaocai1Api.BASE}/front/") }
            if (bytes == null) {
                coverMisses.add(url)
                return@withContext null
            }
            decode(bytes, targetWidthPx, Jiaocai1DecodeTier.SCREEN)?.also { memory.put(memKey, it) }
        }
    }

    suspend fun ensureOnDisk(handle: Jiaocai1BookHandle, page: Jiaocai1Page): Boolean {
        if (diskFile(handle.ssno, page.fileName).exists()) return true
        return withContext(Dispatchers.IO) {
            gate.withPermit { download(handle, page) } != null
        }
    }

    fun setPrefetchWindow(handle: Jiaocai1BookHandle, center: Int) {
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

    fun close() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        prefetchScope.cancel()
    }

    fun evictBook(ssno: String) {
        evictBookStatic(appContext, ssno)
        memory.evictAll()
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

    private fun decode(bytes: ByteArray, targetWidthPx: Int, tier: Jiaocai1DecodeTier): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val want = when (tier) {
            Jiaocai1DecodeTier.SCREEN -> targetWidthPx
            Jiaocai1DecodeTier.DETAIL -> (targetWidthPx * 2).coerceAtLeast(targetWidthPx)
        }
        var sample = 1
        if (want > 0) {
            while (bounds.outWidth / (sample * 2) >= want) sample *= 2
        }
        if (tier == Jiaocai1DecodeTier.DETAIL) sample = (sample / 2).coerceAtLeast(1)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: Exception) {
        Log.e(TAG, "decode failed: ${e.message}")
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

        fun evictBookStatic(context: Context, ssno: String) {
            try {
                File(File(context.applicationContext.cacheDir, "jiaocai1"), ssno).deleteRecursively()
            } catch (_: Exception) {
            }
        }

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
