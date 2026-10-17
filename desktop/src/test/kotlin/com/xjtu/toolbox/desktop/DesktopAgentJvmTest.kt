package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentConfigStore
import com.xjtu.toolbox.agent.AgentMemory
import com.xjtu.toolbox.agent.AgentViewModel
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.SecretProtectionException
import com.xjtu.toolbox.platform.SecretProtector
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.yellowpage.YellowPageApi
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 屁岱在桌面端的装配验收。 */
class DesktopAgentJvmTest {

    private val root: File = Files.createTempDirectory("xjtu-desktop-agent-test").toFile()

    init {
        // ⚠️ 先装常驻前置：本类按名排在前面且会碰 `HttpClients`，否则后面的假上游用例会直连真站点。
        com.xjtu.toolbox.FakeCampusProxy.installFakeUpstreams()
        // 这一端没有登录：命名空间是 `default`（见 `AccountContext.suffixFor(null)`）
        AccountContext.activeAccountId = null
        // ⚠️ 先指到临时根目录、再清：`wipeSecureStore` 删的是**当前根目录**下那个文件，
        // 顺序反了就会去删用户真实的 ~/.local/share/xjtu-toolbox。
        dataRootOverride = root
        // 存储是**进程级缓存**（`:data` 的 `secureKeyValueStore` 按名字缓存实例）⇒ 每条用例先按名字清一遍：
        // 不清的话新根目录下会拿到上一条用例建的实例（文件句柄还指在旧根目录里）。
        for (name in listOf(
            AgentConfigStore.STORE + AccountContext.safeSuffix(),
            AgentConfigStore.STORE_SECURE + AccountContext.safeSuffix(),
            AgentMemory.STORE + AccountContext.safeSuffix(),
        )) {
            wipeSecureStore(name)
        }
    }

    @AfterTest
    fun tearDown() {
        dataRootOverride = null
    }

    private fun newHost(): DesktopAgentHost = DesktopAgentHost(DesktopAuth())

