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
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.yellowpage.YellowPageApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 桌面端的屁岱宿主面 —— **如实声明这一端有什么**（见 `:core` 的 [AgentHost] 与 [AgentCapability]）。
 *
 * | 槽位 | 桌面这一端 |
 * |---|---|
 * | [honored] | **空集** —— 五档一个都不列（理由见下） |
 * | [tools] | [DesktopAgentTools]：`:core` 的纯逻辑三件（算术 / 功能引导 / 偏好）+ `:data`/`:core` 的两件免登录取数（黄页 / 教师检索） |
 * | [transport] | `:data` 的 [SseAgentTransport]（与 Android **同一份实现**：okhttp SSE、边收边回调） |
 * | [sessions] / [loadConfig] / [saveConfig] | `:data` 的 [AgentSessionStore] / [AgentConfigStore] —— 真落盘（见下） |
 * | [fetchModels] / [generateTitle] | `:data` 的 [AgentModelFetcher] / [AgentTitleGen]（各一次真的模型请求） |
 * | [mfaRequests] | `auth.sessionManager.activeMfaRequest`（桌面有同一条短信二验链：`DesktopMfaDialog`） |
 * | [images] / 附件四件 / [AgentHost.readPlanImage] / [AgentHost.saveZyxfFile] | **不接**（见下） |
 * | [AgentHost.welcomeFacts] | 不接：桌面没有 `DataCache` 那份本地快照 ⇒ 首屏那几张卡片直接不出，不放空壳 |
 *
 * ## 三份落盘是真的（不是内存）
 *
 * 三份都存在本端的数据根目录里（`dataFilesDir()` = `~/.local/share/xjtu-toolbox`，测试与证据运行
 * 用 `dataRootOverride` 指到临时目录），文件名与 Android 那份**逐字相同**，键名也同一批：
 *
 *  - 配置：[AgentConfigStore] 的非敏感那半走 `secureKeyValueStore`（`:core` 的 jvm `keyValueStore`
 *    是**进程内内存**，设置项存进去重开就没了 —— 与 `DesktopSettingsHost` 同一条理由）；
 *    密钥那一半本来就只能落在密文存储上（Windows 上再套一层 DPAPI，见 `:data` 的 `SecretProtector`）；
 *  - 会话：[AgentSessionStore]（`agent_sessions<账号后缀>/index.json` + 每会话一个 `convo_*.json`）；
 *  - 记忆：[AgentMemory] 落在 0600 的 `agent_memory<账号后缀>.properties`（不分账号的旧版文件由它自己迁移）。
 *
 * ## 为什么 [honored] 是空集
 *
 * 那一档的语义是「这一端**真会照着办**」：闹钟（`AlarmClock` 的 Intent）、
 * 应用设置读写（`CredentialStore` 那 11 项白名单）、自建日程写入（`:app` 的 Room 表）——
 * 桌面**三样都没有对应实现**：
 *
 *  - 闹钟：桌面没有系统闹钟的 Intent，也没有等价的「交给系统闹钟界面」这条路（不画 `set_alarm`）；
 *  - 应用设置：`:app` 那 11 项里桌面真有的只有 `dark_mode` / `dynamic_color` / `home_theme`
 *    （`DesktopSettingsHost.honoredRows` 就这三行），其余八项（启动页 / 网络模式 / 更新通道 /
 *    界面风格 / 常用功能 / 账号类型 / 场馆验证码 / 预览版更新）这一端**根本没有那个开关**
 *    —— 一个只列三项、其余八项答 `bad_value` 的 `app_setting` 就是「时灵时不灵」，
 *    所以整档不画（要的是先把这八项接上，见 `DesktopSettingsHost` 的 TODO 1~5）；
 *  - 自建日程写入：桌面的 `KeyValueCustomCourseStore` 能写（课表屏的自建日程就是它），
 *    但 `add_schedule_event` 的本体（学期/开学日期/周次换算 + 与课程和已有日程的冲突判定）
 *    现在还在 `:app` 的 `AgentToolRegistry` 里，没搬进共享层 ⇒ 这一档也先不画（见 TODO 1）。
 *
 * 屏上那两档（相册挑图 [AgentCapability.IMAGE_INPUT]、存进下载 [AgentCapability.FILE_SAVE]）同理：
 * 桌面没有 `ImageDecoder` 那套「压好再落盘」的图片编码缝（`:core` 只有解码），也没有仲英学辅
 * 资料站那条路由 ⇒ 输入栏那颗回形针与资料卡那几行下载**都不画**。
 *
 * ## TODO（要真点亮某一件，缺的是哪一环）
 *
 *  1. **自建日程写入**（`SCHEDULE_EVENT_WRITE`）：把 `add_schedule_event` 的本体从 `:app` 搬进
 *     `:core`/`:data`（判据用的是 `ScheduleCache` + `CustomCourseConflicts` + `CustomCourseStore`，
 *     三样都已经是共享层的东西），这一端再补一格写入口 —— 那时它才配进 [honored]；
 *  2. **课表 / 成绩 / 空教室 / 校园卡那几档取数**：它们的 `*Api` 与屏这一端都有（见
 *     `DESKTOP_SUPPORTED_ROUTES` 里那几条路由），但工具结果那段文案现在写在 `:app` 的
 *     `AgentToolRegistry` 里（每档 30~150 行）。按「屏怎么搬就怎么搬」那条路把取数 + 格式化
 *     一起搬进共享层，一次点一档（`get_schedule` / `get_grades` / `get_empty_rooms` 优先）；
 *  3. **远程图的取图引擎**（[AgentHost.images]）：`:core` 已经有解码缝（`decodeImage`，
 *     教材阅读器在用），缺的是「https 取字节 + 内存 LRU」这一段 —— 接上之前，回复里的
 *     markdown 图片会退成一行可点的链接（`:core` 本来就有的降级路，不是画不出来）；
 *  4. **附件四件**（[AgentHost.imageFileExists] / `readImageBytes` / `imageDataUri` /
 *     `deleteImageFiles`）：现在是默认值 ⇒ 老会话里的图片路径一律算「文件不在了」。
 *     它们本身只是 `java.io.File` + `java.util.Base64`，但**这一端没有挑图入口**（TODO 5）
 *     ⇒ 先不写没有调用方的代码；
 *  5. **相册挑图**：桌面这一端要有的是「文件选择器（Swing 的 `JFileChooser`）+ 压好落盘」，
 *     而后半步需要一个**图片编码**缝（`:core` 现在只有解码），那要动四个目标的 actual
 *     —— 不在一屏点亮这一轮里。
 *
 * @param auth 会话 + 凭据（MFA 那一路要它；取数工具本身都不需要校园会话）
 */
