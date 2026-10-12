package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 屁岱的多会话落盘（`:app` → `:data` 的那一条）。
 *
 * 会话是**一会话一文件**（`index.json` + `convo_<id>.json`），所以「搬迁不许改落盘口径」这件事
 * 只能靠文件名与 JSON 指向钉：老用户的会话全在这些文件里，换格式就等于会话全丢。
 * 这份测试因此逐条钉住：
 *
 * 1. 目录与文件名（`agent_sessions<suffix>/index.json`、`convo_<id>.json`）与搬迁前逐字相同；
 * 2. 索引里的字段名（`id/title/createdAt/updatedAt/locked`）与旧文件读得回来；
 * 3. 老会话缺新字段（`images`、`contextExhausted`）时仍能反序列化；
 * 4. 切账号即换目录，两个账号的会话互相看不见。
 */
class AgentSessionStoreJvmTest {

    private lateinit var root: File

    private fun store() = AgentSessionStore(rootDirOf = { root })

    private fun dir(suffix: String = AccountContext.safeSuffix()) = File(root, "agent_sessions$suffix")

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("xjtu-agent-session-test").toFile()
        AccountContext.activeAccountId = null
    }

    @AfterTest
    fun tearDown() {
        AccountContext.activeAccountId = null
        root.deleteRecursively()
    }

    @Test
    fun `新会话落在同名目录与同名文件里`() {
        val session = store().create()

        assertEquals("新对话", session.title)
        assertEquals(session.createdAt, session.updatedAt)
        assertFalse(session.locked)
        assertTrue(File(dir(), "index.json").exists(), "索引还是 index.json")
        assertTrue(File(dir(), "convo_${session.id}.json").exists(), "会话内容还是 convo_<id>.json")

        val index = File(dir(), "index.json").readText()
        listOf("\"id\"", "\"title\"", "\"createdAt\"", "\"updatedAt\"", "\"locked\"").forEach {
            assertTrue(index.contains(it), "索引里应当有 $it：$index")
        }
    }

    @Test
    fun `建两个会话，列表按最近更新降序`() {
        val s = store()
        val first = s.create()
        Thread.sleep(2)
        val second = s.create()
        assertEquals(listOf(second.id, first.id), s.list().map { it.id })

        Thread.sleep(2)
        s.rename(first.id, "问过了")
        assertEquals(listOf(first.id, second.id), s.list().map { it.id })
        assertEquals("问过了", s.list().first().title)
    }

    @Test
    fun `改名默认锁标题，AI 总结不会再覆盖它`() {
        val s = store()
        val session = s.create()
        assertFalse(s.isLocked(session.id))

        s.rename(session.id, "期末成绩")
        assertTrue(s.isLocked(session.id))
        assertEquals("期末成绩", s.list().single().title)

        // 不锁的那种（AI 自动起的标题）下次还能被改
        s.rename(session.id, "临时", lock = false)
        assertFalse(s.isLocked(session.id))
    }

    @Test
    fun `标题一律按 12 字截断`() {
        val session = store().create("一二三四五六七八九十十一十二三")
        assertEquals("一二三四五六七八九十十一…", session.title)
        assertEquals(session.title, store().list().single().title)
    }

    @Test
    fun `会话内容往返：消息、LLM 历史、token 与图片路径一个不丢`() {
        val s = store()
        val session = s.create()
        val convo = StoredConversation(
            messages = listOf(
                StoredMessage(
                    role = "user",
                    content = "今天有什么课",
                    nav = listOf(listOf("看课表", "schedule")),
                    widgets = listOf(StoredWidget("ScheduleWidget", """{"day":1}""")),
                    reasoningContent = "先查课表",
                    timestamp = 1_700_000_000_000L,
                    toolError = null,
                    images = listOf("/data/user/0/…/photo.jpg"),
                ),
                StoredMessage(role = "tool_event", content = "已查课表", toolError = "error: timeout"),
            ),
            llmHistory = """[{"role":"user","content":"今天有什么课"}]""",
            lastTotalTokens = 12_345L,
            contextExhausted = false,
        )

        s.save(session.id, convo, "今天的课")
        val back = assertNotNull(s.load(session.id))

        assertEquals(convo, back)
        assertEquals(listOf("/data/user/0/…/photo.jpg"), back.messages[0].images)
        assertEquals("error: timeout", back.messages[1].toolError)
        assertEquals("今天的课", s.list().single().title)
    }

    @Test
    fun `save 会给没登记过的会话补一条索引`() {
        val s = store()
        s.save("手写的id", StoredConversation(), "补登记")

        assertEquals("手写的id", s.list().single().id)
        assertEquals("补登记", s.list().single().title)
        assertEquals(StoredConversation(), s.load("手写的id"))
    }

    @Test
    fun `老会话的文件缺新字段也读得回来`() {
        val s = store()
        dir().mkdirs()
        // 老版本写出来的形状：没有 images、没有 contextExhausted、没有 widgets
        File(dir(), "convo_old.json").writeText(
            """{"messages":[{"role":"user","content":"你好"}],"llmHistory":"[]"}"""
        )

        val convo = assertNotNull(s.load("old"))
        assertEquals("你好", convo.messages.single().content)
        assertNull(convo.messages.single().images)
        assertNull(convo.lastTotalTokens)
        assertFalse(convo.contextExhausted)
        assertEquals("[]", convo.llmHistory)
    }

    @Test
    fun `读不到的会话返回 null 而不是抛`() {
        assertNull(store().load("不存在"))
        File(dir(), "convo_坏.json").writeText("{不是 JSON")
        assertNull(store().load("坏"))
    }

    @Test
    fun `删会话会把索引条目与内容文件一起删掉`() {
        val s = store()
        val keep = s.create()
        val drop = s.create()

        s.delete(drop.id)

        assertEquals(listOf(keep.id), s.list().map { it.id })
        assertFalse(File(dir(), "convo_${drop.id}.json").exists())
        assertNull(s.load(drop.id))
    }

    @Test
    fun `切账号即换目录，两个账号的会话互相看不见`() {
        val s = store()
        AccountContext.activeAccountId = null
        val mine = s.create()

        AccountContext.activeAccountId = "2210000001"
        assertEquals(emptyList(), s.list(), "另一个账号不该看到默认空间的会话")
        val theirs = s.create()
        assertTrue(File(root, "agent_sessions_2210000001/convo_${theirs.id}.json").exists())
        assertEquals(listOf(theirs.id), s.list().map { it.id })

        AccountContext.activeAccountId = null
        assertEquals(listOf(mine.id), s.list().map { it.id })
        assertFalse(File(root, "agent_sessions_2210000001/convo_${mine.id}.json").exists())
    }
}
