package com.xjtu.toolbox.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.AlarmClock
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.util.toJavaTime
import com.xjtu.toolbox.util.toKx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import java.io.File
import java.util.UUID
import com.xjtu.toolbox.network.HttpClients

/** Android 侧的屁岱宿主面：`:core` 的 [AgentHost] 收的那些「这一端有什么」在这边各自是谁。 */
internal class AndroidAgentHost(
    private val context: Context,
    private val loginState: AppLoginState,
) : AgentHost {

    override val honored: Set<AgentCapability> = AgentCapability.entries.toSet()

    /** 阻塞 IO 走 `Dispatchers.IO`（`:core` 的默认值是 `Dispatchers.Default`）。 */
    override val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    // ── 端装配 ────────────────────────────────────────────────────────────────

    override fun tools(searchEngine: String): AgentTools = com.xjtu.toolbox.agent.CompositeAgentTools(
        primary = AgentToolRegistry(
            loginState = loginState,
            // DataCache 构造时绑账号：换账号必须重建，否则工具读写的是旧账号缓存（见 AgentViewModel）。
            dataCache = DataCache(context, AccountContext.activeAccountId),
            context = context,
            host = this,
            defaultSearchEngine = searchEngine,
        ),
        // 各 feature 自带的工具（只实现 :shared:model 的 AgentTools），在入口这里并进来
        extras = listOf(com.xjtu.toolbox.inbox.SelfTodoAgentTools),
    )

    override fun transport(): AgentTransport = SseAgentTransport()

    // ── 登录态 ────────────────────────────────────────────────────────────────

    override val accountId: String get() = loginState.accountId

    /** 打招呼用缓存昵称，退一站通姓名。 */
    override val greetingName: String
        get() = loginState.cachedNickname.orEmpty()
            .ifBlank { loginState.ywtbUserInfo?.userName.orEmpty() }

    override val loginKey: String get() = "${loginState.isLoggedIn}|${loginState.activeUsername}"

    override val mfaRequests: Flow<AgentMfaRequest?>?
        get() = loginState.sessionManager?.activeMfaRequest
            ?.map { req -> req?.let { AgentMfaRequest(it.siteName) } }

    // ── 工具表里那几档端能力 ──────────────────────────────────────────────────

    override fun setAlarm(hour: Int?, minute: Int?, message: String?, days: String?): String =
        when (val plan = agentAlarmPlan(hour, minute, days)) {
            is AgentAlarmPlan.Invalid -> plan.reply
            is AgentAlarmPlan.Ok -> try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, plan.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, plan.minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message?.takeIf { it.isNotBlank() } ?: "岱宗盒子提醒")
                    if (plan.repeatOnIsoDays.isNotEmpty()) {
                        putExtra(AlarmClock.EXTRA_DAYS, ArrayList(plan.repeatOnIsoDays.map { calendarDayOf(it) }))
                    }
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolReply.handedOff("alarm", "time: ${"%02d:%02d".format(plan.hour, plan.minute)}" +
                    (message?.takeIf { it.isNotBlank() }?.let { "; label: $it" } ?: ""))
            } catch (e: Exception) {
                ToolReply.failed("set_alarm", e.message ?: "no_alarm_app")
            }
        }

    /** `java.util.Calendar` 的星期号：周日在第 1 位（与 [agentAlarmPlan] 的 ISO 号只差一个换算）。 */
    private fun calendarDayOf(isoDay: Int): Int = when (isoDay) {
        1 -> java.util.Calendar.MONDAY
        2 -> java.util.Calendar.TUESDAY
        3 -> java.util.Calendar.WEDNESDAY
        4 -> java.util.Calendar.THURSDAY
        5 -> java.util.Calendar.FRIDAY
        6 -> java.util.Calendar.SATURDAY
        else -> java.util.Calendar.SUNDAY
    }

    /** `app_setting` 只认这几项：改名/新增都要两边一起动（工具表的 enum 与这里）。 */
    private val writableSettingKeys = listOf(
        "dark_mode", "dynamic_color", "home_theme", "nav_bar_style", "show_quick_actions",
        "default_tab", "network_mode", "account_type", "venue_auto_solve_captcha",
        "update_channel", "receive_preview_updates",
    )

    override fun readAppSettings(): String {
        val cs = CredentialStore(context)
        return buildString {
            append("当前应用设置：\n")
            append("• dark_mode（深色模式）：${cs.darkMode}　可选 system/light/dark\n")
            append("• dynamic_color（跟随系统取色）：${cs.dynamicColor}　可选 true/false\n")
            append("• home_theme（首页主题）：${cs.homeTheme}　可选 card/icon\n")
            append("• nav_bar_style（界面风格）：${cs.navBarStyle}　可选 floating（玻璃，默认）/classic（经典，不透明、更省电）\n")
            append("• show_quick_actions（首页常用功能）：${cs.showQuickActions}　可选 true/false\n")
            append("• default_tab（启动页）：${cs.defaultTab}　可选 HOME/COURSES/TOOLS/PROFILE\n")
            append("• network_mode（网络模式）：${cs.networkMode}　可选 auto/direct/vpn\n")
            append("• account_type（账号类型）：${cs.accountType.key}　可选 undergraduate/postgraduate\n")
            append("• venue_auto_solve_captcha（场馆验证码自动识别）：${cs.venueAutoSolveCaptchaEnabled}　可选 true/false\n")
            append("• update_channel（更新通道）：${cs.updateChannel}（${com.xjtu.toolbox.update.AppUpdater.channelLabel(cs.updateChannel)}）　可选 ${com.xjtu.toolbox.update.AppUpdater.channelKeys.joinToString("/")}\n")
            append("• receive_preview_updates（接收预览版更新）：${cs.receivePreviewUpdates}　可选 true/false")
        }
    }

    override fun writeAppSetting(key: String, value: String): String {
        val cs = CredentialStore(context)
        return when (key.trim()) {
            "dark_mode" -> {
                val v = value.trim().lowercase()
                if (v !in listOf("system", "light", "dark")) ToolReply.badValue("dark_mode", "system/light/dark")
                else {
                    cs.darkMode = v
                    "已将深色模式设为 $v（已即时生效）。"
                }
            }
            "dynamic_color" -> {
                val b = agentParseBoolSetting(value) ?: return ToolReply.badValue("dynamic_color", "true/false")
                cs.dynamicColor = b
                "已${if (b) "开启" else "关闭"}跟随系统取色（已即时生效）。"
            }
            "home_theme" -> {
                val v = when (value.trim().lowercase()) {
                    "card", "卡片", "卡片主题" -> CredentialStore.THEME_CARD
                    "icon", "图标", "图标主题" -> CredentialStore.THEME_ICON
                    else -> return ToolReply.badValue("home_theme", "card/icon")
                }
                cs.homeTheme = v
                "已将首页主题设为 $v（已即时生效）。"
            }
            "nav_bar_style" -> {
                val v = when (value.trim().lowercase()) {
                    "floating", "悬浮", "悬浮胶囊", "玻璃", "液态玻璃" -> CredentialStore.NAV_STYLE_FLOATING
                    "classic", "经典", "经典底栏", "不透明" -> CredentialStore.NAV_STYLE_CLASSIC
                    else -> return ToolReply.badValue("nav_bar_style", "floating/classic")
                }
                cs.navBarStyle = v
                "已将界面风格设为 $v（已即时生效）。"
            }
            "show_quick_actions" -> {
                val b = agentParseBoolSetting(value) ?: return ToolReply.badValue("show_quick_actions", "true/false")
                cs.showQuickActions = b
                "已${if (b) "显示" else "隐藏"}首页常用功能（已即时生效）。"
            }
            "default_tab" -> {
                val v = value.trim().uppercase()
                if (v !in listOf("HOME", "COURSES", "TOOLS", "PROFILE")) ToolReply.badValue("default_tab", "HOME/COURSES/TOOLS/PROFILE")
                else { cs.defaultTab = v; "已将启动页设为 $v（下次冷启动生效）。" }
            }
            "network_mode" -> {
                val v = when (value.trim().lowercase()) {
                    "auto", "自动", "自动检测" -> CredentialStore.NETWORK_AUTO
                    "direct", "直连", "强制直连" -> CredentialStore.NETWORK_DIRECT
                    "vpn", "webvpn", "强制 webvpn" -> CredentialStore.NETWORK_VPN
                    else -> return ToolReply.badValue("network_mode", "auto/direct/vpn")
                }
                cs.networkMode = v
                "已将网络模式设为 $v。"
            }
            "account_type" -> {
                val type = when (value.trim().lowercase()) {
                    "undergraduate", "本科", "本科生" -> AccountType.UNDERGRADUATE
                    "postgraduate", "研究生" -> AccountType.POSTGRADUATE
                    else -> return ToolReply.badValue("account_type", "undergraduate/postgraduate")
                }
                cs.accountType = type
                "已将账号类型设为 ${type.displayName}。"
            }
            "venue_auto_solve_captcha" -> {
                val b = agentParseBoolSetting(value) ?: return ToolReply.badValue("venue_auto_solve_captcha", "true/false")
                cs.venueAutoSolveCaptchaEnabled = b
                "已${if (b) "开启" else "关闭"}场馆验证码自动识别。"
            }
            "update_channel" -> {
                val raw = value.trim().lowercase()
                val accepted = com.xjtu.toolbox.update.AppUpdater.channelKeys + listOf("stable", "beta")
                if (raw !in accepted) {
                    ToolReply.badValue("update_channel", com.xjtu.toolbox.update.AppUpdater.channelKeys.joinToString("/"))
                } else {
                    val v = com.xjtu.toolbox.update.AppUpdater.normalizeChannel(raw)
                    cs.updateChannel = v
                    "已将更新通道设为 $v（${com.xjtu.toolbox.update.AppUpdater.channelLabel(v)}）。"
                }
            }
            "receive_preview_updates" -> {
                val b = agentParseBoolSetting(value) ?: return ToolReply.badValue("receive_preview_updates", "true/false")
                cs.receivePreviewUpdates = b
                "已将「接收预览版更新」设为 $b。"
            }
            else -> ToolReply.badValue("key", writableSettingKeys.joinToString("/"))
        }
    }

    // ── 落盘与取数（`:data` 的那四个类，构造后按账号动态解析路径）────────────

    private val sessionStore = AgentSessionStore()
    private val configStore = AgentConfigStore()

    override fun sessions(): AgentSessionPersistence = sessionStore

    override fun loadConfig(): AgentConfig = configStore.load()

    override fun saveConfig(config: AgentConfig) = configStore.save(config)

    override suspend fun fetchModels(config: AgentConfig): List<String> = AgentModelFetcher.fetch(config)

    override suspend fun generateTitle(config: AgentConfig, userMsg: String, assistantMsg: String): String? =
        AgentTitleGen.generate(config, userMsg, assistantMsg)

    // ── 首屏快照 ──────────────────────────────────────────────────────────────

    override fun welcomeFacts(now: LocalDateTime): WelcomeFacts =
        AndroidWelcomeFactsLoader.load(context, now.toJavaTime())

    // ── 图片：远程图引擎 + 附件那几件 ─────────────────────────────────────────

    override val images: AgentImageLoader = AndroidAgentImages

    override fun imageFileExists(path: String): Boolean = File(path).isFile

    override fun readImageBytes(path: String): ByteArray? =
        runCatching { File(path).readBytes() }.getOrNull()

    override fun imageDataUri(path: String): String? = runCatching {
        val f = File(path)
        if (!f.isFile) return null
        "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    override fun deleteImageFiles(paths: List<String>) {
        paths.forEach { runCatching { File(it).delete() } }
    }

    override suspend fun readPlanImage(name: String): DecodedPlanImage? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(File(context.cacheDir, "library_plan"), name.replace('/', '_'))
            if (!file.isFile) return@runCatching null
            val bytes = file.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = if (bounds.outWidth > 1600) 2 else 1
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?.let { bmp -> DecodedPlanImage(bmp.asImageBitmap(), bounds.outWidth) }
        }.getOrNull()
    }

    override suspend fun saveZyxfFile(fileId: Int): String? = withContext(Dispatchers.IO) {
        runCatching { com.xjtu.toolbox.zyxf.ZyxfApi.download(context, fileId) }.getOrNull()
            ?.let { "已保存到下载" }
    }
}

