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
import com.xjtu.toolbox.agent.CompositeAgentTools
import com.xjtu.toolbox.agent.DeepSeekWebTools
import com.xjtu.toolbox.agent.SseAgentTransport
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.platform.SecretProtector
import com.xjtu.toolbox.platform.protectedSecureKeyValueStore
import com.xjtu.toolbox.platform.secretProtectorForPlatform
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.yellowpage.YellowPageApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/** 桌面端的屁岱宿主面（见 [AgentHost] 与 [AgentCapability]）。 */
internal class DesktopAgentHost(
    private val auth: DesktopAuth,
    /** 密钥那一半的凭据保护器。 */
    private val protector: SecretProtector? = secretProtectorForPlatform(),
) : AgentHost {

    /**
     * 这一端真会照着办的那几档。
     *
     * 只有一档：**从文件对话框挑图**（[AgentCapability.IMAGE_INPUT]）—— 图片附件那一套（落盘、
     * 缩略图、`data:` URI、删除）在这一端都接上了，见 [DesktopAgentImages]。
     *
     * 其余四档不列，理由都是"这一端没有那件事"，不是"还没轮到"：
     * - `ALARM` / `APP_SETTINGS` / `SCHEDULE_EVENT_WRITE`：桌面上没有系统闹钟、没有那几项设置的白名单、
     *   自建日程表也还没接（见 `DesktopScheduleHost` 的 honored 与 gapNotes）。
     * - `FILE_SAVE`（存进下载）：屁岱能存的那两种文件来自学辅资料站与思源附件，这两路取数桌面都没有
     *   ⇒ 列进来就是"col 里有、点了没反应"（同一句纪律见 `AgentCapability.FILE_SAVE`）。
     */
    override val honored: Set<AgentCapability> = setOf(AgentCapability.IMAGE_INPUT)

    /**
     * 桌面这一端的屁岱比手机朴素在哪（见 [AgentHost.gapNote]）。
     *
     * 两件事分开写清，因为它们都表现为"手机上有的东西这里没有"，但一个是工具面（能查/能办什么），
     * 一个是形象面（长什么样、谁在说话）：
     * - 工具：本端的工具表与 serve **同一档**（黄页、教师检索、偏好、功能引导、算一算、校历、课表、
     *   考试、空教室；另加校园地图，配了联网搜索再添一份搜索）—— 成绩、一卡通、考勤、通知、图书馆、
     *   教材、加餐券、思源学堂、全校课表、体测、学辅资料都还没有取数，设闹钟 / 改设置 / 写自建日程
     *   同样没有宿主。课表与考试两件读的是本机缓存（`ScheduleCache`）而不是现拉：桌面没有教务取数
     *   通路，缓存空时会直说“去日程页刷一次”。
     * - 形象：`hero` / `appearanceSection` 都没交（见 [AgentScreen] 的槽位）⇒ 问候是纯文字的一句，
     *   形象、角色、皮肤在手机上调过的那套不会同步过来。
     */
    override val gapNote: String =
        "这一端能查的比手机少：成绩、一卡通、考勤、通知、图书馆、教材、加餐券、思源学堂、全校课表、" +
            "体测、学辅资料都还没接（现在会查的是黄页、教师、校历、课表、考试、空教室、校园地图、算一算、" +
            "功能引导，以及记住的偏好；配了联网搜索还能查网）⇒ 问这些它会说查不到，不是它偷懒。" +
            "课表与考试读的是本机缓存（桌面不直连教务），缓存空时它会让你先去「日程」页刷一次；" +
            "空教室只有 CDN 的逐节空闲表，没有智慧教室那种「此刻几个人在」。" +
            "设闹钟、改设置、写进自建日程这几件在这里同样做不到。" +
            "另外，这一端的屁岱是简单模样：问候只有一句文字，没有形象、角色和皮肤（那三样在手机上调，" +
            "调过的那套不会同步过来）。"

    override val io: CoroutineDispatcher = Dispatchers.IO

    // ── 端装配 ────────────────────────────────────────────────────────────────

    override fun tools(config: AgentConfig): AgentTools = CompositeAgentTools(
        primary = DesktopAgentTools(
            honored = honored,
            // 每次调用重建一份：`AgentMemory` 自己按当前账号后缀解析存储（切账号即切文件）。
            memory = agentMemory(),
            yellowPage = YellowPageApi(yellowPageClient),
            faculty = FacultyApi(),
            // 课表族那三件读的缓存：与日程屏同一个账号命名空间（切账号即切文件）。
            cache = DesktopScheduleHost(auth).cache(accountId),
        ),
        extras = listOfNotNull(
            com.xjtu.toolbox.campusmap.CampusMapAgentTools(),
            config.webSearchKey.takeIf { it.isNotEmpty() }?.let { DeepSeekWebTools(it) },
        ),
    )

    override val supportsMcp = true
    override fun mcpTools(config: AgentConfig, confirm: suspend (String, String, String) -> Boolean): AgentTools =
        com.xjtu.toolbox.agent.McpAgentTools(config, confirm)
    override suspend fun testMcp(config: AgentConfig): String =
        com.xjtu.toolbox.agent.McpAgentTools(config, confirm = { _, _, _ -> false }).testConnection()

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

    // ── 附件图（见 [AgentCapability.IMAGE_INPUT] 与 [DesktopAgentImages]）────────

    /** 这张附件图还在不在：拷进 `~/.cache/xjtu-toolbox/agent_images<suffix>` 的那一份还在地上。 */
    override fun imageFileExists(path: String): Boolean = File(path).isFile

    /** 读一张附件图的字节（气泡里那颗缩略图解码用）。 */
    override fun readImageBytes(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

    /** 带图消息要的 `data:image/…;base64,…`（形状见 `AgentVision.userContent`）。 */
    override fun imageDataUri(path: String): String? = DesktopAgentImages.dataUri(path)

    /** 会话被删 / 附件被撤回：把落盘的那几张也删掉（文件本来就不在时什么都不做）。 */
    override fun deleteImageFiles(paths: List<String>) {
        paths.forEach { runCatching { File(it).delete() } }
    }
}
