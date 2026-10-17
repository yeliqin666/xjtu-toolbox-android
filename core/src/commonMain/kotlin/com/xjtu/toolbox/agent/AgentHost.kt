package com.xjtu.toolbox.agent

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

/** 屁岱（Agent 域）的宿主槽位 —— 「这一端有什么」，而不是「这一端是不是 Android」。 */
interface AgentHost {
    /** 「谁去跑阻塞 IO」由端上说：`:core` 的 commonMain 没有 `Dispatchers.IO`（wasm 目标更没有）。 */
    val io: CoroutineDispatcher get() = Dispatchers.Default


    /** 这一端**真会照着办**的那些档位（见 [AgentCapability]）。 */
    val honored: Set<AgentCapability> get() = emptySet()

    // ── 1. 端装配 ─────────────────────────────────────────────────────────────

    /** 这一端的工具层：`:app` 交 `AgentToolRegistry`（取数走 `:data` 的 `*Api`，那三档端能力回指 [honored] 与下面那几个成员）。 */
    fun tools(searchEngine: String): AgentTools = AgentTools.None

    /** 这一端的「发一次请求」：`:app` 与桌面都交 `:data` 的 `SseAgentTransport`。 */
    fun transport(): AgentTransport = AgentTransport.None

    // ── 1b. 登录态那几件 ──────────────────────────────────

    /** 当前账号 id（`:app` = `AppLoginState.accountId`）。 */
    val accountId: String get() = ""

    /** 打招呼用的名字（`:app` = 缓存昵称，退一站通姓名；取不到就是空串）。 */
    val greetingName: String get() = ""

    /** 当前登录态的一句话指纹：登录态没变就不再联网补画像。 */
    val loginKey: String get() = ""

    /** 正在等用户回短信验证码的那次询问（`:app` = `SessionManager.activeMfaRequest`）。 */
    val mfaRequests: Flow<AgentMfaRequest?>? get() = null

    // ── 2. 只有某些端才有的动作（工具表里那几档）────────────────────────────

    /** `set_alarm`：把一次闹钟交给系统（`:app` = `AlarmClock.ACTION_SET_ALARM` 的 Intent，打开系统闹钟界面等用户确认）。 */
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

    /** 首屏那几张卡片的本地数据快照（`:app` = `DataCache` + 校园卡偏好 + 聊天事实，全在 `:app`）。 */
    fun welcomeFacts(now: LocalDateTime): WelcomeFacts = WelcomeFacts()

    /** 回复里那些远程图（markdown 内联图）的取图引擎。 */
    val images: AgentImageLoader get() = AgentImageLoader.None

    /** 这个附件文件还在不在（文件被清掉/换了账号时，气泡里不再显示缩略图）。 */
    fun imageFileExists(path: String): Boolean = false

    /** 读一张本地附件的字节（缩略图解码用）。 */
    fun readImageBytes(path: String): ByteArray? = null

    /** 把一张本地附件读成 `data:image/jpeg;base64,…`（带图消息要的就是这个形状）。 */
    fun imageDataUri(path: String): String? = null

    /** 删掉这些附件文件（会话被删、附件被撤回时调用）。 */
    fun deleteImageFiles(paths: List<String>): Unit = Unit

    /** 读工具落下的平面图缓存（图书馆座位卡的底图，`:app` = `cacheDir/library_plan/<name>`）。 */
    suspend fun readPlanImage(name: String): DecodedPlanImage? = null

    /** 把仲英学辅资料站的一个文件存进「下载」（资料卡那一行的动作）。 */
    suspend fun saveZyxfFile(fileId: Int): String? = null

    companion object {

        /** 什么都没接的宿主：所有档位都不画、不执行。 */
        val None: AgentHost = object : AgentHost {}
    }
}

/** 一张解好的本地图 + 它的原始宽度。 */
/** MFA 询问里屁岱用得上的那一点：正在等哪个站点的短信验证码（`:app` = `SessionManager.MfaRequest`）。 */
class AgentMfaRequest(val siteName: String)

/** 一张解好的本地图 + 它的原始宽度。 */
class DecodedPlanImage(val image: ImageBitmap, val originalWidth: Int)

/** 回复里远程图的取图引擎（`:app` = OkHttp + `BitmapFactory` 那一段，见那个模块的 `AndroidAgentImages`）。 */
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

/** 系统相册挑图的注入点（`:app` 用 `rememberLauncherForActivityResult` 实现，见那个模块的 `rememberAndroidAgentImagePicker`）。 */
fun interface AgentImagePicker {

    fun pick(room: Int, onPicked: (List<String>) -> Unit)

    companion object {
        /** 没有相册的端：点了什么都不会发生（而且那一档本来就不画）。 */
        val None: AgentImagePicker = AgentImagePicker { _, _ -> }
    }
}

/** 设置项取值那几句人话的解析（`true/1/on/yes/开/开启` …），`app_setting` 的写入方共用。 */
fun agentParseBoolSetting(value: String): Boolean? {
    return when (value.trim().lowercase()) {
        "true", "1", "on", "yes", "开", "开启" -> true
        "false", "0", "off", "no", "关", "关闭" -> false
        else -> null
    }
}