// ── 相册挑图（`:core` 的 AgentImagePicker 在这一端的实现）──────────────────────

/** 系统相册挑图：`:app` 用 `rememberLauncherForActivityResult` 实现 `:core` 的那格缝。 */
@Composable
internal fun rememberAndroidAgentImagePicker(): AgentImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 待回填的（还能再带几张 → 回调），选完由系统回调里取走并清空。
    var pending by remember { mutableStateOf<Pair<Int, (List<String>) -> Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AgentVision.MAX_IMAGES_PER_MESSAGE)
    ) { uris ->
        val p = pending
        pending = null
        if (uris.isEmpty() || p == null) return@rememberLauncherForActivityResult
        val (room, onPicked) = p
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.take(room).mapNotNull { attachAgentImage(context, it) }
            }
            onPicked(added)
        }
    }
    return remember(launcher, context) {
        AgentImagePicker { room, onPicked ->
            pending = room to onPicked
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

/** 压缩后最长边。对齐服务端的重采样尺寸，再大不会提高识别效果，只会更慢。 */
private const val AGENT_IMAGE_MAX_EDGE = 1300

private const val AGENT_IMAGE_JPEG_QUALITY = 88

private const val AGENT_IMAGE_DIR = "agent_images"

private const val AGENT_IMAGE_TAG = "AgentVision"

private fun agentImageDir(context: Context): File =
    File(context.filesDir, "$AGENT_IMAGE_DIR${AccountContext.safeSuffix()}")
        .apply { mkdirs() }

/** 把用户选中的图片压好存进私有目录，返回文件绝对路径；失败返回 null。 */
private fun attachAgentImage(context: Context, uri: Uri): String? = try {
    val source = decodeAgentImageScaled(context, uri)
    if (source == null) {
        null
    } else {
        val out = File(agentImageDir(context), "${UUID.randomUUID()}.jpg")
        out.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, AGENT_IMAGE_JPEG_QUALITY, it) }
        source.recycle()
        out.absolutePath
    }
} catch (e: Exception) {
    Log.w(AGENT_IMAGE_TAG, "attach failed: $uri", e)
    null
}

