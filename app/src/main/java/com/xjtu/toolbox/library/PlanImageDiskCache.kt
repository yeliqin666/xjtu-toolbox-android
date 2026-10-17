package com.xjtu.toolbox.library

/** 平面图图片的磁盘缓存。 */
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
