package com.xjtu.toolbox.agent

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

/**
 * 屁岱（Agent 域）的**宿主槽位** —— 「这一端有什么」，而不是「这一端是不是 Android」。
 *
 * ## 为什么这个域要一个这么宽的槽位
 *
 * 屁岱是唯一一个「屏 + 调度循环 + 工具层 + 四份落盘」都在 `:core`、而**端上资源**又到处都是的域：
 * 闹钟（`AlarmClock` 的 Intent）、应用设置（`CredentialStore`）、相册挑图、下载、
 * 会话与配置落盘（`:data` 的两个 Store）、模型列表与自动标题（`:data` 的两个 object）、
 * 每屏一次的网络传输（`:data` 的 `SseAgentTransport`）、首屏那几张卡片的本地数据快照。
 * 这些**一件都进不了 `:core`**（要么是 Android API，要么是 `:data` 的类 —— 而 `:data` 依赖 `:core`，
 * 方向反过来是环）。所以它们在这里各占一格，各带一个**如实的默认值**：默认值是「这一端没有」
 * 的形状，不是「假装有」。
 *
 * ## 与别的域的同一条纪律
 *
 * [honored] 是「这一端**真会照着办**」的那几档（见 [AgentCapability]）—— 与
 * `SettingsHost.honoredRows` / `ScheduleHost.honored` / `HomeHost.honoredCards` 同一条：
 * **没有的档一个字都不画**（画了就得能用）。两个消费点共用这一份清单：工具表
 * （[agentToolDefinitions] 少画那几行）与屏（输入栏那颗回形针、资料卡那几行下载）。
 *
 * ## 分三类，别混在一起看
 *
 * 1. **端装配**（[tools] / [transport]）：`:core` 的调度循环（[AgentRunner]）要的两件事。
 *    `:app` 交的是「`*Api` 取数 + Android 能力」的工具注册表与 okhttp 的 SSE；
 * 2. **只有某些端才有的动作**（[setAlarm] / [readAppSettings] / [writeAppSetting]）：工具表里那三档；
 * 3. **屏与卡片要碰端上资源的几件事**（落盘、取图、挑图、首屏快照）。
 *
 * 本体的搬迁口径：`:app` 的实现与搬迁前**逐字相同**，只是从某个类的私有方法变成这里的一个 `override`
 * （见 `:app` 的 `AndroidAgentHost`）。
 */
interface AgentHost {
    /**
     * 「谁去跑阻塞 IO」由端上说：`:core` 的 commonMain 没有 `Dispatchers.IO`（wasm 目标更没有）。
     * `:app` 与桌面传 `Dispatchers.IO`（与搬迁前同一个池），其余端用 [Dispatchers.Default]。
     */
    val io: CoroutineDispatcher get() = Dispatchers.Default


    /** 这一端**真会照着办**的那些档位（见 [AgentCapability]）。 */
    val honored: Set<AgentCapability> get() = emptySet()

    // ── 1. 端装配 ─────────────────────────────────────────────────────────────

    /**
     * 这一端的工具层：`:app` 交 `AgentToolRegistry`（取数走 `:data` 的 `*Api`，
     * 那三档端能力回指 [honored] 与下面那几个成员）。
     *
     * [searchEngine] 是用户设置里的联网搜索源（工具注册表构造时要用），每次变化由 ViewModel 重建。
     */
    fun tools(searchEngine: String): AgentTools = AgentTools.None

    /** 这一端的「发一次请求」：`:app` 与桌面都交 `:data` 的 `SseAgentTransport`。 */
    fun transport(): AgentTransport = AgentTransport.None

    // ── 1b. 登录态那几件 ──────────────────────────────────

    /**
     * 当前账号 id（`:app` = `AppLoginState.accountId`）。
     * 切账号要重读配置、重建工具表（工具表里的 DataCache 是构造时绑账号的）。
     */
    val accountId: String get() = ""

    /**
     * 打招呼用的名字（`:app` = 缓存昵称，退一站通姓名；取不到就是空串）。
     * 读的仍是宿主那份可观察状态，所以昵称到了会自己重组。
     */
    val greetingName: String get() = ""

    /**
     * 当前登录态的一句话指纹（`:app` = `isLoggedIn|activeUsername`）：`AgentViewModel` 用它判
     * 「登录态没变就不再联网补画像」—— 没登录时每条消息都去撞一次网络，只会白等一轮超时。
     */
    val loginKey: String get() = ""

