package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AgentCapability
import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentConfigStore
import com.xjtu.toolbox.agent.AgentHost
import com.xjtu.toolbox.agent.AgentMemory
import com.xjtu.toolbox.agent.AgentMfaRequest
import com.xjtu.toolbox.agent.AgentModelFetcher
import com.xjtu.toolbox.agent.AgentSessionPersistence
import com.xjtu.toolbox.agent.AgentSessionStore
import com.xjtu.toolbox.agent.AgentTitleGen
import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.AgentTransport
import com.xjtu.toolbox.agent.SseAgentTransport
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.platform.SecretProtector
import com.xjtu.toolbox.platform.protectedSecureKeyValueStore
import com.xjtu.toolbox.platform.secretProtectorForPlatform
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.yellowpage.YellowPageApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 桌面端的屁岱宿主面（见 [AgentHost] 与 [AgentCapability]）。 */
internal class DesktopAgentHost(
    private val auth: DesktopAuth,
    /** 密钥那一半的凭据保护器。 */
    private val protector: SecretProtector? = secretProtectorForPlatform(),
) : AgentHost {

    override val honored: Set<AgentCapability> = emptySet()

    override val io: CoroutineDispatcher = Dispatchers.IO

    // ── 端装配 ────────────────────────────────────────────────────────────────

    override fun tools(searchEngine: String): AgentTools = DesktopAgentTools(
        honored = honored,
        // 每次调用重建一份：`AgentMemory` 自己按当前账号后缀解析存储（切账号即切文件）。
        memory = agentMemory(),
        yellowPage = YellowPageApi(yellowPageClient),
        faculty = FacultyApi(),
    )

    override fun transport(): AgentTransport = SseAgentTransport()

    // ── 登录态那几件 ──────────────────────────────────────────────────────────

    override val accountId: String get() = AccountContext.activeAccountId.orEmpty()

    /** 打招呼用的名字：空串（只问候，不写名字）。 */
    override val greetingName: String = ""

    /** 登录态指纹：与 `:app` 的 `isLoggedIn|activeUsername` 同义（这一端用户名就是激活账号）。 */
    override val loginKey: String get() = "${auth.loggedIn}|${AccountContext.activeAccountId.orEmpty()}"

    /** 正在等用户回短信验证码的那次询问（桌面有同一条链：`DesktopMfaDialog` 就是它弹的窗）。 */
    override val mfaRequests: Flow<AgentMfaRequest?>?
        get() = auth.sessionManager.activeMfaRequest
            .map { req -> req?.let { AgentMfaRequest(it.siteName) } }

    // ── 落盘与取数（`:data` 的那四个类，构造后按账号动态解析路径）────────────

    private val sessionStore = AgentSessionStore()

    /** 非敏感那半的存储：必须传 `secureKeyValueStore`（0600 的 `agent_config*<账号后缀>.properties`）。 */
    private val configStore = AgentConfigStore(
        plainStoreOf = { secureKeyValueStore(it) },
        secureStoreOf = { suffix ->
            protectedSecureKeyValueStore(
                AgentConfigStore.STORE_SECURE + suffix,
                AgentConfigStore.STORE_LEGACY + suffix,
                protector,
            )
        },
    )

    private fun agentMemory(): AgentMemory =
        AgentMemory(storeOf = { secureKeyValueStore(AgentMemory.STORE + AccountContext.safeSuffix()) })

    override fun sessions(): AgentSessionPersistence = sessionStore

    override fun loadConfig(): AgentConfig = configStore.load()

    override fun saveConfig(config: AgentConfig) = configStore.save(config)

    override suspend fun fetchModels(config: AgentConfig): List<String> = AgentModelFetcher.fetch(config)

    override suspend fun generateTitle(config: AgentConfig, userMsg: String, assistantMsg: String): String? =
        AgentTitleGen.generate(config, userMsg, assistantMsg)
}
