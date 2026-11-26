package com.xjtu.toolbox.tips

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.singletonState
import kotlin.time.Clock

enum class TipKind(val label: String) { FEATURE("冷门功能"), LOOKS_LIKE_BUG("看着像 bug"), MEME("梗") }

/**
 * 一条小贴士。[route] 非空时带「去试试」；[newcomer] 的装机头两周优先出（#458 新手引导并在这里）。
 * [agentSettings]：讲的是小岱设置页里的项，「去试试」进小岱页后直接打开设置（见 [com.xjtu.toolbox.nav.AgentSettingsRequest]）。
 */
data class Tip(
    val id: String,
    val kind: TipKind,
    val title: String,
    val body: String,
    val route: AppRoute? = null,
    val newcomer: Boolean = false,
    val agentSettings: Boolean = false,
)

/** 内置的小贴士（#460）。只写代码里核实过的行为；改了功能记得回来改这里。 */
object Tips {
    val all: List<Tip> = listOf(
        // ── 新手向 ──
        Tip("home-search-inbox", TipKind.FEATURE, "首页右上角：搜索与消息收纳",
            "放大镜能搜到所有功能；旁边的消息收纳把课表变动、考勤、成绩、作业截止和通知收在一起，也能记待办。",
            AppRoute.Inbox, newcomer = true),
        Tip("bottom-tabs", TipKind.FEATURE, "底栏可以换成你的常用功能",
            "在「我的 → 设置 → 外观 → 底栏」里，把首页的一级功能放上底栏、调整顺序。小岱不占格子，固定在底栏右边的圆球上。",
            AppRoute.Settings, newcomer = true),
        Tip("pidai-orb", TipKind.FEATURE, "按住小岱球说话",
            "底栏右边的小岱球：点一下打开小岱（首页进小岱页，其他页弹出大半屏）；按住直接说话，松手自动发出去，说到一半往上滑可以取消。语音要先在小岱设置里填 DeepSeek Key。",
            AppRoute.Agent, newcomer = true),
        Tip("hero-appearance", TipKind.FEATURE, "首页顶部卡片，长按换外观",
            "长按顶部卡片可以换配色和流动速度，背景有液体、森林、像素、纯色，也能用自己的图片。",
            newcomer = true),
        Tip("pidai-share", TipKind.FEATURE, "文章和图片，交给小岱读",
            "在微信、浏览器或相册里选「使用其他方式打开 → 小岱」或「分享 → 小岱收纳」，会新开对话阅读链接或识别图片，每次最多 4 张。",
            newcomer = true),
        Tip("widgets", TipKind.FEATURE, "不打开 App 也能看日程和待办",
            "长按桌面空白处添加小组件：日程、校园卡余额、教务通知、待办，还有智能卡片——课前显示这节课和考勤入口，饭点显示余额和付款码。",
            newcomer = true),
        Tip("schedule-by-pidai", TipKind.FEATURE, "把安排直接告诉小岱",
            "比如「帮我记下明天下午两点开组会」，小岱会记进日程；自建日程在日程页点开就能改。",
            AppRoute.Agent, newcomer = true),
        Tip("qr-scan", TipKind.FEATURE, "扫一扫能登电脑",
            "「我的」页右上角的扫一扫，可以扫电脑上统一身份认证的登录二维码，也能扫图书馆座位码。",
            AppRoute.QrScan, newcomer = true),

        // ── 冷门功能 ──
        Tip("pidai-interject", TipKind.FEATURE, "小岱回答时也能插话",
            "回答过程中可以继续发消息补充条件；点停止可以中断这次回答。", AppRoute.Agent),
        Tip("pidai-settings", TipKind.FEATURE, "设置也能直接跟小岱说",
            "比如「切到深色模式」「默认打开日程页」，小岱能直接改这类设置。", AppRoute.Agent),
        Tip("notice-picks", TipKind.FEATURE, "通知精选，范围自己定",
            "在通知页的「小岱精选」里选来源和关注的主题，和你相关的通知排在前面。", AppRoute.Notification),
        Tip("seat-favorite", TipKind.FEATURE, "常坐的座位，长按收藏",
            "图书馆座位列表里长按座位就能收藏；在座位图上先点选中再长按。", AppRoute.Library),
        Tip("home-edit", TipKind.FEATURE, "首页卡片可以挪位置",
            "卡片组布局下，长按首页空白处进入编辑，拖动卡片调整位置。"),
        Tip("pidai-proactive", TipKind.FEATURE, "小岱的提醒气泡可以调",
            "小岱球头上的提醒气泡（上课、余额、考试、新成绩等）嫌多，可以在小岱页右上角设置的「主动提醒」里调频率或关掉。",
            AppRoute.Agent, agentSettings = true),
        Tip("card-bill", TipKind.FEATURE, "干饭人账单",
            "校园卡的消费分析里，会按你的吃饭习惯生成一张海报，可以存图分享。", AppRoute.CampusCard),
        Tip("venue-captcha", TipKind.FEATURE, "场馆预订自动过滑块",
            "预订场馆时，滑块验证码会自动识别滑过去。", AppRoute.Venue),
        Tip("activity-checkin", TipKind.FEATURE, "活动签到不用另开 App",
            "「活动报名」里能直接签到签退（输入现场 6 位验证码），还能一键找常能开集体活动证明的讲座和分享会。",
            AppRoute.SsActivity),
        Tip("term-report", TipKind.FEATURE, "学期总结",
            "学期所有周结束后会生成一份回顾：早八、全勤、GPA、干饭、和小岱聊了多少，还有一个称号。只在本机计算。",
            AppRoute.TermReport),
        Tip("schedule-match", TipKind.FEATURE, "课表匹配找同伴",
            "「匹配交友」比对课表，找共同空闲一起自习，也能扫码匹配。", AppRoute.Match),
        Tip("submit-homework", TipKind.FEATURE, "作业在哪交",
            "在「思源」的作业详情里点「去提交作业」，会打开学堂官方的提交页。", AppRoute.Lms()),
        Tip("data-archive", TipKind.FEATURE, "换手机前导出数据",
            "「我的 → 设置 → 关于」里可以导出数据存档，到新手机上导入。", AppRoute.Settings),

        // ── 看着像 bug ──
        Tip("paycode-recording", TipKind.LOOKS_LIKE_BUG, "录屏时付款码是空白的？",
            "故意的：Android 15 及以上，录屏、投屏和共享屏幕时系统会遮住付款码，自己截图不受影响。"),
        Tip("paycode-brightness", TipKind.LOOKS_LIKE_BUG, "一打开付款码，屏幕突然变亮？",
            "为了让收银机扫得出来，付款码页面会把亮度调到最高，关掉就恢复。"),
        Tip("network-mode", TipKind.LOOKS_LIKE_BUG, "校外打不开教务、思源？",
            "连接方式默认自动检测。检测不准时，到「我的 → 设置 → 通用 → 连接模式」改成「强制 WebVPN」；在校园网里就选「强制直连」。",
            AppRoute.Settings),
        Tip("offline-cache", TipKind.LOOKS_LIKE_BUG, "没网时看到的是旧数据？",
            "日程和成绩没网也能打开，显示的是上次同步下来的内容。"),
        Tip("android-12-14", TipKind.LOOKS_LIKE_BUG, "Android 12–14 上玻璃效果变成了纯色？",
            "系统限制：Android 12–14 上液态玻璃会换成纯色或模糊样式，动态背景变为静态，小岱读 PDF 改用识图。Android 15 及以上不受影响。"),
        Tip("browser-dark", TipKind.LOOKS_LIKE_BUG, "深色模式下网页颜色怪怪的？",
            "内置浏览器会把网页一起变暗。哪个网站显示错乱，就在浏览器右上角菜单里点「此网站不用深色」；全部关掉在「我的 → 设置 → 外观 → 网页跟随深色模式」。",
            AppRoute.Settings),
        Tip("pidai-quiet", TipKind.LOOKS_LIKE_BUG, "戳小岱它不说话？",
            "小岱页设置里的「主动提醒」关掉以后，点小岱球它也不回话，只会打开小岱。", AppRoute.Agent, agentSettings = true),

        // ── 梗 ──
        Tip("pidai-combo", TipKind.MEME, "连戳小岱三下",
            "在小岱页新对话中间那只大小岱上连戳三下，它会放出自己的彩蛋：经典小岱被戳成陀螺转两圈、晕头转向，换了角色各有各的。"),
        Tip("pidai-cast", TipKind.MEME, "小岱可以换角色",
            "小岱页右上角设置 → 小岱形象：除了经典小岱，还有早八人、木鱼、喵、仓鼠和 MJ。", AppRoute.Agent, agentSettings = true),
        Tip("pidai-nickname", TipKind.MEME, "搜「屁岱」也行",
            "全局搜索里输「屁岱」，一样能找到小岱。"),
    )
}

