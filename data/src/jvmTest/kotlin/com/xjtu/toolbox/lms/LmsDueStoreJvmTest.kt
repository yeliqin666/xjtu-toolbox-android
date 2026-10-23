package com.xjtu.toolbox.lms

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:data` 版的 `LmsDueStore`：老数据搬迁（`data_cache` 的 JSON 原文）与幂等性。
 *
 * 合并规则本身的契约单测在 `:app` 的 `LmsDueStoreTest`（逐字不动），这里只测「搬到 `:data` 之后
 * 老数据还读得回来」这件事。
 */
class LmsDueStoreJvmTest {

    /** 老格式：`data_cache<后缀>/lms_due_items.json` 里就是这份 `List<LmsDue>` 的 JSON。 */
    private val legacyJson = """
        [{"courseId":11,"courseName":"理论力学","activityId":77,"title":"第三章作业",
          "deadline":"2999-01-01T00:00:00Z","submitted":false,"fetchedAt":1700000000000}]
    """.trimIndent()

    /** 这些测试要可重复跑：先把本账号的 store 清干净（IDE 里连着重跑两次不能因为上一轮的标记而变红）。 */
    private fun reset(account: String) {
        keyValueStore("lms_due" + AccountContext.suffixFor(account)).clear()
    }

    @AfterTest
    fun detach() {
        LmsDueStore.installLegacySource(null)
    }

    @Test
    fun `老缓存搬迁一次之后就从新 store 读`() {
        val account = "jvm-migrate"
        reset(account)
        var reads = 0
        LmsDueStore.installLegacySource { asked ->
            assertEquals(account, asked, "搬迁必须问的是同一个账号")
            reads++
            legacyJson
        }

        val first = LmsDueStore.load(account)
        assertEquals(listOf("第三章作业"), first.map { it.title }, "老数据要读得回来")

        // 换成一个永远给别的数据的口：已经搬过就不该再问它（幂等）。
        LmsDueStore.installLegacySource { reads++; """[{"courseId":1,"title":"不该出现"}]""" }
        val second = LmsDueStore.load(account)
        assertEquals(listOf("第三章作业"), second.map { it.title }, "搬迁之后以新 store 为准")
        assertEquals(1, reads, "老数据只读一次")
    }

    @Test
    fun `新 store 空着且没有老数据时给空表并记住别再翻`() {
        val account = "jvm-empty"
        reset(account)
        var reads = 0
        LmsDueStore.installLegacySource { reads++; null }

        assertTrue(LmsDueStore.load(account).isEmpty())
        assertTrue(LmsDueStore.load(account).isEmpty())
        assertEquals(1, reads, "读不到也要打标记，别每次冷启动都翻旧文件")
    }

    @Test
    fun `save 之后 load 拿到合并结果且过期条目被丢掉`() {
        val account = "jvm-save"
        reset(account)
        LmsDueStore.save(
            listOf(
                LmsDue(
                    courseId = 1, courseName = "课", activityId = 2, title = "还有效",
                    deadline = "2999-01-02T00:00:00Z", fetchedAt = 1L,
                ),
                LmsDue(
                    courseId = 1, courseName = "课", activityId = 3, title = "过期一天以上",
                    deadline = "2000-01-01T00:00:00Z", fetchedAt = 1L,
                ),
            ),
            account,
        )
        val stored = LmsDueStore.load(account)
        assertTrue(stored.any { it.title == "还有效" })
        assertTrue(stored.none { it.title == "过期一天以上" }, "过期条目不该留在缓存里")
    }
}
