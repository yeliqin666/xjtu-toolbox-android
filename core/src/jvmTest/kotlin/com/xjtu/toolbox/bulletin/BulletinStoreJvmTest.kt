package com.xjtu.toolbox.bulletin

import com.xjtu.toolbox.platform.keyValueStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 公告的本地状态（[BulletinStore]）—— 搬进 `:core` 时 `Context` + `SharedPreferences` 换成
 * 平台键值缝的那一段，逐条钉住。
 *
 * ## 它钉的是什么
 *
 * 「同名同键」这件事是**用户可见**的：Android 那份键值缝就是 `getSharedPreferences(name)`，
 * 所以只要有一样东西搬歪了（store 名、三个键名、集合类型），已经在用的用户就会发现自己的
 * 「已关掉的公告」「已确认的投票」全回来了。这里的断言就写在那条缝上：
 *
 *  - store 名逐字 `bulletin_store`，三个键逐字 `cached_json` / `dismissed_ids` / `acked_ids`；
 *  - 集合那一档是 `StringSet`（不是拼成一行的字符串）—— 与搬迁前同一个值类型；
 *  - `peekCached()` 走的还是 [BulletinRules.parsePayload]（坏 JSON 给空表，不抛）；
 *  - `snooze` 只在进程内（`sessionSnoozedIds`），不落盘 —— 这是搬迁前就有的语义。
 *
 * ## 为什么在 jvmTest
 *
 * 键值缝的 JVM 实现是**进程内**的（同名的 store 是同一个实例），所以这里的 setUp 要先
 * `clear()`；这也正是它不能被当成「落盘语义」来测的原因 —— 落盘那一档由各端的 actual 负责
 * （Android 就是上面那个 `getSharedPreferences`）。
 */
class BulletinStoreJvmTest {

    @BeforeTest
    fun setUp() {
        keyValueStore(STORE_NAME).clear()
    }

    @Test
    fun `缓存：写进去读得回来，走的是搬前那个 store 名与键`() {
        val store = BulletinStore()
        store.cachedJson = PAYLOAD

        val raw = keyValueStore(STORE_NAME).getString("cached_json")
        assertEquals(PAYLOAD, raw, "键名逐字是 `cached_json`，store 名逐字是 `bulletin_store`")

        val peeked = BulletinStore().peekCached()
        assertEquals(listOf("tips"), peeked.map { it.id }, "另起一个实例也读得到（缝是共享的）")
        assertEquals(BulletinLevel.WARN, peeked.single().level)
    }

    @Test
    fun `坏缓存：为空或不是 JSON 时 peekCached 给空表，不抛`() {
        assertTrue(BulletinStore().peekCached().isEmpty(), "没写过 ⇒ 空表")

        val store = BulletinStore()
        store.cachedJson = "这不是 JSON"
        assertTrue(store.peekCached().isEmpty())
    }

    @Test
    fun `已关闭与已确认：落在两个 StringSet 键上，累加不覆盖`() {
        val store = BulletinStore()
        assertEquals(emptySet(), store.dismissedIds)
        assertEquals(emptySet(), store.ackedIds)

        store.dismiss("a")
        store.dismiss("b")
        store.ack("poll-1")

        assertEquals(setOf("a", "b"), store.dismissedIds)
        assertEquals(setOf("poll-1"), store.ackedIds)
        assertEquals(setOf("a", "b"), keyValueStore(STORE_NAME).getStringSet("dismissed_ids"))
        assertEquals(setOf("poll-1"), keyValueStore(STORE_NAME).getStringSet("acked_ids"))

        // 另起一个实例读的是同一份状态（用户关过的公告不会因为换实例又冒出来）
        assertEquals(setOf("a", "b"), BulletinStore().dismissedIds)
    }

    @Test
    fun `force_update 的稍后：只在进程内，不落盘`() {
        val store = BulletinStore()
        assertTrue(!store.peekCached().any { it.id == "snoozed" })

        store.snooze("force-1")
        assertTrue("force-1" in BulletinStore.sessionSnoozedIds(), "这一次进程里算「稍后」")

        store.cachedJson = PAYLOAD
        assertTrue(
            keyValueStore(STORE_NAME).getStringSet("dismissed_ids").isNullOrEmpty(),
            "snooze 不该写进那条缝（下次冷启动要再出现）",
        )
    }

    private companion object {
        /** 与搬迁前 `BulletinStore.PREFS` 同一个名字（Android 上就是那个 SharedPreferences 文件）。 */
        const val STORE_NAME = "bulletin_store"

        val PAYLOAD = """
            {"bulletins":[{"id":"tips","title":"示例公告（假）","level":"warn","body":"夹具正文"}]}
        """.trimIndent()
    }
}