/**
 * 首页小贴士的状态：开关、关掉过的、第一次见到的日子。每天换一条，装机头两周先出新手向的。
 * 改动会 bump [revision]，首页和设置里的开关据此刷新。
 */
object TipsStore {
    private val store by lazy { keyValueStore("tips") }

    var revision by singletonState { mutableIntStateOf(0) }
        private set

    var enabled: Boolean
        get() = store.getBoolean(K_ENABLED, true)
        set(value) {
            store.putBoolean(K_ENABLED, value)
            revision++
        }

    /** ✕：这条不再出现，今天也不再补一条。 */
    fun dismiss(tip: Tip, nowMs: Long = Clock.System.now().toEpochMilliseconds()) {
        store.putStringSet(K_DISMISSED, dismissed() + tip.id)
        store.putString(K_QUIET_DAY, dayOf(nowMs).toString())
        revision++
    }

    /** 今天该出的那条；关了开关、今天刚关过一条、或全看完了返回 null。 */
    fun today(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Tip? {
        if (!enabled) return null
        val day = dayOf(nowMs)
        if (store.getString(K_QUIET_DAY)?.toLongOrNull() == day) return null
        val firstDay = store.getString(K_FIRST_DAY)?.toLongOrNull()
            ?: day.also { store.putString(K_FIRST_DAY, it.toString()) }
        val left = Tips.all.filter { it.id !in dismissed() }
        val pool = left.filter { it.newcomer }.takeIf { day - firstDay < NEWCOMER_DAYS && it.isNotEmpty() } ?: left
        return pool.getOrNull(((day - firstDay) % pool.size.coerceAtLeast(1)).toInt())
    }

    val dismissedCount: Int get() = dismissed().size

    /** 把关掉过的都放回来。 */
    fun restore() {
        store.remove(K_DISMISSED)
        store.remove(K_QUIET_DAY)
        revision++
    }

    private fun dismissed(): Set<String> = store.getStringSet(K_DISMISSED).orEmpty()

    /** 北京时间的第几天。 */
    private fun dayOf(ms: Long): Long = (ms + BEIJING_OFFSET_MS) / DAY_MS

    private const val K_ENABLED = "enabled"
    private const val K_DISMISSED = "dismissed"
    private const val K_QUIET_DAY = "quiet_day"
    private const val K_FIRST_DAY = "first_day"
    private const val NEWCOMER_DAYS = 14
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val BEIJING_OFFSET_MS = 8L * 60 * 60 * 1000
}
