package com.xjtu.toolbox.archive

import java.io.File

/** 桌面（jvm）侧的 `files` 层投影（只读）。 */
actual fun archiveFileBackend(): ArchiveFileBackend? =
    archiveDataRoot()?.let { JvmFileBackend(File(it)) }

actual fun archiveCacheRoot(): String? = null

/** 桌面文件投影：`files/agent_sessions/…` ⇄ `<数据根>/agent_sessions<suffix>/…`。 */
class JvmFileBackend(private val dataRoot: File) : ArchiveFileBackend {

    override fun list(suffix: String, prefix: String): List<String> = when (prefix) {
        "agent_sessions" -> agentSessionsDir(suffix)
            .listFiles { f -> f.isFile }
            ?.map { "agent_sessions/${it.name}" }
            ?.sorted()
            ?: emptyList()
        // 桌面课表族在 KV 店，不在文件层（§2.3 / §5.1 第 1 行）。
        else -> emptyList()
    }

    override fun read(suffix: String, relPath: String): String? = when {
        relPath.startsWith("agent_sessions/") -> {
            val file = File(agentSessionsDir(suffix), relPath.removePrefix("agent_sessions/"))
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
        else -> null
    }

    private fun agentSessionsDir(suffix: String): File = File(dataRoot, "agent_sessions$suffix")
}
