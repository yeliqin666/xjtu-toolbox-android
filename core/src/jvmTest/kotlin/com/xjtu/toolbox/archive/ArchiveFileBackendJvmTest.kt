package com.xjtu.toolbox.archive

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 桌面（jvm）`files` 层投影的验收（§2.3）。 */
class ArchiveFileBackendJvmTest {

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("xjtu-file-backend").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `agent_sessions 目录名不带 suffix 投影为 files 相对路径`() = withRoot { root ->
        val dir = File(root, "agent_sessions_2253415556")
        dir.mkdirs()
        File(dir, "index.json").writeText("""[{"id":"s1"}]""")
        File(dir, "convo_s1.json").writeText("""{"messages":[]}""")

        val backend = JvmFileBackend(root)
        assertEquals(
            listOf("agent_sessions/convo_s1.json", "agent_sessions/index.json"),
            backend.list("_2253415556", "agent_sessions"),
        )
        assertEquals("""[{"id":"s1"}]""", backend.read("_2253415556", "agent_sessions/index.json"))
        assertNull(backend.read("_2253415556", "agent_sessions/missing.json"))
    }

    @Test
    fun `桌面没有 cacheDir 课表族不在文件层`() = withRoot { root ->
        val backend = JvmFileBackend(root)
        assertEquals(emptyList(), backend.list("_2253415556", "schedule"))
        assertNull(backend.read("_2253415556", "schedule/schedule_2026-2027-1.json"))
    }

    @Test
    fun `archiveCacheRoot 桌面为 null`() {
        assertNull(archiveCacheRoot())
    }
}