    /**
     * 正在等用户回短信验证码的那次询问（`:app` = `SessionManager.activeMfaRequest`）。
     * 对话列表据此插一条「需要登录验证」的气泡，让用户知道**不是 AI 在慢**，是在校验。
     * null = 这一端没有 MFA 这回事（默认）。
     */
    val mfaRequests: Flow<AgentMfaRequest?>? get() = null

    // ── 2. 只有某些端才有的动作（工具表里那几档）────────────────────────────

    /**
     * `set_alarm`：把一次闹钟交给系统（`:app` = `AlarmClock.ACTION_SET_ALARM` 的 Intent，
     * 打开系统闹钟界面等用户确认）。参数判据在 [agentAlarmPlan]，这里只管发出去。
     */
    fun setAlarm(hour: Int?, minute: Int?, message: String?, days: String?): String =
        ToolReply.notFound("tool", AgentCapability.ALARM.tool ?: "")

    /** `app_setting` 不给 value：列出全部设置项的当前值与可选值。 */
    fun readAppSettings(): String =
        ToolReply.notFound("tool", AgentCapability.APP_SETTINGS.tool ?: "")

    /** `app_setting` 给了 key 和 value：改这一项（只认白名单，其余回 `bad_value`）。 */
    fun writeAppSetting(key: String, value: String): String =
        ToolReply.notFound("tool", AgentCapability.APP_SETTINGS.tool ?: "")

    // ── 3. 落盘 / 取数 / 端上资源 ────────────────────────────────────────────

    /** 多会话落盘（`:data` 的 `AgentSessionStore` 实现它）。 */
    fun sessions(): AgentSessionPersistence = AgentSessionPersistence.None

    /** 读配置（`:data` 的 `AgentConfigStore`）。没接的端回默认配置（= 没配 key 的状态）。 */
    fun loadConfig(): AgentConfig = AgentConfig()

    /** 存配置（即改即存，见配置面板）。 */
    fun saveConfig(config: AgentConfig) {}

    /** 拉模型列表（`:data` 的 `AgentModelFetcher`）。 */
    suspend fun fetchModels(config: AgentConfig): List<String> = emptyList()

    /** 把首轮对话总结成标题（`:data` 的 `AgentTitleGen`）。返回 null = 不自动改名。 */
    suspend fun generateTitle(config: AgentConfig, userMsg: String, assistantMsg: String): String? = null

    /**
     * 首屏那几张卡片的本地数据快照（`:app` = `DataCache` + 校园卡偏好 + 聊天事实，全在 `:app`）。
     * 默认是空快照：**读不到的卡片直接不出**，不放空壳（见 [AgentWelcome]）。
     */
    fun welcomeFacts(now: LocalDateTime): WelcomeFacts = WelcomeFacts()

    /**
     * 回复里那些远程图（markdown 内联图）的取图引擎。
     * 默认 [AgentImageLoader.None]：加载不出来就退成一行可点的链接（屏上本来就有这条退路）。
     */
    val images: AgentImageLoader get() = AgentImageLoader.None

    /** 这个附件文件还在不在（文件被清掉/换了账号时，气泡里不再显示缩略图）。 */
    fun imageFileExists(path: String): Boolean = false

    /** 读一张本地附件的字节（缩略图解码用）。 */
    fun readImageBytes(path: String): ByteArray? = null

    /** 把一张本地附件读成 `data:image/jpeg;base64,…`（带图消息要的就是这个形状）。 */
    fun imageDataUri(path: String): String? = null

    /** 删掉这些附件文件（会话被删、附件被撤回时调用）。 */
    fun deleteImageFiles(paths: List<String>): Unit = Unit

    /**
     * 读工具落下的平面图缓存（图书馆座位卡的底图，`:app` = `cacheDir/library_plan/<name>`）。
     * 返回 null = 这张图取不到了，座位卡退成一行「点开图书馆页查看」。
     */
    suspend fun readPlanImage(name: String): DecodedPlanImage? = null

    /**
     * 把仲英学辅资料站的一个文件存进「下载」（资料卡那一行的动作）。返回给用户看的文案；
     * null = 这一端存不了。没接的端由 [AgentCapability.FILE_SAVE] 把那一行的动作**整行不画**。
     */
    suspend fun saveZyxfFile(fileId: Int): String? = null