private fun decodeAgentImageScaled(context: Context, uri: Uri): Bitmap? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        val longEdge = maxOf(info.size.width, info.size.height)
        if (longEdge > AGENT_IMAGE_MAX_EDGE) {
            val scale = AGENT_IMAGE_MAX_EDGE.toFloat() / longEdge
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
        }
    }
}.onFailure { Log.w(AGENT_IMAGE_TAG, "decode failed: $uri", it) }.getOrNull()

// ── 回复里的远程图─────────────────────

/** 回复里的远程图。 */
private object AndroidAgentImages : AgentImageLoader {
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val TARGET_PX = 1080

    private val cache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val client by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    override fun cached(url: String): ImageBitmap? = cache.get(url)?.asImageBitmap()

    override fun hasFailed(url: String): Boolean = url in failed

    override suspend fun load(url: String): ImageBitmap? {
        if (!url.startsWith("https://") || url in failed) return null
        cache.get(url)?.let { return it.asImageBitmap() }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { resp ->
                    val len = resp.body?.contentLength() ?: -1L
                    if (!resp.isSuccessful || len > MAX_BYTES) return@use null
                    resp.body?.bytes()?.takeIf { it.size <= MAX_BYTES }
                } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > TARGET_PX * 2) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()?.also { cache.put(url, it) }?.asImageBitmap() ?: null.also { failed.add(url) }
        }
    }
}

