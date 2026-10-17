package com.xjtu.toolbox.server

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AGENT_CONTEXT_EXHAUSTED_MESSAGE
import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentConfigStore
import com.xjtu.toolbox.agent.AgentFixture
import com.xjtu.toolbox.agent.AgentMemory
import com.xjtu.toolbox.agent.AgentSessionStore
import com.xjtu.toolbox.agent.AgentStreamResult
import com.xjtu.toolbox.agent.AgentToolCall
import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.AgentTransport
import com.xjtu.toolbox.agent.StoredWidget
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.protectedSecureKeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import io.ktor.http.HttpStatusCode
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `/api/agent/…` 的契约验收（`docs/api-contract.md` §5.11）。 */
class ServeAgentTest {

    @AfterTest
    fun resetSharedState() {
        dataRootOverride = null
        AccountContext.activeAccountId = null
    }

    /** 起一个只挂 `/api/agent*` 的真 serve（端口 0）+ 临时数据根，把夹具的配置 / 记忆 / 工具表 / 传输 / 起标题都塞进 [ServeAgent]，跑 [block]。 */
    private fun withAgentServer(
        config: AgentConfig? = null,
        memory: List<Pair<String, String>> = emptyList(),
        toolTable: (String) -> AgentTools = { FixtureAgentTools() },
        transport: AgentTransport = FixtureTransport(
            listOf(Step(deltas = AgentFixture.DELTAS, totalTokens = AgentFixture.TOTAL_TOKENS)),
        ),
        models: suspend (AgentConfig) -> List<String> = { AgentFixture.MODELS },
        generateTitle: suspend (AgentConfig, String, String) -> String? = { _, _, _ -> null },
        mfa: () -> String? = { null },
        pingMillis: Long = 0,
        block: (Harness) -> Unit,
    ) {
        // 进程级前置：必须在碰任何会话 / 客户端之前（见类 KDoc）
        FakeCampusProxy.installFakeUpstreams()
        dataRootOverride = Files.createTempDirectory("xjtu-serve-agent").toFile()
        wipeAgentStores()
        AccountContext.activeAccountId = null

        val memoryStore = AgentMemory(
            storeOf = { secureKeyValueStore(AgentMemory.STORE + AccountContext.safeSuffix()) },
            legacyStoreOf = { secureKeyValueStore(AgentMemory.STORE) },
        )
        memory.forEach { memoryStore.remember(it.first, it.second) }

        val configStore = AgentConfigStore(
            plainStoreOf = { secureKeyValueStore(it) },
            secureStoreOf = { suffix ->
                protectedSecureKeyValueStore(
                    AgentConfigStore.STORE_SECURE + suffix,
                    AgentConfigStore.STORE_LEGACY + suffix,
                )
            },
        )
        config?.let { configStore.save(it) }

        val agent = ServeAgent(
            session = ServeSession(),
            configStore = configStore,
            store = AgentSessionStore(),
            memory = memoryStore,
            toolTable = toolTable,
            transport = { transport },
            fetchModels = models,
            generateTitle = generateTitle,
            mfaSiteName = mfa,
            pingIntervalMillis = pingMillis,
        )
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { agentRoutes(agent) }
        try {
            block(Harness(http, token))
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            wipeAgentStores()
            dataRootOverride = null
            AccountContext.activeAccountId = null
        }
    }

    /** 清三份落盘（`:data` 的 `secureKeyValueStore` 按名字缓存实例，不清理就会写到上一个用例的临时根）。 */
    private fun wipeAgentStores() {
        for (base in listOf(
            AgentConfigStore.STORE,
            AgentConfigStore.STORE_SECURE,
            AgentConfigStore.STORE_LEGACY,
            AgentMemory.STORE,
        )) {
            wipeSecureStore(base + "default")
        }
        wipeSecureStore(AgentMemory.STORE)
    }

    /** 断言响应体里根本不出现那把夹具 Key（不是掩码）。 */
    private fun assertNoKey(body: String, where: String) {
        assert(!body.contains(AgentFixture.FAKE_API_KEY)) { "$where 的响应体里不该出现 API Key" }
    }

    /** 带令牌的夹具配置（`keyConfigured` 为 true 的那一档）。 */
    private fun configuredConfig() = AgentConfig(
        provider = AgentFixture.PROVIDER,
        apiKey = AgentFixture.FAKE_API_KEY,
        model = AgentFixture.MODEL,
        assistantName = AgentFixture.ASSISTANT_NAME,
        searchEngine = AgentFixture.SEARCH_ENGINE,
    )

    // ── 1 / 2. 读配置、写配置 ────────────────────────────────────────────────

