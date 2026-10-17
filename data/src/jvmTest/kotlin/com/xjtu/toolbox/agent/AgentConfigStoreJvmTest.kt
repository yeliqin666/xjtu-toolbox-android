package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 屁岱配置的存取。 */
class AgentConfigStoreJvmTest {

    private lateinit var root: File

    private fun store(plain: (String) -> KeyValueStore = { keyValueStore(it) }) =
        AgentConfigStore(plainStoreOf = plain)
    private val suffix get() = AccountContext.safeSuffix()

    private fun plainStore() = keyValueStore(AgentConfigStore.STORE + suffix)

    private fun secureStore() = secureKeyValueStore(AgentConfigStore.STORE_SECURE + suffix)

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("xjtu-agent-config-test").toFile()
        dataRootOverride = root
        AccountContext.activeAccountId = null
        plainStore().clear()
        wipeSecureStore(AgentConfigStore.STORE_SECURE + suffix)
    }

    @AfterTest
    fun tearDown() {
        AccountContext.activeAccountId = null
        plainStore().clear()
        wipeSecureStore(AgentConfigStore.STORE_SECURE + suffix)
        dataRootOverride = null
    }

    @Test
    fun `存下来再读回来，一个字段不丢`() {
        val config = AgentConfig(
            provider = AgentConfig.PROVIDER_CUSTOM,
            apiKey = "sk-fake-config-001",
            model = "my-model",
            baseUrl = "https://example.invalid/v1",
            assistantName = "小氪",
            searchEngine = AgentConfig.SEARCH_WIKI,
            thinkingEnabled = false,
            reasoningEffort = AgentConfig.REASONING_MAX,
            showReasoning = false,
        )
        store().save(config)
        assertEquals(config, store().load())
    }

    @Test
    fun `没配过时读到的是默认配置`() {
        assertEquals(AgentConfig(), store().load())
    }

    /** `keyConfigured` 是 `:core` 模型上的一格非密文信道（浏览器那一端拿不到 key）： 它不进落盘。 */
    @Test
    fun `keyConfigured 不落盘：存了也不写键，读回来恒为 false`() {
        store().save(AgentConfig(apiKey = "sk-fake-config-003", keyConfigured = true))

        assertNull(plainStore().getString("key_configured"), "非敏感那半不该多一个键")
        assertNull(plainStore().getString("keyConfigured"), "非敏感那半不该多一个键（驼峰写法也不许）")
        // 密码那一半里只有 api_key 这一个键
        assertNull(secureStore().getString("key_configured"))
        assertEquals("sk-fake-config-003", secureStore().getString("api_key"))

        val loaded = store().load()
        assertEquals(false, loaded.keyConfigured, "读回来那一格只能是默认的 false")
        assertTrue(loaded.isConfigured, "key 在本端 ⇒ isConfigured 仍靠 apiKey 那一格")
    }

    @Test
    fun `密钥只落在密文那半，明文那份里没有 api_key 这个键`() {
        store().save(AgentConfig(apiKey = "sk-fake-config-002", searchEngine = AgentConfig.SEARCH_BAIDU))

        assertEquals("sk-fake-config-002", secureStore().getString("api_key"))
        assertNull(plainStore().getString("api_key"), "API Key 不许出现在明文存储里")
        // 非敏感那半还在老键名上
        assertEquals(AgentConfig.SEARCH_BAIDU, plainStore().getString("search_engine"))
        assertEquals(AgentConfig.PROVIDER_DEEPSEEK, plainStore().getString("provider"))
    }

    @Test
    fun `两个存储的文件名与搬迁前逐字相同`() {
        store().save(AgentConfig(apiKey = "sk-fake-config-003"))

        val suffix = AccountContext.safeSuffix()
        assertEquals("default", suffix)
        assertTrue(
            File(root, AgentConfigStore.STORE_SECURE + suffix + ".properties").exists(),
            "密文那半应当落在 agent_config_securedefault.properties",
        )
        // 明文那半的 jvm 实现（`keyValueStore`）现在也真落盘 —— 只验名字（落盘根由 Gradle 系统属性隔离），
        // 名字就是 Android 上那个 SharedPreferences 文件名。
        assertEquals("agent_config", AgentConfigStore.STORE)
        assertEquals("agent_config_secure", AgentConfigStore.STORE_SECURE)
        assertEquals("agent_config_fallback", AgentConfigStore.STORE_LEGACY)
    }

    @Test
    fun `老配置里的搜索源按老判据收敛：已下线与认不出的都回自动`() {
        val plain = plainStore()
        plain.putString("search_engine", "sogou")
        assertEquals(AgentConfig.SEARCH_AUTO, store().load().searchEngine)

        plain.putString("search_engine", "bing")
        assertEquals(AgentConfig.SEARCH_AUTO, store().load().searchEngine)

        plain.putString("search_engine", "百度")
        assertEquals(AgentConfig.SEARCH_AUTO, store().load().searchEngine, "中文名不在名单里，按认不出处理")

        plain.putString("search_engine", AgentConfig.SEARCH_WECHAT)
        assertEquals(AgentConfig.SEARCH_WECHAT, store().load().searchEngine)
    }

    @Test
    fun `思考强度的别名与垃圾值在 load 时收敛，不让服务端 400`() {
        val plain = plainStore()
        plain.putString("reasoning_effort", "minimal")
        assertEquals(AgentConfig.REASONING_LOW, store().load().reasoningEffort)

        plain.putString("reasoning_effort", "xhigh")
        assertEquals(AgentConfig.REASONING_HIGH, store().load().reasoningEffort)

        plain.putString("reasoning_effort", "超强")
        assertEquals(AgentConfig.REASONING_AUTO, store().load().reasoningEffort)
    }

    @Test
    fun `助手名与开关缺省时的口径与搬迁前一致`() {
        val plain = plainStore()
        assertEquals(AgentConfig.DEFAULT_ASSISTANT_NAME, store().load().assistantName)
        assertTrue(store().load().thinkingEnabled)
        assertTrue(store().load().showReasoning)

        plain.putString("assistant_name", "一二三四五六七八九十十一十二三")
        assertEquals("一二三四五六七八九十十一…", store().load().assistantName, "超 12 字要截断")
    }

    @Test
    fun `保存时清掉老配置里的残留键`() {
        val plain = plainStore()
        plain.putString("max_tool_calls", "5")
        plain.putString("disabled_caps", "web")
        plain.putString("response_style", "亲切")

        store().save(AgentConfig())

        assertNull(plain.getString("max_tool_calls"))
        assertNull(plain.getString("disabled_caps"))
        assertNull(plain.getString("response_style"))
    }

    @Test
    fun `按账号分开命名空间，换账号看不到上一个人的配置`() {
        val names = mutableListOf<String>()
        val s = store(plain = { name -> names += name; keyValueStore(name) })

        AccountContext.activeAccountId = "2210000001"
        s.save(AgentConfig(assistantName = "甲的屁岱", apiKey = "sk-fake-jia"))
        assertEquals(listOf(AgentConfigStore.STORE + "_2210000001"), names)
        assertEquals("甲的屁岱", s.load().assistantName)

        AccountContext.activeAccountId = "2210000002"
        assertEquals(AgentConfig.DEFAULT_ASSISTANT_NAME, s.load().assistantName, "另一个账号不该看到上一个人的助手名")
        assertTrue(AgentConfigStore.STORE + "_2210000002" in names)
        // 密钥那半同样按账号分开（文件不同 = 解不开别人的）
        assertEquals("sk-fake-jia", secureKeyValueStore(AgentConfigStore.STORE_SECURE + "_2210000001").getString("api_key"))
        assertNull(secureKeyValueStore(AgentConfigStore.STORE_SECURE + "_2210000002").getString("api_key"))
    }
}
