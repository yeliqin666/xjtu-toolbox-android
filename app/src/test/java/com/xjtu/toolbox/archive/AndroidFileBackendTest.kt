package com.xjtu.toolbox.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
/** Android `files` 层投影的验收（§2.3 / §8.7 的 `data_cache*` 位置解析）。 */
class AndroidFileBackendTest {

    private fun newTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "")
        f.delete()
        f.mkdirs()
        return f
    }

    private fun withRoots(block: (dataRoot: File, cacheRoot: File) -> Unit) {
        val dataRoot = newTempDir("xjtu-fb-data")
        val cacheRoot = newTempDir("xjtu-fb-cache")
        try {
            block(dataRoot, cacheRoot)
        } finally {
            dataRoot.deleteRecursively()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `agent_sessions 与 schedule 按 suffix 投影且键名升序`() = withRoots { dataRoot, cacheRoot ->
        val sessions = File(dataRoot, "agent_sessions_2253415556")
        sessions.mkdirs()
        File(sessions, "index.json").writeText("""[{"id":"s1"}]""")

        val cache = File(cacheRoot, "data_cache_2253415556")
        cache.mkdirs()
        File(cache, "schedule_2026-2027-1.json").writeText("""{"courses":[]}""")
        File(cache, "exams_2026-2027-1.json").writeText("""[]""")

        val backend = AndroidFileBackend(dataRoot, cacheRoot)
        assertEquals(
            listOf("schedule/exams_2026-2027-1.json", "schedule/schedule_2026-2027-1.json"),
            backend.list("_2253415556", "schedule"),
        )
        assertEquals("""{"courses":[]}""", backend.read("_2253415556", "schedule/schedule_2026-2027-1.json"))
        assertEquals(listOf("agent_sessions/index.json"), backend.list("_2253415556", "agent_sessions"))
        assertEquals("""[{"id":"s1"}]""", backend.read("_2253415556", "agent_sessions/index.json"))
    }

    @Test
    fun `schedule 忽略 tmp 残片`() = withRoots { dataRoot, cacheRoot ->
        // suffix "default" 无前导下划线（§4 `suffixFor(null) == "default"`）⇒ 目录名是 `data_cachedefault`。
        val cache = File(cacheRoot, "data_cachedefault")
        cache.mkdirs()
        File(cache, "schedule_2026-2027-1.json").writeText("""{}""")
        File(cache, "schedule_2026-2027-1.json.tmp").writeText("""partial""")

        val backend = AndroidFileBackend(dataRoot, cacheRoot)
        assertEquals(listOf("schedule/schedule_2026-2027-1.json"), backend.list("default", "schedule"))
    }

    @Test
    fun `cacheRoot 为 null 时 schedule 无投影`() = withRoots { dataRoot, _ ->
        val backend = AndroidFileBackend(dataRoot, null)
        assertEquals(emptyList<Any>(), backend.list("default", "schedule"))
        assertNull(backend.read("default", "schedule/schedule_2026-2027-1.json"))
    }
}
