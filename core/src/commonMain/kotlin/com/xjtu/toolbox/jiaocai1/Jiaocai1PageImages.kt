package com.xjtu.toolbox.jiaocai1

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.xjtu.toolbox.platform.decodeImageDownToWidth
import com.xjtu.toolbox.platform.decodeImageSize
import com.xjtu.toolbox.platform.imageMemoryCacheBudgetBytes

/** 解码档位：屏上看的那一档 / 放大之后要更清楚的那一档（搬迁前 `Jiaocai1DecodeTier`）。 */
enum class Jiaocai1DecodeTier { SCREEN, DETAIL }

/**
 * 教材页面的**解码后位图缓存**（`jiaocai1` 域唯一留在 `:core` 的那一半页加载）。
 *
 * ## 它与 [Jiaocai1Source] 怎么分工
 *
 * | | 谁做 | 为什么 |
 * |---|---|---|
 * | 取字节、磁盘缓存、预取、并发闸门 | 实现方（`:data` 的 `Jiaocai1PageLoader`） | 要 okhttp / `java.io.File` / 会话 |
 * | 解码成可画的位图、内存 LRU | **这里** | 解码走 `:core` 的图片缝；`:data` 里连 `Bitmap` 类型都编不出来 |
 *
 * ## 与搬迁前逐字对齐的三件事
 *
 * 1. **缓存键**：`"${ssno}/${fileName}@${targetWidthPx}/$tier"`（搬迁前 `Jiaocai1PageLoader.load`
 *    里那一行）—— 同一本书的同一页、同一目标宽度、同一档位才复用；
 * 2. **降采样口径**：`want = if (DETAIL) targetWidthPx * 2 else targetWidthPx`，
 *    再交给 [decodeImageDownToWidth]（Android 的实现与搬迁前那段 `inSampleSize` 循环逐字同构）；
 * 3. **失败与封面**：页解不出来返回 null（屏画「加载失败」），封面取不到**记住这一次失败**
 *    （搬迁前的 `coverMisses` 集合）—— 免得每次重组都为同一张取不到的封面再打一枪。
 *
 * ## 一处如实的差异（都在这里，不在别处）
 *
 * 搬迁前内存 LRU 用 `android.util.LruCache`，容量按 `Bitmap.byteCount` 算、上限
 * `maxMemory / 8`（不超过 64MB）。`:core` 是公共代码：`LruCache` 与 `Runtime` 都不在，
 * 位图也不带 `byteCount` ⇒ 这里用「**估算字节** = 宽 × 高 × 4」自己算账，预算由平台缝
 * [imageMemoryCacheBudgetBytes] 给（Android = 搬迁前那句原话；桌面/Web = 固定 32MB）。
 * Android 上解出来的是 `RGB_565`（2 字节/像素），估算值因此偏大一倍 ⇒ 缓存**只可能装得更少**，
 * 不会因此多占内存（最坏是多解一次图）。这是如实的近似，不是漏做。
 */
class Jiaocai1PageImages(private val source: Jiaocai1Source) {

    private val budget = imageMemoryCacheBudgetBytes()

    /**
     * 自己写的 LRU（访问序）：`LinkedHashMap` 在公共代码里就有（JVM 侧就是同一个类），
     * 只是没有 `removeEldestEntry` 这一档 ⇒ 每次 put 之后自己淘汰到预算以内。
     */
    private val lru = LinkedHashMap<String, ImageBitmap>()

    private var bytes = 0L

    /** 取不到封面的那些 URL：记住它们，别每帧重试（搬迁前是 `Collections.synchronizedSet`）。 */
    private val coverMisses = mutableSetOf<String>()

    /**
     * 取一页并解成可画的位图；失败返回 null。
     *
     * [targetWidthPx] 是页面宽度（像素）；[tier] 见 [Jiaocai1DecodeTier]。
     */
    suspend fun load(
        handle: Jiaocai1BookHandle,
        page: Jiaocai1Page,
        targetWidthPx: Int,
        tier: Jiaocai1DecodeTier = Jiaocai1DecodeTier.SCREEN,
    ): ImageBitmap? {
        val memKey = "${handle.ssno}/${page.fileName}@$targetWidthPx/$tier"
        cached(memKey)?.let { return it }
        val bytes = source.pageBytes(handle, page) ?: return null
        return decodeInto(memKey, bytes, targetWidthPx, tier)
    }

    /** 取封面并解成可画的位图；[url] 为空、取不到、或不是图片都返回 null。 */
    suspend fun loadCover(url: String, targetWidthPx: Int): ImageBitmap? {
        if (url.isBlank()) return null
        val memKey = "cover:$url@$targetWidthPx"
        cached(memKey)?.let { return it }
        if (url in coverMisses) return null
        val bytes = source.coverBytes(url)
        if (bytes == null) {
            coverMisses.add(url)
            return null
        }
        return decodeInto(memKey, bytes, targetWidthPx, Jiaocai1DecodeTier.SCREEN)
    }

    /** 预取窗口转发（屏滚动时调；见 [Jiaocai1Source.prefetchWindow]）。 */
    fun setPrefetchWindow(handle: Jiaocai1BookHandle, center: Int) = source.prefetchWindow(handle, center)

    private fun decodeInto(
        memKey: String,
        raw: ByteArray,
        targetWidthPx: Int,
        tier: Jiaocai1DecodeTier,
    ): ImageBitmap? {
        // 搬迁前先读一遍文件头（`inJustDecodeBounds`）再解：一次解析尺寸、一次降采样解码。
        val size: IntSize = decodeImageSize(raw) ?: return null
        val want = when (tier) {
            Jiaocai1DecodeTier.SCREEN -> targetWidthPx
            Jiaocai1DecodeTier.DETAIL -> (targetWidthPx * 2).coerceAtLeast(targetWidthPx)
        }
        val decoded = decodeImageDownToWidth(
            bytes = raw,
            size = size,
            wantWidthPx = want,
            halveSample = tier == Jiaocai1DecodeTier.DETAIL,
        ) ?: return null
        put(memKey, decoded)
        return decoded
    }

    private fun cached(key: String): ImageBitmap? {
        val hit = lru.remove(key) ?: return null
        lru[key] = hit // 重新放到访问序末尾
        return hit
    }

    private fun put(key: String, image: ImageBitmap) {
        lru.remove(key)?.let { bytes -= estimate(it) }
        lru[key] = image
        bytes += estimate(image)
        val iterator = lru.entries.iterator()
        while (bytes > budget && iterator.hasNext()) {
            val (k, v) = iterator.next()
            iterator.remove()
            bytes -= estimate(v)
            if (k == key) break // 刚放进去的那张不淘汰（搬迁前的 LruCache 也是先放再淘汰）
        }
    }

    private fun estimate(image: ImageBitmap): Long =
        image.width.toLong() * image.height.toLong() * 4L

    /** 清空内存缓存（移出书架、令牌过期重新打开书时调；搬迁前是 `memory.evictAll()`）。 */
    fun clear() {
        lru.clear()
        coverMisses.clear()
        bytes = 0L
    }
}
