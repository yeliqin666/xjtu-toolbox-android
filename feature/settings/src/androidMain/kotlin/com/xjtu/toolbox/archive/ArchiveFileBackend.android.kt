package com.xjtu.toolbox.archive

import com.xjtu.toolbox.platform.androidPlatformContext
import java.io.File

/** Android 侧的 `files` 层投影（只读）。 */
actual fun archiveFileBackend(): ArchiveFileBackend? {
    val context = androidPlatformContext() ?: return null
    return AndroidFileBackend(context.filesDir, context.cacheDir)
}

actual fun archiveCacheRoot(): String? = androidPlatformContext()?.cacheDir?.absolutePath

/** Android 文件投影：`files/agent_sessions/…` ⇄ `filesDir/…`、`files/schedule/…` ⇄ `cacheDir/data_cache<suffix>/`。 */
class AndroidFileBackend(
    private val dataRoot: File,
    private val cacheRoot: File?,
) : ArchiveFileBackend {

    override fun list(suffix: String, prefix: String): List<String> = when (prefix) {
        "agent_sessions" -> agentSessionsDir(suffix)
            .listFiles { f -> f.isFile }
            ?.map { "agent_sessions/${it.name}" }
            ?.sorted()
            ?: emptyList()
        // §2.3：`files/schedule/<key>.json` 的键名升序；`.tmp` 残片要忽略（§6.3）。
        "schedule" -> scheduleDir(suffix)
            ?.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { "schedule/${it.name}" }
            ?.sorted()
            ?: emptyList()
        else -> emptyList()
    }

    override fun read(suffix: String, relPath: String): String? = when {
        relPath.startsWith("agent_sessions/") -> {
            val file = File(agentSessionsDir(suffix), relPath.removePrefix("agent_sessions/"))
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
        relPath.startsWith("schedule/") -> {
            val dir = scheduleDir(suffix) ?: return null
            val file = File(dir, relPath.removePrefix("schedule/"))
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
        else -> null
    }

    /** §6.3「mtime → `at`」：成绩缓存那一族的年龄就是文件 mtime（[ArchiveScoreReport]）。 */
    override fun mtimeMs(suffix: String, relPath: String): Long? = when {
        relPath.startsWith("agent_sessions/") -> {
            val file = File(agentSessionsDir(suffix), relPath.removePrefix("agent_sessions/"))
            if (file.isFile) file.lastModified().takeIf { it > 0L } else null
        }
        relPath.startsWith("schedule/") -> {
            val dir = scheduleDir(suffix) ?: return null
            val file = File(dir, relPath.removePrefix("schedule/"))
            if (file.isFile) file.lastModified().takeIf { it > 0L } else null
        }
        else -> null
    }

    private fun agentSessionsDir(suffix: String): File = File(dataRoot, "agent_sessions$suffix")

    private fun scheduleDir(suffix: String): File? = cacheRoot?.let { File(it, "data_cache$suffix") }
}
