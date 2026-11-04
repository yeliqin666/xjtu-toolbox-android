package com.xjtu.toolbox.jiaocai1

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.xjtu.toolbox.platform.decodeImageDownToWidth
import com.xjtu.toolbox.platform.decodeImageSize
import com.xjtu.toolbox.platform.imageMemoryCacheBudgetBytes

/** 解码档位：屏上看的那一档 / 放大后更清楚的那一档。 */
enum class Jiaocai1DecodeTier { SCREEN, DETAIL }

/** 教材页面的解码后位图缓存。 */
class Jiaocai1PageImages(private val source: Jiaocai1Source) {

    private val budget = imageMemoryCacheBudgetBytes()

    /** 自己写的 LRU（访问序） */
    private val lru = LinkedHashMap<String, ImageBitmap>()

    private var bytes = 0L

    /** 取不到封面的 URL：记住它们，别每帧重试。 */
    private val coverMisses = mutableSetOf<String>()

    /** 取一页并解成可画的位图；失败返回 null。 */
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

    /** 没有封面地址时退而取本地缓存的首页；没缓存返回 null。 */
    suspend fun loadFirstPage(ssno: String, targetWidthPx: Int): ImageBitmap? {
        val memKey = "first:$ssno@$targetWidthPx"
        cached(memKey)?.let { return it }
        val bytes = source.cachedFirstPageBytes(ssno) ?: return null
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

    /** 清空内存缓存（移出书架、令牌过期重新打开书时调）。 */
    fun clear() {
        lru.clear()
        coverMisses.clear()
        bytes = 0L
    }
}
