package com.xjtu.toolbox.archive

import com.xjtu.toolbox.platform.androidPlatformContext
import java.io.File

/**
 * Android 侧的 `files` 层投影（S3，只读）。
 *
 * 落点逐字照既有实现（§8.1 / C1 红线）：
 *  - `files/agent_sessions/…` ⇄ `filesDir/agent_sessions<suffix>/`（`AgentSessionStore`）；
 *  - `files/schedule/<key>.json` ⇄ `cacheDir/data_cache<suffix>/<key>.json`（`DataCache`）。
 * 这里只枚举/读文件，不写任何端内文件（§8.8 的原子写留给 S4 的导入侧；Android 的 `DataCache`
 * 旧实现一个字不改）。
 *
 * 设计成「注入 provider」的形状（同 S2 的 [AndroidPrefsBackend]）：`:app:testDebugUnitTest` 没有真
 * `Context`，但 `java.io.File` 直接可用 ⇒ 把两个根（`filesDir` / `cacheDir`）注入进来就能跑断言。
 * [archiveFileBackend] / [archiveCacheRoot] 只是默认装配。
 */
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

    private fun agentSessionsDir(suffix: String): File = File(dataRoot, "agent_sessions$suffix")

    private fun scheduleDir(suffix: String): File? = cacheRoot?.let { File(it, "data_cache$suffix") }
}
