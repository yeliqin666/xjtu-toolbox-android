package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 屁岱记住的用户偏好（[AgentMemory]）—— 从 `:app` 搬进 `:core` 的那一份。
 *
 * 存储换成 [KeyValueStore] 缝之后，这里能**在 jvm 上**把「上限挤旧、键序、迁移、prompt 片段」
 * 逐条钉住（以前只有 Android 单测能覆盖）。用一份假存储，不碰任何平台文件。
 *
 * ⚠️ 迁移那两条要动 [AccountContext.activeAccountId]（进程级全局），用例自己负责还原。
 */
class AgentMemoryTest {

    /** 进程内的假键值存储：语义与 `SharedPreferences` 对齐（写 null 等于删键）。 */
    private class FakeStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String): String? = map[key] as? String
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) {
            map[key] = value
        }
        override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) {
            map[key] = value
        }
        override fun getStringSet(key: String): Set<String>? = null
        override fun putStringSet(key: String, value: Set<String>) = Unit
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() = map.clear()
    }

    /** 只读旧版那份（迁移的源）。 */
    private fun legacyStore(vararg pairs: Pair<String, String>): FakeStore = FakeStore().apply {
        map["__order"] = pairs.joinToString("\u0001") { it.first }
        pairs.forEach { (k, v) -> map[k] = v }
    }

    private fun withAccount(id: String?, body: () -> Unit) {
        val before = AccountContext.activeAccountId
        AccountContext.activeAccountId = id
        try {
            body()
        } finally {
            AccountContext.activeAccountId = before
        }
    }

    @Test
    fun `记住之后读得回，最近写入的排在最后`() {
        val store = FakeStore()
        val memory = AgentMemory(storeOf = { store })
        memory.remember("校区", "兴庆")
        memory.remember("口味", "清淡")
        assertEquals(listOf("校区" to "兴庆", "口味" to "清淡"), memory.all())
        // 重写同一个键只把它挪到队尾，不产生第二条
        memory.remember("校区", "创新港")
        assertEquals(listOf("口味" to "清淡", "校区" to "创新港"), memory.all())
    }

    @Test
    fun `键与值都截断到上限，空的拒收且不动已有内容`() {
        val store = FakeStore()
        val memory = AgentMemory(storeOf = { store })
        assertEquals("键和内容都不能为空。", memory.remember("  ", "值"))
        assertEquals("键和内容都不能为空。", memory.remember("键", "   "))
        assertEquals(emptyList(), memory.all())

        memory.remember("k".repeat(60), "v".repeat(200))
        val (k, v) = memory.all().single()
        assertEquals(40, k.length)
        assertEquals(120, v.length)
    }

    @Test
    fun `超过上限挤掉最旧的，回话里说清挤掉了谁`() {
        val store = FakeStore()
        val memory = AgentMemory(storeOf = { store })
        repeat(AgentMemory.MAX_ITEMS) { memory.remember("k$it", "v$it") }
        assertEquals(AgentMemory.MAX_ITEMS, memory.all().size)

        val reply = memory.remember("最新", "v")
        assertTrue(reply.contains("记住了：最新 = v"), reply)
        assertTrue(reply.contains("已挤掉最旧的「k0」"), reply)
        assertEquals(AgentMemory.MAX_ITEMS, memory.all().size)
        assertNull(store.map["k0"])
        assertEquals("最新" to "v", memory.all().last())
    }

    @Test
    fun `忘掉一个键只删它，没记过的如实回话`() {
        val store = FakeStore()
        val memory = AgentMemory(storeOf = { store })
        memory.remember("校区", "兴庆")
        memory.remember("口味", "清淡")
        assertEquals("忘掉了「校区」。", memory.forget("校区"))
        assertEquals(listOf("口味" to "清淡"), memory.all())
        assertEquals("没有记过「校区」。", memory.forget("校区"))
    }

    @Test
    fun `拼进系统提示的那一段：没有偏好是空串，有偏好带标题与收尾说明`() {
        val store = FakeStore()
        val memory = AgentMemory(storeOf = { store })
        assertEquals("", memory.promptBlock())
        memory.remember("校区", "兴庆")
        assertEquals(
            "# 用户偏好\n- 校区：兴庆\n偏好只影响表达方式和查询顺序，不影响查不查。",
            memory.promptBlock().removePrefix("\n"),
        )
    }

    @Test
    fun `全部清空`() {
        val store = FakeStore()
        val memory = AgentMemory(storeOf = { store })
        memory.remember("校区", "兴庆")
        memory.clear()
        assertEquals(emptyList(), memory.all())
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `没登录时不碰旧版那份`() = withAccount(null) {
        val current = FakeStore()
        val legacy = legacyStore("校区" to "兴庆")
        val memory = AgentMemory(storeOf = { current }, legacyStoreOf = { legacy })
        assertEquals(emptyList(), memory.all())
        assertEquals("兴庆", legacy.map["校区"])
    }

    @Test
    fun `登录后旧版不分账号的偏好被接手，接手后旧文件清干净`() = withAccount("2210000001") {
        val current = FakeStore()
        val legacy = legacyStore("校区" to "兴庆", "口味" to "清淡")
        val memory = AgentMemory(storeOf = { current }, legacyStoreOf = { legacy })

        assertEquals(listOf("校区" to "兴庆", "口味" to "清淡"), memory.all())
        // 旧那份一个键都不留（含键序）
        assertTrue(legacy.map.isEmpty(), "旧版那份应当被清干净：${legacy.map.keys}")
    }

    @Test
    fun `当前账号已经有偏好时，旧版只被清掉、不覆盖`() = withAccount("2210000001") {
        // 这个账号自己已经记过偏好（键序也在），迁移不该把旧版的键混进来
        val current = FakeStore().apply {
            map["__order"] = "口味"
            map["口味"] = "清淡"
        }
        val legacy = legacyStore("校区" to "兴庆")
        val memory = AgentMemory(storeOf = { current }, legacyStoreOf = { legacy })

        assertEquals(listOf("口味" to "清淡"), memory.all())
        assertTrue(legacy.map.isEmpty(), "旧版那份应当被清干净：${legacy.map.keys}")
    }
}
