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
    fun `课表族从键值店投影出来（B-9）：键到 files schedule，侧挂时间戳当 mtime`() = withRoot { root ->
        // 与 DesktopScheduleHost.cache() 落在同一个文件上：<数据根>/schedule_cache<suffix>.properties
        File(root, "schedule_cache_2253415556.properties").writeText(
            """
            schedule_current_term=2026-2027-1
            schedule_current_term#written_at=1700000000000
            exams_2026-2027-1=[{"name":"高等数学"}]
            """.trimIndent(),
        )

        val backend = JvmFileBackend(root)
        assertEquals(
            listOf("schedule/exams_2026-2027-1.json", "schedule/schedule_current_term.json"),
            backend.list("_2253415556", "schedule"),
            "#written_at 不是一条文件，不许出现在清单里",
        )
        assertEquals("2026-2027-1", backend.read("_2253415556", "schedule/schedule_current_term.json"))
        assertEquals(1_700_000_000_000, backend.mtimeMs("_2253415556", "schedule/schedule_current_term.json"))
        assertNull(backend.mtimeMs("_2253415556", "schedule/exams_2026-2027-1.json"), "没有侧挂时间戳 ⇒ 年龄不可知")
        assertNull(backend.read("_2253415556", "schedule/score_report.json"), "成绩那一族不在这份店里")
    }

    @Test
    fun `没有课表缓存店时这一族为空`() = withRoot { root ->
        val backend = JvmFileBackend(root)
        assertEquals(emptyList(), backend.list("_2253415556", "schedule"))
        assertNull(backend.read("_2253415556", "schedule/schedule_2026-2027-1.json"))
    }

    @Test
    fun `archiveCacheRoot 桌面为 null`() {
        assertNull(archiveCacheRoot())
    }
}