// ── 首屏那几张卡片的本地数据快照────────

/** 首屏要用的本地数据快照。 */
private object AndroidWelcomeFactsLoader {

    fun load(ctx: Context, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): WelcomeFacts = runCatching {
        val today = now.toLocalDate()
        val cache = DataCache(ctx)
        val holidays = com.xjtu.toolbox.schedule.HolidayApi.peekCached(DataCache(ctx)).mapKeys { it.key.toJavaTime() }
        val schedule = com.xjtu.toolbox.schedule.ScheduleCache.readCurrentTermSchedule(cache)
        val courses: List<com.xjtu.toolbox.schedule.CourseItem> = schedule?.courses.orEmpty()
        val start = schedule?.start?.toJavaTime()
        fun on(date: java.time.LocalDate): List<com.xjtu.toolbox.schedule.CourseItem> {
            if (start == null || holidays.containsKey(date)) return emptyList()
            val week = com.xjtu.toolbox.schedule.TermWeeks.weekOf(start.toKx(), date.toKx())
            return courses.filter { it.dayOfWeek == date.dayOfWeek.value && it.isInWeek(week) }
                .distinctBy { Triple(it.courseName, it.startSection, it.location) }
                .sortedBy { it.startSection }
        }
        // XjtuTime 给 kotlinx 的 LocalTime → 先按同一套类型转换，再直接比大小
        val nowTime = now.toLocalTime().toKx()
        val todayCourses = on(today)
        val upcoming = todayCourses.filter { c ->
            com.xjtu.toolbox.schedule.XjtuTime.getClassTime(c.startSection)?.start?.let { it > nowTime } ?: false
        }
        val prefs = com.xjtu.toolbox.card.CampusCardCache.cardPrefs(ctx)
        WelcomeFacts(
            chatter = ChatterFactsLoader.load(ctx, today),
            nextToday = upcoming.firstOrNull(),
            todayLeft = upcoming.size,
            tomorrow = on(today.plusDays(1)),
            exam = com.xjtu.toolbox.schedule.cachedNextExam(DataCache(ctx)),
            balance = prefs.getFloat("card_balance_cache", -1f),
            todaySpend = prefs.getFloat("card_today_spend_cache", -1f),
        )
    }.getOrDefault(WelcomeFacts())
}