internal class DesktopAgentHost(private val auth: DesktopAuth) : AgentHost {

    override val honored: Set<AgentCapability> = emptySet()

    /** 与搬迁前同一个池（`:app` 的屁岱一直是 `Dispatchers.IO`）。 */
    override val io: CoroutineDispatcher = Dispatchers.IO

    // ── 端装配 ────────────────────────────────────────────────────────────────

    override fun tools(searchEngine: String): AgentTools = DesktopAgentTools(
        honored = honored,
        // 每次调用重建一份：`AgentMemory` 自己按当前账号后缀解析存储（切账号即切文件）。
        memory = agentMemory(),
        // 黄页与教师检索**都免登录**（同一条纪律：桌面端不发任何带凭据的请求去这两条公开接口）。
        // 黄页收一条 Ktor 客户端（`Main.kt` 的 `yellowPageClient`，与黄页那一屏同一份），
        // 教师检索自己建 okhttp 客户端（与教师检索那一屏同一份实现）。
        yellowPage = YellowPageApi(yellowPageClient),
        faculty = FacultyApi(),
    )

    override fun transport(): AgentTransport = SseAgentTransport()

    // ── 登录态那几件 ──────────────────────────────────────────────────────────

    override val accountId: String get() = AccountContext.activeAccountId.orEmpty()

    /**
     * 打招呼用的名字：**空串**（只问候，不写名字）。理由与首页那一条相同 —— 一网通办那份昵称
     * 属 `:app` 的学工系统缓存，桌面这一端没有（账号列表里显示的也是学号，见 `DesktopAccounts`）。
     */
    override val greetingName: String = ""

    /** 登录态指纹：与 `:app` 的 `isLoggedIn|activeUsername` 同义（这一端用户名就是激活账号）。 */
    override val loginKey: String get() = "${auth.loggedIn}|${AccountContext.activeAccountId.orEmpty()}"

    /** 正在等用户回短信验证码的那次询问（桌面有同一条链：`DesktopMfaDialog` 就是它弹的窗）。 */
    override val mfaRequests: Flow<AgentMfaRequest?>?
        get() = auth.sessionManager.activeMfaRequest
            .map { req -> req?.let { AgentMfaRequest(it.siteName) } }

    // ── 落盘与取数（`:data` 的那四个类，构造后按账号动态解析路径）────────────

    private val sessionStore = AgentSessionStore()

    /**
     * 非敏感那半的存储：**必须传 `secureKeyValueStore`**（0600 的 `agent_config*<账号后缀>.properties`）。
     * `:core` 的 `keyValueStore` 在 jvm 上是进程内内存实现，用它等于「服务商/模型/助手名一关窗就丢」。
     */
    private val configStore = AgentConfigStore(plainStoreOf = { secureKeyValueStore(it) })

    private fun agentMemory(): AgentMemory =
        AgentMemory(storeOf = { secureKeyValueStore(AgentMemory.STORE + AccountContext.safeSuffix()) })

    override fun sessions(): AgentSessionPersistence = sessionStore

    override fun loadConfig(): AgentConfig = configStore.load()

    override fun saveConfig(config: AgentConfig) = configStore.save(config)

    override suspend fun fetchModels(config: AgentConfig): List<String> = AgentModelFetcher.fetch(config)

    override suspend fun generateTitle(config: AgentConfig, userMsg: String, assistantMsg: String): String? =
        AgentTitleGen.generate(config, userMsg, assistantMsg)
}