    /** 数据根目录里第一个内容含 [text] 的文件（用来断言「真写到了磁盘上」）。 */
    private fun fileContaining(text: String, suffix: String = ".json"): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(suffix) && it.readText().contains(text) }

    /** 在测试线程上等一个条件成立：VM 的协程跑在 `Dispatchers.Main`（桌面 = AWT 事件队列）上。 */
    private fun waitFor(what: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("等不到：$what（${timeoutMs}ms）")
    }

    @Test
    fun `夹具传输端到端：发一条、流式回一条、会话与配置都落盘`() {
        val transport = FixtureAgentTransport()
        val fixtureHost = fixtureAgentHost(DesktopAuth(), transport)

        // 配置：即改即存（与配置面板同一个动作）；有 key 才发得出去
        val config = AgentConfig(apiKey = "fixture-key", model = "fixture-model")
        fixtureHost.saveConfig(config)

        val userText = "帮我看一下桌面这一端接上了没有"
        val vm = AgentViewModel(fixtureHost)
        vm.bind(fixtureHost.sessions())
        vm.sendMessage(userText, config)

        waitFor("夹具回复上屏") { vm.messages.any { it.role == "assistant" && it.content.contains(AGENT_FIXTURE_REPLY) } }
        waitFor("这一轮结束（不再 loading）") { !vm.isLoading }

        val body = assertNotNull(transport.lastBody, "夹具传输没被调用 —— 这一轮根本没发出去")
        waitFor("会话落盘") { fileContaining(AGENT_FIXTURE_REPLY) != null }
        assertTrue(
            body.toString().contains(userText),
            "请求体里没有这条用户消息：$body",
        )
        assertTrue(transport.deltas > 1, "夹具是分段回调的，只收到 ${transport.deltas} 段")
        assertEquals(
            AGENT_FIXTURE_REPLY,
            vm.messages.last { it.role == "assistant" && it.content.isNotBlank() }.content,
        )

        val plain = File(root, "agent_config${AccountContext.safeSuffix()}.properties")
        assertTrue(plain.isFile, "配置的非敏感那半没落盘：${plain.absolutePath}")
        assertTrue(plain.readText().contains("fixture-model"), "配置里没有刚存的模型：${plain.readText()}")
        val secure = File(root, "agent_config_secure${AccountContext.safeSuffix()}.properties")
        assertTrue(secure.isFile, "配置的密钥那一半没落盘：${secure.absolutePath}")
        val reread = newHost().loadConfig()
        assertEquals("fixture-model", reread.model)
        assertEquals("fixture-key", reread.apiKey)

        val sessions = newHost().sessions().list()
        assertTrue(sessions.isNotEmpty(), "会话表是空的 —— 那一轮没落盘")
        val stored = sessions.firstNotNullOfOrNull { newHost().sessions().load(it.id) }
        assertNotNull(stored, "列得出会话却载不回来")
        assertTrue(
            stored.messages.any { it.role == "assistant" && it.content.contains(AGENT_FIXTURE_REPLY) },
            "落盘的会话里没有夹具回复：${stored.messages.map { it.role to it.content }}",
        )
        // 落盘位置也钉一下：会话是 `agent_sessions<账号后缀>/convo_*.json`
        val convo = fileContaining(AGENT_FIXTURE_REPLY)
        assertTrue(
            convo?.parentFile?.name == "agent_sessions${AccountContext.safeSuffix()}",
            "会话没落在 agent_sessions 目录里：${convo?.absolutePath}",
        )
    }

    @Test
    fun `工具表只画接得上的那五件，没接的一档都不画`() {
        val host = newHost()
        val tools = host.tools(AgentConfig.SEARCH_AUTO)

        val names = (AppJson.parseToJsonElement(tools.toolDefinitions) as JsonArray)
            .map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(DESKTOP_AGENT_TOOLS, names, "工具表与「这一端接得上的那几件」对不上")

        // 没接的档一个字都不画（画了就得能用）——调过来只会回 not_found
        for (missing in listOf("get_schedule", "get_grades", "get_empty_rooms", "web_search", "web_fetch", "set_alarm", "app_setting", "add_schedule_event")) {
            assertTrue(missing !in names, "没接的档画出来了：$missing")
        }
        runBlocking {
            assertEquals(
                "error: not_found; tool: get_schedule",
                tools.execute("get_schedule", "{}"),
            )
        }

        assertTrue(host.honored.isEmpty(), "honored 不该有值：${host.honored}")
    }

    @Test
    fun `纯逻辑那三件真能跑：算术・功能引导・偏好落盘`() {
        val tools = newHost().tools(AgentConfig.SEARCH_AUTO)

        runBlocking {
            assertEquals("2*3+4 = 10", tools.execute("calculate", """{"expression":"2*3+4"}"""))
            assertEquals("error: missing_param; param: expression", tools.execute("calculate", "{}"))

            // app_guide：两个话题的判据与 `:core` 的 agentGuideText 同一份
            val campus = tools.execute("app_guide", """{"topic":"campus"}""")
            assertTrue(campus.contains("兴庆校区"), "campus 话题没给校区那段：$campus")
            assertTrue(!campus.contains("App 功能与入口"), "campus 话题不该带 App 功能那段")
            val both = tools.execute("app_guide", "{}")
            assertTrue(both.contains("App 功能与入口") && both.contains("兴庆校区"), "不带 topic 时两段都要给")

            // preference：记下来就进系统提示那一段，删掉就没了
            assertEquals("记住了：常在 = 兴庆", tools.execute("preference", """{"key":"常在","value":"兴庆"}"""))
            assertTrue(tools.memoryBlock().contains("- 常在：兴庆"), "偏好没进系统提示：${tools.memoryBlock()}")
            assertEquals("忘掉了「常在」。", tools.execute("preference", """{"key":"常在"}"""))
            assertTrue(tools.memoryBlock().isEmpty(), "删掉之后不该还在：${tools.memoryBlock()}")
        }

        // 记忆是**真落盘**的：另一份工具层（新一份 `AgentMemory`）读得回来
        runBlocking { newHost().tools(AgentConfig.SEARCH_AUTO).execute("preference", """{"key":"校区","value":"兴庆"}""") }
        val reopened = newHost().tools(AgentConfig.SEARCH_AUTO)
        assertTrue(reopened.memoryBlock().contains("- 校区：兴庆"), "偏好没落在数据根目录里：${reopened.memoryBlock()}")
        val memoryFile = File(root, "${AgentMemory.STORE}${AccountContext.safeSuffix()}.properties")
        assertTrue(memoryFile.isFile, "偏好存储文件不在：${memoryFile.absolutePath}")
    }

    @Test
    fun `两件免登录取数走夹具上游：黄页与教师检索`() {
        val tools = DesktopAgentTools(
            honored = emptySet(),
            memory = AgentMemory(),
            yellowPage = YellowPageApi(mockYellowPageClient()),
            faculty = FacultyApi(mockFacultyClient()),
        )

        runBlocking {
            // 黄页：按类别筛（夹具那三档里「教务处」= id 1，停用的那条不该出现）
            val byCategory = tools.execute("search_yellow_page", """{"category":"教务处"}""")
            assertTrue(byCategory.contains("教学运行中心｜029-82668888｜教务处"), "类别筛选没生效：$byCategory")
            assertTrue(!byCategory.contains("已撤销的科室"), "停用的科室不该出现：$byCategory")
            assertTrue(byCategory.contains("更新于2026年08月01日"), "更新时间的格式化断了：$byCategory")

            assertEquals(
                "error: not_found; category: 不存在的机构; options: 教务处, 学生工作部（处）",
                tools.execute("search_yellow_page", """{"category":"不存在的机构"}"""),
            )

            // 教师检索：夹具那位老师（值都是编的）
            val faculty = tools.execute("find_faculty", """{"name":"示例甲"}""")
            assertTrue(faculty.contains("【示例甲】"), "夹具那位老师没找到：$faculty")
            assertTrue(faculty.contains("研究方向：方向甲、方向乙"), "研究方向那段不对：$faculty")
            assertEquals("error: missing_param; param: name", tools.execute("find_faculty", "{}"))
        }
    }

    /** 密钥那一半的落盘（`docs/agent-web-spec.md` §5.1）。 */
    @Test
    fun `密钥那一半：旧明文读得回、文件名不变、有保护器时落信封`() {
        val secureFile = File(root, "agent_config_secure${AccountContext.safeSuffix()}.properties")

        secureFile.parentFile?.mkdirs()
        secureFile.writeText("api_key=sk-fake-legacy-plaintext\n")
        assertEquals("sk-fake-legacy-plaintext", newHost().loadConfig().apiKey, "老明文必须原样读得回来")

        newHost().saveConfig(AgentConfig(apiKey = "sk-fake-desktop-001", model = "fixture-model"))
        val plainText = secureFile.readText()
        assertTrue(plainText.contains("api_key"), "键名一字未改：$plainText")
        assertTrue(plainText.contains("sk-fake-desktop-001"), "本机没有保护器 ⇒ 与今天逐字相同：$plainText")
        assertEquals("sk-fake-desktop-001", newHost().loadConfig().apiKey)

        DesktopAgentHost(DesktopAuth(), FixtureSecretProtector())
            .saveConfig(AgentConfig(apiKey = "sk-fake-desktop-002", model = "fixture-model"))
        val sealedText = secureFile.readText()
        // ⚠️ `Properties` 会把值里的冒号转义成 `\:`（文件层面的事，读回来还是冒号）⇒ 只断言前缀
        assertTrue(sealedText.contains("xjtu-sealed"), "有保护器时盘上应当是信封：$sealedText")
        assertFalse(sealedText.contains("sk-fake-desktop-002"), "有保护器时盘上不许留明文：$sealedText")
        assertEquals(
            "sk-fake-desktop-002",
            DesktopAgentHost(DesktopAuth(), FixtureSecretProtector()).loadConfig().apiKey,
            "同一个保护器要读得回来（前缀与 id 都对得上才解）",
        )
        assertFailsWith<SecretProtectionException>("换用户 / 换机器 / 跨平台拷过来的文件：抛") {
            DesktopAgentHost(DesktopAuth(), FixtureSecretProtector("other")).loadConfig()
        }
    }
}

/** 夹具凭据保护器：把明文倒过来当密文（必须真的改变字节，见 `SecretProtector` 的契约）。 */
private class FixtureSecretProtector(private val name: String = "fixture") : SecretProtector {

    override val id: String get() = name

    override fun protect(plain: ByteArray): ByteArray = plain.reversedArray()

    override fun unprotect(sealed: ByteArray): ByteArray = sealed.reversedArray()
}