    /** 没配过 key 的部署：逐字段如实（空型号→ 真发出去的是服务商默认那个、`keyConfigured:false`、`toolAllowlist` 就是夹具工具表里那一个、`honored` 空集），并且没令牌仍是 401。 */
    @Test
    fun configReportsHonestDefaultsAndTheGateStillApplies() {
        withAgentServer { h ->
            val noToken = h.get(AGENT_CONFIG_PATH, bearer = null)
            assertEquals(HttpStatusCode.Unauthorized.value, noToken.statusCode())
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(noToken))

            val res = h.get(AGENT_CONFIG_PATH)
            assertEquals(200, res.statusCode())
            val data = envelopeData(res)
            assertEquals(AgentConfig.PROVIDER_DEEPSEEK, data.optString("provider"))
            assertEquals("", data.optString("model"))
            assertEquals("deepseek-flash", data.optString("effectiveModel"))
            assertEquals("", data.optString("baseUrl"))
            assertEquals(AgentConfig.DEFAULT_ASSISTANT_NAME, data.optString("assistantName"))
            assertEquals(AgentConfig.SEARCH_AUTO, data.optString("searchEngine"))
            assertEquals(true, data.optBoolean("thinkingEnabled"))
            assertEquals(AgentConfig.REASONING_AUTO, data.optString("reasoningEffort"))
            assertEquals(true, data.optBoolean("showReasoning"))
            // 这一份部署没配 key ⇒ 唯一的「key 相关」投影就是这一个 bool
            assertEquals(false, data.optBoolean("keyConfigured"))
            // 白名单是从真下发给模型的工具定义里读出来的（不是另维护一份清单）
            assertEquals(listOf(AgentFixture.TOOL_NAME), data.arrOf("toolAllowlist").strings())
            assertEquals(emptyList(), data.arrOf("honored").strings())
            assertEquals(true, data.optBoolean("canEditKey"))
            assertNoKey(res.body(), "GET /api/agent/config")
        }
    }

    /** 写配置再读回来：字段对得上、`keyConfigured` 为 true，响应体里都没有 key。 */
    @Test
    fun writingConfigRoundTripsAndNeverProjectsTheKey() {
        withAgentServer { h ->
            val written = h.postJson(
                AGENT_CONFIG_PATH,
                """{"provider":"${AgentFixture.PROVIDER}","model":"${AgentFixture.MODEL}","baseUrl":"",""" +
                    """"assistantName":"${AgentFixture.ASSISTANT_NAME}","searchEngine":"${AgentFixture.SEARCH_ENGINE}",""" +
                    """"thinkingEnabled":false,"reasoningEffort":"high","showReasoning":false,""" +
                    """"apiKey":"${AgentFixture.FAKE_API_KEY}"}""",
            )
            assertEquals(200, written.statusCode())
            assertEquals(true, envelopeData(written).optBoolean("ok"))
            assertEquals("配置已保存", envelopeData(written).optString("message"))
            assertNoKey(written.body(), "POST /api/agent/config")

            val res = h.get(AGENT_CONFIG_PATH)
            val data = envelopeData(res)
            assertEquals(AgentFixture.MODEL, data.optString("model"))
            assertEquals(AgentFixture.MODEL, data.optString("effectiveModel"))
            assertEquals(AgentFixture.ASSISTANT_NAME, data.optString("assistantName"))
            assertEquals(false, data.optBoolean("thinkingEnabled"))
            assertEquals(AgentConfig.REASONING_HIGH, data.optString("reasoningEffort"))
            assertEquals(false, data.optBoolean("showReasoning"))
            assertEquals(true, data.optBoolean("keyConfigured"))
            // 键集合钉死：这一族**没有** apiKey / apiKeyMasked 那种格（掩码也不给）
            assertEquals(
                setOf(
                    "provider", "model", "effectiveModel", "baseUrl", "assistantName", "searchEngine",
                    "thinkingEnabled", "reasoningEffort", "showReasoning", "keyConfigured",
                    "toolAllowlist", "honored", "canEditKey",
                ),
                data.keys,
            )
            assertNoKey(res.body(), "GET /api/agent/config（写过之后）")

            // 夹具里明明有那把 key：它确实落在 `agent_config_secure<default>` 里 —— 上面那两条
            // 「不出现」的断言因此不是空的（写法见 `docs/api-contract.md` §5.2）。
            assertEquals(
                AgentFixture.FAKE_API_KEY,
                protectedSecureKeyValueStore(AgentConfigStore.STORE_SECURE + "default").getString("api_key"),
            )
        }
    }

    /** 认不出的取值一律 400 且不落盘；`apiKey` 不给 = 保留原值，给空串 = 清掉。 */
    @Test
    fun writingConfigRejectsUnknownValuesAndTreatsApiKeyAsOptional() {
        withAgentServer { h ->
            val rest = """"model":"","baseUrl":"","assistantName":"屁岱","thinkingEnabled":true,""" +
                """"showReasoning":true"""

            val badProvider = h.postJson(AGENT_CONFIG_PATH, """{"provider":"nope","searchEngine":"auto","reasoningEffort":"auto",$rest}""")
            assertEquals(400, badProvider.statusCode())
            assertTrue(envelopeMessage(badProvider)!!.contains("provider 认不出来"))

            val badEngine = h.postJson(AGENT_CONFIG_PATH, """{"provider":"deepseek","searchEngine":"bing","reasoningEffort":"auto",$rest}""")
            assertEquals(400, badEngine.statusCode())
            assertTrue(envelopeMessage(badEngine)!!.contains("searchEngine 认不出来"))

            val badEffort = h.postJson(AGENT_CONFIG_PATH, """{"provider":"deepseek","searchEngine":"auto","reasoningEffort":"最聪明",$rest}""")
            assertEquals(400, badEffort.statusCode())
            assertTrue(envelopeMessage(badEffort)!!.contains("reasoningEffort 认不出来"))

            val missing = h.postJson(
                AGENT_CONFIG_PATH,
                """{"provider":"deepseek","searchEngine":"auto","reasoningEffort":"auto","baseUrl":"",""" +
                    """"assistantName":"屁岱","thinkingEnabled":true,"showReasoning":true}""",
            )
            assertEquals(400, missing.statusCode())
            assertTrue(envelopeMessage(missing)!!.contains("model 必填"))

            // 四次 400 之后配置还是原样（读出来就是默认那份）
            assertEquals("", envelopeData(h.get(AGENT_CONFIG_PATH)).optString("model"))
            assertEquals(false, envelopeData(h.get(AGENT_CONFIG_PATH)).optBoolean("keyConfigured"))

            val ok = """{"provider":"deepseek","model":"m1","baseUrl":"","assistantName":"屁岱",""" +
                """"searchEngine":"auto","thinkingEnabled":true,"reasoningEffort":"auto","showReasoning":true"""
            // 先写一把 key，再写一次**不带 apiKey** 的 ⇒ 那把 key 留着
            h.postJson(AGENT_CONFIG_PATH, """$ok,"apiKey":"${AgentFixture.FAKE_API_KEY}"}""")
            assertEquals(true, envelopeData(h.get(AGENT_CONFIG_PATH)).optBoolean("keyConfigured"))
            h.postJson(AGENT_CONFIG_PATH, """{"provider":"deepseek","model":"m2","baseUrl":"","assistantName":"屁岱",""" +
                """"searchEngine":"auto","thinkingEnabled":true,"reasoningEffort":"auto","showReasoning":true}""")
            val kept = envelopeData(h.get(AGENT_CONFIG_PATH))
            assertEquals("m2", kept.optString("model"))
            assertEquals(true, kept.optBoolean("keyConfigured"))

            // 显式给空串 = 清掉那一把（「给了就按给的写」）
            h.postJson(AGENT_CONFIG_PATH, """$ok,"apiKey":""}""".replace(""""model":"m1"""", """"model":"m2""""))
            assertEquals(false, envelopeData(h.get(AGENT_CONFIG_PATH)).optBoolean("keyConfigured"))
        }
    }

    // ── 3. 拉模型列表 ────────────────────────────────────────────────────────

    /** 没配 key ⇒ 409 + 指路文案，而且一个请求都不打出去（夹具那一枪的计数为 0）。 */
    @Test
    fun modelsWithoutKeySaysSoExplicitlyAndCallsNothing() {
        var called = 0
        withAgentServer(models = { called++; AgentFixture.MODELS }) { h ->
            val res = h.postJson(AGENT_MODELS_PATH, """{"provider":"deepseek"}""")
            assertEquals(HttpStatusCode.Conflict.value, res.statusCode())
            assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(res))
            assertTrue(envelopeMessage(res)!!.contains("API Key"))
            assertEquals(0, called)
            assertNoKey(res.body(), "POST /api/agent/models（没配 key）")

            assertEquals(
                HttpStatusCode.Unauthorized.value,
                h.postJson(AGENT_MODELS_PATH, "{}", bearer = null).statusCode(),
            )
        }
    }

    /** 配了 key：型号列表照夹具回；服务商 / 网络失败 ⇒ 502 + 服务商原文。 */
    @Test
    fun modelsReturnsTheProvidersListAndMapsFailuresToBadGateway() {
        var seen: AgentConfig? = null
        withAgentServer(config = configuredConfig(), models = { seen = it; AgentFixture.MODELS }) { h ->
            val res = h.postJson(AGENT_MODELS_PATH, """{"provider":"openai","baseUrl":"https://example.invalid/v1"}""")
            assertEquals(200, res.statusCode())
            assertEquals(AgentFixture.MODELS, envelopeData(res).arrOf("models").strings())
            // 请求里那两格覆盖了存下来的配置（provider / baseUrl），key 仍用存下来的那一把
            assertEquals("openai", seen?.provider)
            assertEquals("https://example.invalid/v1", seen?.baseUrl)
            assertEquals(AgentFixture.FAKE_API_KEY, seen?.apiKey)
            assertNoKey(res.body(), "POST /api/agent/models")
        }
        withAgentServer(config = configuredConfig(), models = { throw RuntimeException("服务商说：额度不够") }) { h ->
            val res = h.postJson(AGENT_MODELS_PATH, """{"provider":"deepseek"}""")
            assertEquals(ApiErrors.UPSTREAM_FAILED, res.statusCode())
            assertEquals("服务商说：额度不够", envelopeMessage(res))
            assertNoKey(res.body(), "POST /api/agent/models（502）")
        }
    }

    // ── 4–8. 会话 ───────────────────────────────────────────────────────────

    /** 空表 + 闸门：没有会话时 `sessions: []`（不是 404，也不是 null）。 */
    @Test
    fun sessionsStartEmptyAndTheGateApplies() {
        withAgentServer { h ->
            assertEquals(
                HttpStatusCode.Unauthorized.value,
                h.get(AGENT_SESSIONS_PATH, bearer = null).statusCode(),
            )
            val res = h.get(AGENT_SESSIONS_PATH)
            assertEquals(200, res.statusCode())
            assertEquals(0, envelopeData(res).arrOf("sessions").size)
        }
    }

    /** 新建：不给请求体 = 默认标题；给了标题就按给的；两格时间戳是 epoch 毫秒且一开始相等。 */
    @Test
    fun creatingSessionsUsesTheDefaultTitleOrTheGivenOne() {
        withAgentServer { h ->
            val first = envelopeData(h.postJson(AGENT_SESSIONS_PATH, "")).getValue("session").jsonObject
            assertEquals(AgentFixture.DEFAULT_SESSION_TITLE, first.optString("title"))
            assertEquals(false, first.optBoolean("locked"))
            assertTrue(first.optString("id")!!.isNotBlank())
            assertEquals(first.optLong("createdAt"), first.optLong("updatedAt"))
            assertTrue(first.optLong("createdAt")!! > 0)

            val second = envelopeData(
                h.postJson(AGENT_SESSIONS_PATH, """{"title":"${AgentFixture.SESSION_TITLE}"}"""),
            ).getValue("session").jsonObject
            assertEquals(AgentFixture.SESSION_TITLE, second.optString("title"))

            val list = envelopeData(h.get(AGENT_SESSIONS_PATH)).arrOf("sessions").map { it.jsonObject }
            assertEquals(2, list.size)
            assertEquals(
                setOf(first.optString("id"), second.optString("id")),
                list.map { it.optString("id") }.toSet(),
            )
        }
    }

    /** 单条会话的内容：新会话是空历史；**id 不存在 ⇒ `200 + conversation:null`**（不是 404）。 */
    @Test
    fun conversationIsNullForAnUnknownIdButPresentForANewSession() {
        withAgentServer { h ->
            val id = envelopeData(h.postJson(AGENT_SESSIONS_PATH, "")).getValue("session").jsonObject.optString("id")!!
            val res = h.get(agentSessionPath(id))
            assertEquals(200, res.statusCode())
            val convo = envelopeData(res).getValue("conversation").jsonObject
            assertEquals(0, convo.arrOf("messages").size)
            assertEquals(JsonNull, convo.getValue("lastTotalTokens"))
            assertEquals(false, convo.optBoolean("contextExhausted"))
            // llmHistory 不投影（它含工具结果与画像原文）
            assertFalse(convo.containsKey("llmHistory"))

            val missing = h.get(agentSessionPath("没有这条会话"))
            assertEquals(200, missing.statusCode())
            assertEquals(JsonNull, envelopeData(missing).getValue("conversation"))
            assertEquals(HttpStatusCode.Unauthorized.value, h.get(agentSessionPath(id), bearer = null).statusCode())
        }
    }

    /** 改名锁标题；**对不存在的 id 是空操作**（列表一行都不动，仍 200）；空白标题 400。 */
    @Test
    fun renamingLocksTheTitleAndUnknownIdsAreANoop() {
        withAgentServer { h ->
            val id = envelopeData(h.postJson(AGENT_SESSIONS_PATH, "")).getValue("session").jsonObject.optString("id")!!
            val renamed = h.postJson(agentSessionRenamePath(id), """{"title":"${AgentFixture.SESSION_TITLE}"}""")
            assertEquals(200, renamed.statusCode())
            assertEquals(true, envelopeData(renamed).optBoolean("ok"))

            val before = envelopeData(h.get(AGENT_SESSIONS_PATH)).arrOf("sessions").map { it.jsonObject }
            val row = before.first { it.optString("id") == id }
            assertEquals(AgentFixture.SESSION_TITLE, row.optString("title"))
            // `lock` 缺省就是 true（与 store 的默认同）
            assertEquals(true, row.optBoolean("locked"))

            val noop = h.postJson(agentSessionRenamePath("没有这条会话"), """{"title":"谁也改不到"}""")
            assertEquals(200, noop.statusCode())
            val after = envelopeData(h.get(AGENT_SESSIONS_PATH)).arrOf("sessions").map { it.jsonObject }
            assertEquals(before.size, after.size)
            assertEquals(AgentFixture.SESSION_TITLE, after.first { it.optString("id") == id }.optString("title"))

            assertEquals(400, h.postJson(agentSessionRenamePath(id), """{"title":"  "}""").statusCode())
            assertEquals(HttpStatusCode.Unauthorized.value, h.postJson(agentSessionRenamePath(id), "{}", bearer = null).statusCode())
        }
    }

    /** 删除幂等（连删两次都 200），删掉之后那条会话的内容也就没有了。 */
    @Test
    fun deletingIsIdempotent() {
        withAgentServer { h ->
            val id = envelopeData(h.postJson(AGENT_SESSIONS_PATH, "")).getValue("session").jsonObject.optString("id")!!
            assertEquals(200, h.postJson(agentSessionDeletePath(id), "").statusCode())
            assertEquals(0, envelopeData(h.get(AGENT_SESSIONS_PATH)).arrOf("sessions").size)
            assertEquals(200, h.postJson(agentSessionDeletePath(id), "").statusCode())
            assertEquals(JsonNull, envelopeData(h.get(agentSessionPath(id))).getValue("conversation"))
            assertEquals(
                HttpStatusCode.Unauthorized.value,
                h.postJson(agentSessionDeletePath(id), "{}", bearer = null).statusCode(),
            )
        }
    }

    // ── 9–11. 记忆 ──────────────────────────────────────────────────────────

    /** 记忆列表用 `AgentMemory.all()` 的序，且**内部那个 `__order` 键不投影**。 */
    @Test
    fun memoryListsInWriteOrderWithoutTheInternalKey() {
        withAgentServer(memory = listOf("常去的校区" to "兴庆", "称呼" to "同学")) { h ->
            val res = h.get(AGENT_MEMORY_PATH)
            assertEquals(200, res.statusCode())
            assertEquals(
                listOf("常去的校区" to "兴庆", "称呼" to "同学"),
                envelopeData(res).arrOf("items").map {
                    it.jsonObject.optString("key")!! to it.jsonObject.optString("value")!!
                },
            )
            assertFalse(res.body().contains("__order"))
            assertEquals(HttpStatusCode.Unauthorized.value, h.get(AGENT_MEMORY_PATH, bearer = null).statusCode())
        }
    }

    /** 忘掉一条：`message` 是 store 原话、`ok` 说这条有没有；清空之后一条不剩。 */
    @Test
    fun forgettingAndClearingMemoryIsHonest() {
        withAgentServer(memory = listOf(AgentFixture.MEMORY_KEY to AgentFixture.MEMORY_VALUE)) { h ->
            val forgotten = h.postJson(AGENT_MEMORY_FORGET_PATH, """{"key":"${AgentFixture.MEMORY_KEY}"}""")
            assertEquals(200, forgotten.statusCode())
            assertEquals(true, envelopeData(forgotten).optBoolean("ok"))
            assertEquals("忘掉了「${AgentFixture.MEMORY_KEY}」。", envelopeData(forgotten).optString("message"))

            val again = h.postJson(AGENT_MEMORY_FORGET_PATH, """{"key":"${AgentFixture.MEMORY_KEY}"}""")
            assertEquals(false, envelopeData(again).optBoolean("ok"))
            assertEquals("没有记过「${AgentFixture.MEMORY_KEY}」。", envelopeData(again).optString("message"))

            assertEquals(400, h.postJson(AGENT_MEMORY_FORGET_PATH, """{"key":"   "}""").statusCode())

            h.postJson(AGENT_MEMORY_FORGET_PATH, """{"key":"${AgentFixture.MEMORY_KEY}"}""")
            val cleared = h.postJson(AGENT_MEMORY_CLEAR_PATH, "")
            assertEquals(200, cleared.statusCode())
            assertEquals(true, envelopeData(cleared).optBoolean("ok"))
            assertEquals(0, envelopeData(h.get(AGENT_MEMORY_PATH)).arrOf("items").size)
        }
    }

    // ── 12. 一轮对话（SSE）──────────────────────────────────────────────────

    /** 没配 key：409 + 指路文案，而且**流根本没开**（Content-Type 不是 event-stream）。 */
    @Test
    fun chatWithoutKeyIsExplicitAndDoesNotOpenTheStream() {
        withAgentServer { h ->
            val id = h.newSession()
            val res = h.chat(id, AgentFixture.USER_TEXT)
            assertEquals(HttpStatusCode.Conflict.value, res.statusCode())
            assertTrue(envelopeMessage(res)!!.contains("API Key"))
            assertFalse(res.headers().firstValue("Content-Type").orElse("").contains("event-stream"))
            assertNoKey(res.body(), "POST /api/agent/chat（没配 key）")
        }
    }

    /** 开门阶段的形状 / 目标错误：400 三档 + 404 会话不在 + 401 闸门。 */
    @Test
    fun chatOpeningStageErrors() {
        withAgentServer(config = configuredConfig()) { h ->
            val id = h.newSession()
            assertEquals(400, h.postJson(AGENT_CHAT_PATH, """{"text":"只有正文"}""").statusCode())
            assertEquals(400, h.postJson(AGENT_CHAT_PATH, """{"sessionId":"$id","text":"   "}""").statusCode())
            assertEquals(400, h.postJson(AGENT_CHAT_PATH, "这不是 JSON").statusCode())
            assertEquals(
                HttpStatusCode.Unauthorized.value,
                h.postJson(AGENT_CHAT_PATH, "{}", bearer = null).statusCode(),
            )

            val missing = h.postJson(AGENT_CHAT_PATH, """{"sessionId":"没有这条","text":"${AgentFixture.USER_TEXT}"}""")
            assertEquals(HttpStatusCode.NotFound.value, missing.statusCode())
            assertTrue(envelopeMessage(missing)!!.contains("没有这条"))
        }
    }

    /** 一轮正常对话：`delta`（逐段，不是一次给全）→ `usage` → `widget` → `nav` → `done`，且每一帧里都没有那把 key。 */
    @Test
    fun chatStreamsDeltasUsageAndDoneAndPersistsTheTurn() {
        withAgentServer(config = configuredConfig()) { h ->
            val id = h.newSession()
            val res = h.chat(id, AgentFixture.USER_TEXT)
            assertEquals(200, res.statusCode())
            assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))
            assertEquals("no-store", res.headers().firstValue("Cache-Control").orElse(""))
            assertNoKey(res.body(), "POST /api/agent/chat 的 SSE 每一帧")

            val frames = parseSse(res.body())
            assertEquals(listOf("delta", "delta", "usage", "widget", "nav", "done"), frames.map { it.name })
            assertEquals(
                AgentFixture.DELTAS,
                frames.filter { it.name == "delta" }.map { it.data!!.optString("text") },
            )
            assertEquals(AgentFixture.TOTAL_TOKENS, frames[2].data!!.optLong("totalTokens"))
            assertEquals(0, frames[3].data!!.arrOf("widgets").size)
            assertEquals(0, frames[4].data!!.arrOf("routes").size)
            val done = frames[5].data!!
            assertEquals(AgentFixture.REPLY_TEXT, done.optString("content"))
            assertEquals(id, done.optString("sessionId"))
            assertEquals(AgentFixture.USER_TEXT, done.optString("title"))
            assertEquals(false, done.optBoolean("contextExhausted"))
            assertEquals(AgentFixture.TOTAL_TOKENS, done.optLong("totalTokens"))

            val convo = envelopeData(h.get(agentSessionPath(id))).getValue("conversation").jsonObject
            val messages = convo.arrOf("messages").map { it.jsonObject }
            assertEquals(listOf("user", "assistant"), messages.map { it.optString("role") })
            assertEquals(AgentFixture.USER_TEXT, messages[0].optString("content"))
            assertEquals(AgentFixture.REPLY_TEXT, messages[1].optString("content"))
            assertEquals(AgentFixture.TOTAL_TOKENS, convo.optLong("lastTotalTokens"))

            val status = envelopeData(h.get(AGENT_STATUS_PATH))
            assertEquals(false, status.optBoolean("running"))
            assertEquals(JsonNull, status.getValue("sessionId"))
        }
    }

    /** 思维链与工具那一族：`reasoning` / `tool_call` / `tool_result` 三帧 + 工具行落盘成「已查询课表」 + `nav` 给出跳转建议——工具名用的是真名字 `get_schedule`，验的就是那两张共享表。 */
    @Test
    fun chatEmitsToolAndReasoningFramesWithTheSharedLabels() {
        val transport = FixtureTransport(
            listOf(
                Step(
                    reasoning = listOf(AgentFixture.REASONING_TEXT),
                    toolCalls = listOf(AgentToolCall("c1", "get_schedule", "{}")),
                ),
                Step(deltas = listOf(AgentFixture.REPLY_TEXT), totalTokens = AgentFixture.TOTAL_TOKENS),
            ),
        )
        withAgentServer(
            config = configuredConfig(),
            toolTable = { FixtureAgentTools(name = "get_schedule") },
            transport = transport,
        ) { h ->
            val id = h.newSession()
            val frames = parseSse(h.chat(id, AgentFixture.USER_TEXT).body())
            assertEquals(
                listOf("reasoning", "tool_call", "tool_result", "delta", "usage", "widget", "nav", "done"),
                frames.map { it.name },
            )
            assertEquals(AgentFixture.REASONING_TEXT, frames[0].data!!.optString("text"))
            assertEquals("get_schedule", frames[1].data!!.optString("name"))
            assertEquals("get_schedule", frames[2].data!!.optString("name"))
            assertEquals(true, frames[2].data!!.optBoolean("success"))
            // 成功时 error 是**明确的 null**（§4：null = 本端明确说没有）
            assertEquals(JsonNull, frames[2].data!!.getValue("error"))
            assertEquals(
                listOf("查看课表"),
                frames[6].data!!.arrOf("routes").map { it.jsonObject.optString("label") },
            )
            assertEquals(
                listOf("schedule"),
                frames[6].data!!.arrOf("routes").map { it.jsonObject.optString("route") },
            )

            // 工具行落盘成收尾文案（同一条共享的 `agentToolLabel` / `agentCompleteToolLabel`）
            val messages = envelopeData(h.get(agentSessionPath(id))).getValue("conversation").jsonObject
                .arrOf("messages").map { it.jsonObject }
            assertEquals(listOf("user", "tool_event", "assistant"), messages.map { it.optString("role") })
            assertEquals("已查询课表", messages[1].optString("content"))
            assertEquals(JsonNull, messages[1].getValue("toolError"))
            assertEquals(
                listOf(listOf("查看课表", "schedule")),
                messages[2].arrOf("nav").map { row -> row.jsonArray.map { it.jsonPrimitive.content } },
            )
        }
    }

    /** 工具失败：`tool_result` 的 `success:false` + 失败原因（原话），会话里的工具行也带 `toolError`。 */
    @Test
    fun chatReportsToolFailureInTheFrameAndOnDisk() {
        val transport = FixtureTransport(
            listOf(
                Step(toolCalls = listOf(AgentToolCall("c1", AgentFixture.TOOL_NAME, "{}"))),
                Step(deltas = listOf(AgentFixture.REPLY_TEXT)),
            ),
        )
        withAgentServer(
            config = configuredConfig(),
            toolTable = { FixtureAgentTools(fail = true) },
            transport = transport,
        ) { h ->
            val id = h.newSession()
            val frames = parseSse(h.chat(id, AgentFixture.USER_TEXT).body())
            val result = frames.first { it.name == "tool_result" }.data!!
            assertEquals(false, result.optBoolean("success"))
            assertEquals(AgentFixture.TOOL_ERROR, result.optString("error"))
            assertTrue(frames.any { it.name == "done" })

            val tool = envelopeData(h.get(agentSessionPath(id))).getValue("conversation").jsonObject
                .arrOf("messages").map { it.jsonObject }.first { it.optString("role") == "tool_event" }
            assertEquals(AgentFixture.TOOL_ERROR, tool.optString("toolError"))
            // 没成功的那一档文案**不**加「已」前缀（走的就是另一个分支）
            assertFalse(tool.optString("content")!!.startsWith("已"))
        }
    }

    /** 一轮失败：`error` 是唯一异常终止事件（**没有 done**），且那句错误也落了盘。 */
    @Test
    fun chatLlmFailureIsAnErrorEventAndAlsoPersisted() {
        withAgentServer(
            config = configuredConfig(),
            transport = FixtureTransport(listOf(Step(failWith = RuntimeException(AgentFixture.LLM_FAILURE)))),
        ) { h ->
            val id = h.newSession()
            val frames = parseSse(h.chat(id, AgentFixture.USER_TEXT).body())
            assertEquals(listOf("error"), frames.map { it.name })
            assertEquals("llm", frames[0].data!!.optString("kind"))
            assertEquals(AgentFixture.LLM_FAILURE, frames[0].data!!.optString("message"))

            val messages = envelopeData(h.get(agentSessionPath(id))).getValue("conversation").jsonObject
                .arrOf("messages").map { it.jsonObject }
            assertEquals("出错了：${AgentFixture.LLM_FAILURE}", messages.last().optString("content"))
        }
    }

    /** 登录失效走另一个 `kind`（浏览器照它显示那一整句，不显示「出错了：」）。 */
    @Test
    fun chatSessionExpiredUsesTheOtherKind() {
        withAgentServer(
            config = configuredConfig(),
            transport = FixtureTransport(listOf(Step(failWith = FixtureSessionExpired()))),
        ) { h ->
            val id = h.newSession()
            val frames = parseSse(h.chat(id, AgentFixture.USER_TEXT).body())
            assertEquals("session_expired", frames.single().data!!.optString("kind"))
            assertEquals(ServeAgent.SESSION_EXPIRED_TEXT, frames.single().data!!.optString("message"))

            val messages = envelopeData(h.get(agentSessionPath(id))).getValue("conversation").jsonObject
                .arrOf("messages").map { it.jsonObject }
            assertEquals(ServeAgent.SESSION_EXPIRED_TEXT, messages.last().optString("content"))
        }
    }

    /** 同时只允许一轮：第一轮还挂着时第二发拿 409（Android 是静默忽略，这里有意更清楚） */
    @Test
    fun chatIsBusyWhileATurnIsRunning() {
        val hold = CompletableDeferred<Unit>()
        withAgentServer(
            config = configuredConfig(),
            transport = FixtureTransport(listOf(Step(deltas = listOf(AgentFixture.REPLY_TEXT))), hold = hold),
        ) { h ->
            val first = h.newSession()
            val second = h.newSession()

            val running = CompletableFuture.supplyAsync { h.chat(first, AgentFixture.USER_TEXT) }
            awaitRunning(h)
            assertEquals(true, envelopeData(h.get(AGENT_STATUS_PATH)).optBoolean("running"))
            assertEquals(first, envelopeData(h.get(AGENT_STATUS_PATH)).optString("sessionId"))

            val busy = h.chat(second, "也问一句")
            assertEquals(HttpStatusCode.Conflict.value, busy.statusCode())
            assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(busy))
            assertTrue(envelopeMessage(busy)!!.contains("上一轮还在生成"))

            hold.complete(Unit)
            assertEquals(200, running.get(30, TimeUnit.SECONDS).statusCode())
            assertEquals(false, envelopeData(h.get(AGENT_STATUS_PATH)).optBoolean("running"))
        }
    }

    /** `POST /api/agent/stop` 幂等（没在跑也 200，`ok` 为 false） */
    @Test
    fun stoppingEndsTheStreamCleanlyAndMarksTheTurn() {
        val hold = CompletableDeferred<Unit>()
        withAgentServer(
            config = configuredConfig(),
            transport = FixtureTransport(listOf(Step(deltas = listOf(AgentFixture.REPLY_TEXT))), hold = hold),
        ) { h ->
            // 没有在跑的时候：200 + ok:false（不假装成功）
            val idle = h.postJson(AGENT_STOP_PATH, "")
            assertEquals(200, idle.statusCode())
            assertEquals(false, envelopeData(idle).optBoolean("ok"))
            assertEquals("现在没有在跑的生成", envelopeData(idle).optString("message"))

            val id = h.newSession()
            val running = CompletableFuture.supplyAsync { h.chat(id, AgentFixture.USER_TEXT) }
            awaitRunning(h)

            val stopped = h.postJson(AGENT_STOP_PATH, """{"sessionId":"$id"}""")
            assertEquals(200, stopped.statusCode())
            assertEquals(true, envelopeData(stopped).optBoolean("ok"))
            assertEquals("已请求停止", envelopeData(stopped).optString("message"))

            val res = running.get(30, TimeUnit.SECONDS)
            assertEquals(200, res.statusCode())
            // 停掉的那一轮：没有 done、也没有 error（这一轮没跑完，说它正常或出错都是假话）
            assertEquals(emptyList<String>(), parseSse(res.body()).map { it.name })

            val messages = envelopeData(h.get(agentSessionPath(id))).getValue("conversation").jsonObject
                .arrOf("messages").map { it.jsonObject }
            assertEquals(2, messages.size)
            assertEquals("user", messages[0].optString("role"))
            assertEquals("（已停止）", messages[1].optString("content"))
        }
    }

    /** 上下文耗尽：服务端判（800k）、`done` 带出来；**下一轮**才是 409 + 那句共享文案。 */
    @Test
    fun contextExhaustionIsDecidedByTheServerAndBlocksTheNextTurn() {
        withAgentServer(
            config = configuredConfig(),
            transport = FixtureTransport(
                listOf(Step(deltas = listOf(AgentFixture.REPLY_TEXT), totalTokens = AgentFixture.OVER_LIMIT_TOKENS)),
            ),
        ) { h ->
            val id = h.newSession()
            val done = parseSse(h.chat(id, "长对话").body()).single { it.name == "done" }.data!!
            assertEquals(true, done.optBoolean("contextExhausted"))
            assertEquals(AgentFixture.OVER_LIMIT_TOKENS, done.optLong("totalTokens"))
            assertEquals(
                true,
                envelopeData(h.get(agentSessionPath(id))).getValue("conversation").jsonObject
                    .optBoolean("contextExhausted"),
            )

            val next = h.chat(id, "再来一句")
            assertEquals(HttpStatusCode.Conflict.value, next.statusCode())
            assertEquals(AGENT_CONTEXT_EXHAUSTED_MESSAGE, envelopeMessage(next))
        }
    }

    /** MFA 帧：**只有站名**（键集合钉死成 `pending` / `siteName`，没有手机号那一格）；保活是注释帧。 */
    @Test
    fun mfaFrameCarriesOnlyTheSiteNameAndPingIsAComment() {
        val hold = CompletableDeferred<Unit>()
        withAgentServer(
            config = configuredConfig(),
            transport = FixtureTransport(listOf(Step(deltas = listOf(AgentFixture.REPLY_TEXT))), hold = hold),
            mfa = { AgentFixture.MFA_SITE_NAME },
            pingMillis = 30,
        ) { h ->
            val id = h.newSession()
            val running = CompletableFuture.supplyAsync { h.chat(id, AgentFixture.USER_TEXT) }
            awaitRunning(h)
            // 等一个 MFA 轮询周期（500ms）+ 若干次 30ms 的保活
            Thread.sleep(700)
            hold.complete(Unit)
            val body = running.get(30, TimeUnit.SECONDS).body()

            val mfa = parseSse(body).first { it.name == "mfa" }
            assertEquals(setOf("pending", "siteName"), mfa.data!!.keys)
            assertEquals(true, mfa.data!!.optBoolean("pending"))
            assertEquals(AgentFixture.MFA_SITE_NAME, mfa.data!!.optString("siteName"))
            assertTrue(body.contains(": ping"), "保活那一帧必须是注释行")
            assertFalse(body.contains(AgentFixture.MFA_PHONE_NUMBER))
        }
    }

    // ── 13 / 14. 停止与状态 ─────────────────────────────────────────────────

    /** 十四个端点的**闸门**那一档：一个都不许漏在令牌外面（没带令牌一律 401）。 */
    @Test
    fun everyEndpointSitsBehindTheTokenGate() {
        withAgentServer { h ->
            // 不含空格的假 id：`java.net.http` 的 `URI.create` 不接受空格（非 ASCII 可以）
            val id = "任意id"
            val posts = listOf(
                AGENT_CONFIG_PATH, AGENT_MODELS_PATH, AGENT_SESSIONS_PATH,
                agentSessionRenamePath(id), agentSessionDeletePath(id),
                AGENT_MEMORY_FORGET_PATH, AGENT_MEMORY_CLEAR_PATH, AGENT_CHAT_PATH, AGENT_STOP_PATH,
            )
            for (path in posts) {
                val res = h.postJson(path, "{}", bearer = null)
                assertEquals(HttpStatusCode.Unauthorized.value, res.statusCode(), "POST $path 没被闸门罩住")
                assertNoKey(res.body(), "POST $path（无令牌）")
            }
            for (path in listOf(
                AGENT_CONFIG_PATH, AGENT_SESSIONS_PATH, AGENT_MEMORY_PATH, AGENT_STATUS_PATH, agentSessionPath(id),
            )) {
                assertEquals(
                    HttpStatusCode.Unauthorized.value,
                    h.get(path, bearer = null).statusCode(),
                    "GET $path 没被闸门罩住",
                )
            }
        }
    }

    /** 没人问时 status 为空；未实现路径走 404 兜底。 */
    @Test
    fun statusDefaultsToIdleAndUnknownAgentPathsAre404() {
        withAgentServer { h ->
            val status = envelopeData(h.get(AGENT_STATUS_PATH))
            assertEquals(false, status.optBoolean("running"))
            assertEquals(JsonNull, status.getValue("sessionId"))
            assertEquals(HttpStatusCode.NotFound.value, h.get("$AGENT_PATH/没有这个端点").statusCode())
            assertEquals(HttpStatusCode.NotFound.value, h.postJson("$AGENT_PATH/没有这个端点", "{}").statusCode())
        }
    }


    // ── 生产装配（`forServe`）本身 ───────────────────────────────────────────

    /** `ServeAgent.forServe` 的装配真的可用，且落的就是那两份文件。 */
    @Test
    fun forServeWiringUsesTheProductionStoresAndTheDefaultAccountSuffix() {
        FakeCampusProxy.installFakeUpstreams()
        val root = Files.createTempDirectory("xjtu-serve-agent-wiring").toFile()
        dataRootOverride = root
        wipeAgentStores()
        AccountContext.activeAccountId = null
        try {
            val agent = ServeAgent.forServe(ServeSession())
            // S-c 已落地 ⇒ 真给模型的工具表就是白名单那九件（与 `/api/agent/config`
            // 的 `toolAllowlist` 同一份定义；这里故意**手写**那九个名字，不从常量抄，免得变成同义反复）
            assertEquals(
                listOf(
                    "get_calendar", "get_schedule", "get_exam_schedule", "get_empty_rooms",
                    "search_yellow_page", "preference", "find_faculty", "app_guide", "calculate",
                ),
                agent.toolAllowlist(AgentConfig.SEARCH_AUTO),
            )
            assertEquals(false, agent.keyConfigured())

            agent.saveConfig(AgentConfig(apiKey = AgentFixture.FAKE_API_KEY, model = AgentFixture.MODEL))
            assertEquals(true, agent.keyConfigured())
            // ⚠️ 匿名时后缀是 `default`（无前导下划线）⇒ 文件名是 `agent_config_securedefault.properties`。
            assertTrue(
                Files.exists(root.toPath().resolve("agent_config_securedefault.properties")),
                "临时根里应该是 [${root.list()?.joinToString()}]",
            )
            assertEquals(
                AgentFixture.FAKE_API_KEY,
                protectedSecureKeyValueStore(AgentConfigStore.STORE_SECURE + "default").getString("api_key"),
            )
            // 会话与记忆也落同一个根（同一套账号后缀）
            val session = agent.createSession(null)
            assertTrue(session.id.isNotBlank())
            assertTrue(Files.isDirectory(root.toPath().resolve("agent_sessionsdefault")))
            agent.memoryItems()   // 懒读：一次都不写也不报错
        } finally {
            wipeAgentStores()
            dataRootOverride = null
            AccountContext.activeAccountId = null
        }
    }
    // ── SSE 帧：纯函数（十一类一次钉全）────────────────────────────────────

    /** 每一类事件都编成**一帧一行**的 SSE（`event:` + `data:` + 空行），`null` 是明确的 `null`。 */
    @Test
    fun everyEventTypeFramesToOneLine() {
        assertEquals("event: delta\ndata: {\"text\":\"a\\nb\"}\n\n", agentSseFrame(AgentStreamEvent.Delta("a\nb")))
        assertEquals("event: reasoning\ndata: {\"text\":\"想\"}\n\n", agentSseFrame(AgentStreamEvent.Reasoning("想")))
        assertEquals(
            "event: tool_call\ndata: {\"name\":\"get_schedule\"}\n\n",
            agentSseFrame(AgentStreamEvent.ToolCall("get_schedule")),
        )
        assertEquals(
            "event: tool_result\ndata: {\"name\":\"t\",\"success\":true,\"error\":null}\n\n",
            agentSseFrame(AgentStreamEvent.ToolResult("t", true, null)),
        )
        assertEquals(
            "event: tool_result\ndata: {\"name\":\"t\",\"success\":false,\"error\":\"炸了\"}\n\n",
            agentSseFrame(AgentStreamEvent.ToolResult("t", false, "炸了")),
        )
        assertEquals("event: usage\ndata: {\"totalTokens\":1234}\n\n", agentSseFrame(AgentStreamEvent.Usage(1234)))
        assertEquals(
            "event: mfa\ndata: {\"pending\":true,\"siteName\":\"图书馆\"}\n\n",
            agentSseFrame(AgentStreamEvent.Mfa(true, "图书馆")),
        )
        assertEquals(
            "event: mfa\ndata: {\"pending\":false,\"siteName\":null}\n\n",
            agentSseFrame(AgentStreamEvent.Mfa(false, null)),
        )
        assertEquals(
            "event: widget\ndata: {\"widgets\":[{\"type\":\"exam\",\"json\":\"{}\"}]}\n\n",
            agentSseFrame(AgentStreamEvent.Widgets(listOf(StoredWidget("exam", "{}")))),
        )
        assertEquals(
            "event: nav\ndata: {\"routes\":[{\"label\":\"查看课表\",\"route\":\"schedule\"}]}\n\n",
            agentSseFrame(AgentStreamEvent.Nav(listOf("查看课表" to "schedule"))),
        )
        assertEquals(
            "event: done\ndata: {\"content\":\"回\",\"sessionId\":\"s1\",\"title\":\"标题\"," +
                "\"contextExhausted\":false,\"totalTokens\":null}\n\n",
            agentSseFrame(AgentStreamEvent.Done("回", "s1", "标题", false, null)),
        )
        assertEquals(
            "event: error\ndata: {\"kind\":\"llm\",\"message\":\"炸了\"}\n\n",
            agentSseFrame(AgentStreamEvent.Error("llm", "炸了")),
        )
        assertEquals(": ping\n\n", agentSseFrame(AgentStreamEvent.Ping))
    }

    // ── 夹具与小工具 ────────────────────────────────────────────────────────

    /** 真 HTTP 客户端 + 本进程那一枚令牌（[bearer] 显式给 null 就是「不带令牌」那一发）。 */
    private class Harness(private val http: EndpointHarness, private val token: String) {

        fun get(path: String, bearer: String? = token): HttpResponse<String> = http.get(path, bearer)

        fun postJson(path: String, body: String, bearer: String? = token): HttpResponse<String> =
            http.postJson(path, body, bearer)

        /** 新建一条会话并取它的 id（几乎每个用例都要开头）。 */
        fun newSession(): String = envelopeData(postJson(AGENT_SESSIONS_PATH, ""))
            .getValue("session").jsonObject.optString("id")!!

        /** 开一轮对话。 */
        fun chat(sessionId: String, text: String): HttpResponse<String> =
            postJson(AGENT_CHAT_PATH, """{"sessionId":"$sessionId","text":"$text"}""")
    }

    /** 等到 `ServeAgent` 真的进「有一轮在跑」那一档（最长 10 秒，超时就是真挂了）。 */
    private fun awaitRunning(h: Harness) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            if (envelopeData(h.get(AGENT_STATUS_PATH)).optBoolean("running") == true) return
            Thread.sleep(20)
        }
        error("10 秒内那一轮都没开始跑")
    }

    /** 一行一个 SSE 帧：[name] + 解析好的 `data`（注释帧的 `data` 是 null）。 */
    private class Frame(val name: String, val data: JsonObject?)

    private fun parseSse(body: String): List<Frame> = body.split("\n\n")
        .filter { it.isNotBlank() }
        .map { block ->
            val lines = block.trim('\n').lines()
            if (lines.first().startsWith(":")) return@map Frame(lines.first().trim(), null)
            val name = lines.first { it.startsWith("event:") }.removePrefix("event:").trim()
            val data = lines.first { it.startsWith("data:") }.removePrefix("data:").trim()
            Frame(name, Json.parseToJsonElement(data).jsonObject)
        }

    private fun kotlinx.serialization.json.JsonArray.strings(): List<String> =
        map { (it as JsonPrimitive).content }

    /** 夹具的剧本里的一枪：增量 / 思维链 / 工具调用 / token / 这一枪直接失败。 */
    private class Step(
        val deltas: List<String> = emptyList(),
        val reasoning: List<String> = emptyList(),
        val toolCalls: List<AgentToolCall> = emptyList(),
        val totalTokens: Long? = null,
        val failWith: Throwable? = null,
    )

    /** 夹具的「发一次请求」：按剧本一枪一枪地回（第 N 次 `stream` 用第 N 个 [Step]）。 */
    private class FixtureTransport(
        private val steps: List<Step>,
        private val hold: CompletableDeferred<Unit>? = null,
    ) : AgentTransport {

        private var index = 0

        override suspend fun stream(
            config: AgentConfig,
            reqBody: JsonObject,
            onDelta: suspend (String) -> Unit,
            onReasoningDelta: suspend (String) -> Unit,
        ): AgentStreamResult {
            val step = steps.getOrNull(index++) ?: error("夹具的剧本用完了（第 $index 枪）")
            hold?.await()
            step.failWith?.let { throw it }
            step.reasoning.forEach { onReasoningDelta(it) }
            step.deltas.forEach { onDelta(it) }
            return AgentStreamResult(
                content = step.deltas.joinToString(""),
                reasoningContent = step.reasoning.joinToString(""),
                toolCalls = step.toolCalls,
                finishReason = "stop",
                totalTokens = step.totalTokens,
            )
        }
    }

    /** 夹具的工具表：只定义 [name] 这一个工具，执行结果 / 失败由参数决定。 */
    private class FixtureAgentTools(
        private val name: String = AgentFixture.TOOL_NAME,
        private val fail: Boolean = false,
    ) : AgentTools {

        /** 纯本地工具 ⇒ 不限流。 */
        override fun rateLimitKeyOf(toolName: String): String? = null

        override val toolDefinitions: String =
            """[{"type":"function","function":{"name":"$name","description":"夹具工具","parameters":{"type":"object","properties":{}}}}]"""

        override suspend fun execute(name: String, argsJson: String): String {
            if (fail) throw IllegalStateException(AgentFixture.TOOL_ERROR)
            return AgentFixture.TOOL_RESULT
        }
    }

    /** `:app` 那个 `AuthExpiredException` 的同义夹具（`:core` 的标记接口）。 */
    private class FixtureSessionExpired : RuntimeException(AgentFixture.LLM_FAILURE), SessionExpiredFailure
}
