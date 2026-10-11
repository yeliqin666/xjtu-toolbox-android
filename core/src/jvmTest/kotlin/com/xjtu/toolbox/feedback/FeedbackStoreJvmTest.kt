package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.platform.keyValueStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 反馈的**本地账本**（[FeedbackStore]）在 JVM 上的口径 —— 它这一轮从 `:app`（`Context` +
 * `SharedPreferences`）搬进 `:core`（`KeyValueStore` 键值缝），这份测试钉住的就是「搬完还是同一件事」。
 *
 * 钉住四类事：
 *  1. **落盘位置与类型一字未改**：存储名 `feedback`、键名 `tickets` / `read_replies` /
 *     `reply_fetch_at` / `reply_cache` / `public_qa` / `prompt_last_at` / `prompt_done_version` /
 *     `anon_id` —— Android 侧的 actual 就是同一个文件名的 `SharedPreferences`，老工单不丢；
 *  2. **工单账本**：最新在前、只留最近 20 条（`addTicket` 的 `take(20)`）；
 *  3. **回复/公开问答的缓存**：键值对往返、空字段过滤；
 *  4. **两处节流的判据**：`shouldFetchReplies`（force 绕过 / pending 档）与
 *     `shouldPrompt`（三重闸门，其中 `isConfigured=false` 直接 false —— 这一档搬迁前读的是
 *     `FeedbackApi.isConfigured`，现在由调用方传进来）。
 *
 * 放在 `jvmTest` 而不是 `commonTest`：jvm 的 `KeyValueStore` 是**进程内单例**（`MemoryKeyValueStore`），
 * 用例之间会互相看见，所以每个用例前显式清一次；commonTest 会在 wasm 上跑，而 wasm 那份落的是
 * `localStorage`，不适合放这里（与 `LibraryFavoritesJvmTest` 同一条口径）。
 */
class FeedbackStoreJvmTest {

    private val raw = keyValueStore("feedback")

    @BeforeTest
    fun clearStore() {
        raw.clear()
    }

    @Test
    fun `工单账本：落在 feedback 的 tickets 上，最新在前且只留 20 条`() {
        for (i in 1..25) {
            FeedbackStore.addTicket(FeedbackStore.Ticket("T${i}0000000", "类型$i", "内容$i", i.toLong()))
        }
        val tickets = FeedbackStore.tickets()
        assertEquals(20, tickets.size, "只留最近 20 条（搬迁前就是 take(20)）")
        assertEquals("T250000000", tickets.first().id, "最新的在最前面")
        assertEquals(25L, tickets.first().createdAt)
        assertEquals("T60000000", tickets.last().id, "保留的是最后加进去的那 20 条（i=6 起）")
        assertTrue(raw.getString("tickets")!!.startsWith("[{"), "落的是一个 JSON 数组：${raw.getString("tickets")}")
    }

    @Test
    fun `已读标记：落在 read_replies 上，值是一组字符串`() {
        FeedbackStore.markRead(listOf("T1"))
        FeedbackStore.markRead(listOf("T2", "T1"))
        assertEquals(setOf("T1", "T2"), FeedbackStore.readTickets())
        assertEquals(setOf("T1", "T2"), raw.getStringSet("read_replies"))
    }

    @Test
    fun `回复缓存：键值对往返（节流命中时屏直接拿它渲染）`() {
        FeedbackStore.cacheReplies(mapOf("TA" to ("示例回复" to "2026-10-01 12:00")))
        val cached = FeedbackStore.cachedReplies()
        assertEquals(setOf("TA"), cached.keys)
        assertEquals("示例回复" to "2026-10-01 12:00", cached.getValue("TA"))
    }

    @Test
    fun `公开问答缓存：问题或回答为空的那几条直接丢掉`() {
        FeedbackStore.cachePublicQa(
            listOf(
                FeedbackPublicQa("示例提问", "示例回答", "想要新功能"),
                FeedbackPublicQa("", "只有回答"),
                FeedbackPublicQa("只有提问", ""),
            ),
        )
        val cached = FeedbackStore.cachedPublicQa()
        assertEquals(1, cached.size, "两份不完整的都该被丢掉：$cached")
        assertEquals("示例提问", cached.single().question)
        assertEquals("想要新功能", cached.single().category)
    }

    @Test
    fun `拉取回复的节流：force 直接放行，拉过一次之后就不再放行`() {
        assertTrue(FeedbackStore.shouldFetchReplies(hasPending = true), "从没拉过 ⇒ 该拉")
        FeedbackStore.markRepliesFetched()
        assertFalse(FeedbackStore.shouldFetchReplies(hasPending = true), "刚拉过（10 分钟档）⇒ 不该再拉")
        assertFalse(FeedbackStore.shouldFetchReplies(hasPending = false), "刚拉过（6 小时档）⇒ 不该再拉")
        assertTrue(FeedbackStore.shouldFetchReplies(hasPending = false, force = true), "下拉刷新绕过节流")
    }

    @Test
    fun `公开问答的节流：拉过一次之后就不再放行`() {
        assertTrue(FeedbackStore.shouldFetchPublicQa(), "从没拉过 ⇒ 该拉")
        FeedbackStore.cachePublicQa(listOf(FeedbackPublicQa("示例提问", "示例回答")))
        assertFalse(FeedbackStore.shouldFetchPublicQa(), "刚拉过 ⇒ 不该再拉")
    }

    @Test
    fun `弹问卷的三重闸门：没配后端不弹、用得太少不弹、记过账就不再弹`() {
        assertFalse(
            FeedbackStore.shouldPrompt(versionCode = 89, usageCount = 100, isConfigured = false),
            "后端没配 ⇒ 从不弹（搬迁前读的是 FeedbackApi.isConfigured）",
        )
        assertFalse(
            FeedbackStore.shouldPrompt(versionCode = 89, usageCount = 7, isConfigured = true),
            "用了不到 8 次 ⇒ 不弹",
        )
        assertTrue(
            FeedbackStore.shouldPrompt(versionCode = 89, usageCount = 8, isConfigured = true),
            "够次数、没记过账、距上次超过两周 ⇒ 该弹",
        )
        FeedbackStore.markPrompted(versionCode = 89)
        assertFalse(
            FeedbackStore.shouldPrompt(versionCode = 89, usageCount = 100, isConfigured = true),
            "这个版本已经问过 ⇒ 不再问",
        )
        assertEquals("89", raw.getString("prompt_done_version"))
    }

    @Test
    fun `匿名标识：桌面没有设备标识 ⇒ 退回本机随机 ID，但同一份数据下稳定`() {
        val id = FeedbackStore.anonId()
        assertEquals(8, id.length, "8 位（搬迁前是 UUID 的前 8 位十六进制）")
        assertTrue(id.all { it in "0123456789abcdef" }, "十六进制：$id")
        assertEquals(id, FeedbackStore.anonId(), "已经落盘 ⇒ 每次都一样")
        assertEquals(id, raw.getString("anon_id"))
    }

    @Test
    fun `工单号：T 开头、长度与字母表都在 TICKET_RE 认得的范围内`() {
        val tickets = (1..20).map { FeedbackStore.newTicket() }
        val re = Regex("""T[A-Z0-9]{8,16}""")
        assertTrue(tickets.all { re.matches(it) }, "工单号形状变了：$tickets")
        assertEquals(11, tickets.first().length, "T + 10 位")
        assertEquals(20, tickets.toSet().size, "20 次生成不该撞号")
    }
}