    companion object {

        /**
         * 什么都没接的宿主：所有档位都不画、不执行。
         * `:core` 自己的测试与将来「只接了取数的端」用它。
         */
        val None: AgentHost = object : AgentHost {}
    }
}

/**
 * 一张解好的本地图 + 它的**原始宽度**。座位坐标是相对原图算的，而底图是降采样解码的，
 * 所以尺寸不能从解出来的位图上取（见 `platform/ImageDecode` 的 KDoc）。
 */
/**
 * MFA 询问里屁岱用得上的那一点：正在等哪个站点的短信验证码（`:app` = `SessionManager.MfaRequest`）。
 * 会话里其余那几个字段（`siteKey` / `mfaContext` / `submit` / `cancel`）是弹窗那份 UI 的事，
 * 屁岱只借 `siteName` 拼一句「等「X」短信验证码…」。
 */
class AgentMfaRequest(val siteName: String)

/**
 * 一张解好的本地图 + 它的**原始宽度**。座位坐标是相对原图算的，而底图是降采样解码的，
 * 所以尺寸不能从解出来的位图上取（见 `platform/ImageDecode` 的 KDoc）。
 */
class DecodedPlanImage(val image: ImageBitmap, val originalWidth: Int)

/**
 * 回复里远程图的取图引擎（`:app` = OkHttp + `BitmapFactory` 那一段，见那个模块的 `AndroidAgentImages`）。
 * 与 [AgentHost.images] 同一个来由：`:core` 编不出 okhttp（它只有 jvm/android 变体），
 * 而「这张图加载失败过」这件事要跨重组记住，所以 [cached] / [hasFailed] 是同步的。
 */
interface AgentImageLoader {

    /** 已经在内存里的那张图（重组时先看这里，避免反复读盘/重下）。 */
    fun cached(url: String): ImageBitmap?

    /** 这张图此前加载失败过：屏上直接画「加载失败，点开看原图」，不再重试。 */
    fun hasFailed(url: String): Boolean

    /** 拉一张图并解码；失败返回 null（调用方据此标 [hasFailed]）。 */
    suspend fun load(url: String): ImageBitmap?

    companion object {
        /** 没有取图引擎的端：一切都「加载不出来」，于是退成一行可点的链接。 */
        val None: AgentImageLoader = object : AgentImageLoader {
            override fun cached(url: String): ImageBitmap? = null
            override fun hasFailed(url: String): Boolean = false
            override suspend fun load(url: String): ImageBitmap? = null
        }
    }
}

/**
 * 系统相册挑图的**注入点**（`:app` 用 `rememberLauncherForActivityResult` 实现，
 * 见那个模块的 `rememberAndroidAgentImagePicker`）。
 *
 * 为什么是参数而不是 [AgentHost] 的成员：挑图要 Composable 的启动器（`ActivityResultLauncher`
 * 只能从 `rememberLauncherForActivityResult` 拿），而 [AgentHost] 是给 ViewModel 用的普通对象。
 * 默认值 = 「这一端没有相册」，而 [AgentCapability.IMAGE_INPUT] 会把那颗回形针**整颗不画**。
 *
 * @param room 这一轮还能再带几张（调用点按 `上限 - 已选` 算好）。
 * @param onPicked 选中的图片**已经压好落盘**，这里给的是一串本地路径（见 [AgentVision]）。
 */
fun interface AgentImagePicker {

    fun pick(room: Int, onPicked: (List<String>) -> Unit)

    companion object {
        /** 没有相册的端：点了什么都不会发生（而且那一档本来就不画）。 */
        val None: AgentImagePicker = AgentImagePicker { _, _ -> }
    }
}

/**
 * 设置项取值那几句人话的解析（`true/1/on/yes/开/开启` …），`app_setting` 的写入方共用。
 *
 * 判据逐字来自 `:app` 的 `AgentToolRegistry.parseBoolSetting`：认不出来返回 null，
 * 由调用方回 `bad_value`。
 */
fun agentParseBoolSetting(value: String): Boolean? {
    return when (value.trim().lowercase()) {
        "true", "1", "on", "yes", "开", "开启" -> true
        "false", "0", "off", "no", "关", "关闭" -> false
        else -> null
    }
}
