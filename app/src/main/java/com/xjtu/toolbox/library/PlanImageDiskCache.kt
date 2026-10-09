package com.xjtu.toolbox.library

/**
 * 平面图图片的磁盘缓存。图是学校服务器上的静态文件，一张几百 KB，经 WebVPN 下得很慢；
 * 缓存 7 天，过期或读坏了再重新下。
 *
 * 为什么留在 `:app`：它写的是 `Context.cacheDir` 下的文件（`java.io.File`），
 * `:core` 的公共代码里没有文件系统这一档。座位图的「取字节」在 `:app` 这一侧是
 * `AppLibrarySource.planBase/planTiles` 里那两行（缓存 + `LibraryApi.getPlanImage`），
 * 共享屏只收到字节。`:app` 的屁岱（`AgentTool` 的图书馆卡片）也直接用这个缓存。
 *
 * 搬迁前这个对象在 `LibrarySeatPlan.kt` 里，跟着那个文件一起搬进了 `:core` 的座位图那半；
 * 只有它没搬（原因见上），所以单独放一个文件。
 */
object PlanImageDiskCache {
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    suspend fun get(context: android.content.Context, name: String, download: suspend (String) -> ByteArray?): ByteArray? {
        val dir = java.io.File(context.cacheDir, "library_plan").apply { mkdirs() }
        val file = java.io.File(dir, name.replace('/', '_'))
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
            runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val bytes = download(name) ?: return null
        runCatching { file.writeBytes(bytes) }
        return bytes
    }
}
