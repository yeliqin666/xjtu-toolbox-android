package com.xjtu.toolbox.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.attendance.AttendanceRecordStore
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.SlotAttendanceMarks
import com.xjtu.toolbox.schedule.attendanceMarksOf
import com.xjtu.toolbox.util.CampusZoneId
import com.xjtu.toolbox.util.RequestCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 卡片此刻要显示的一屏：场景标签、主信息、补充、底部去处和点开去哪儿。
 *
 * [status] 非空时，补充那一行下面再单独画一行状态（考勤结果），并染上 [statusColor]。
 */
internal data class SmartWidgetScene(
    val label: String,
    val title: String,
    val detail: String,
    val action: String,
    val route: AppRoute,
    val status: String? = null,
    val statusColor: Int? = null,

    /**
     * 这个结论是什么时候的事实（`刚刚查过` / `3 分钟前查过` / `还未查询`）。
     *
     * 学校系统没有推送，卡片只能定时间去问；**如实写出问的时刻**比假装"此刻就是如此"重要 ——
     * 用户看到「5 分钟前查过 · 还没刷上」会知道去补，看到「还没查询」就会知道是没同步上。
     */
    val meta: String? = null,
    /**
     * 这一屏的考勤结论只是**按本机数据/时钟推出来的**，不是学校写下的定论。
     *
     * 用在两处：点上「刷新」后显示「正在查询…」；以及让人知道这句话随时可能被学校那条记录推翻。
     */
    val statusIsGuess: Boolean = false,
)

/**
 * 本堂考勤的结论。[recorded] 才是「这句是不是学校写下的」——
 * 文本和颜色都可能只是按时钟的推断（见 `SmartWidgetUpdater.attendanceOf`）。
 */
internal data class AttendanceOutcome(
    val text: String,
    val color: Int,
    /** 学校已经写下结论（正常/迟到/缺勤/请假）：这条不会再变了。 */
    val recorded: Boolean,

    /** 结论的时效说明；[recorded] 为真时不需要（有记录就是定论）。 */
    private val pendingMeta: String? = null,
) {
    /**
     * 给卡片那一行小字：**这个状态是什么时候的**。
     *
     * 有记录 = 学校已经写下结论，不用再标时刻（它不会自己变）；
     * 没记录 = 只是「我们上次去问的结果」，必须写清查的时刻，否则就是在暗示"此刻就是这样"。
     */
    fun meta(checkedAgoMin: Int?): String? {
        if (recorded) return null
        return pendingMeta ?: when {
            checkedAgoMin == null -> "还未查询过"
            checkedAgoMin <= 0 -> "刚刚查过"
            else -> "${checkedAgoMin} 分钟前查过"
        }
    }
}

/**
 * 智能卡片（#408）：只读本机缓存，按此刻挑一屏。
 * - 课前 [CLASS_LEAD_MIN] 分钟到下课：这节课，**并把本堂考勤状态直接写在卡片上**
 *   （读考勤流水落在本机的那份，不登录站点、不发请求）。以前这里只有一颗「查看考勤 ›」，
 *   用户还得点进去才知道到底记上没有，最该一眼看到的那个信息反而藏在里面；
 * - 饭点：余额，点开付款码（付款码不画在桌面上，谁都能扫）；
 * - 其他时间：今天下一节课，没有了就显示余额。
 */
object SmartWidgetUpdater {
    const val ACTION_REFRESH = "com.xjtu.toolbox.widget.ACTION_REFRESH_SMART_WIDGET"
    /** 用户点了卡片上的「刷新」：立刻去问一次学校，不等下一个自查时刻。 */
    const val ACTION_REFRESH_NOW = "com.xjtu.toolbox.widget.ACTION_REFRESH_NOW_SMART_WIDGET"
    private const val CLASS_LEAD_MIN = 20

    // 考勤状态的语义色：和课表格子上的角标同一套（缺勤红、迟到黄、请假灰、正常蓝）
    private const val COLOR_NORMAL = 0xFF2E6BE6.toInt()
    private const val COLOR_LATE = 0xFFF5A524.toInt()
    private const val COLOR_ABSENT = 0xFFE5484D.toInt()
    private const val COLOR_LEAVE = 0xFF8A9099.toInt()
    private const val COLOR_PENDING = 0xFF8A9099.toInt()

    /**
     * 把一次刷卡算进这节课的时间窗时，往前放宽多少分钟。
     *
     * 人在上课前几分钟就在楼里刷了，这条刷卡当然属于这堂课 ——
     * 卡得太死会把「提前刷的」判成没刷，那正是这张卡片最不该犯的错。
     */
    private const val SWIPE_WINDOW_BEFORE_MIN = 25

    /** 上课后多少分钟还没记录就按「迟到」提示。学校刷卡有延迟，留五分钟。 */
    private const val LATE_AFTER_MIN = 5



    /** 早、午、晚饭的窗口（距 00:00 的分钟）。 */
    private val MEALS = listOf(
        Triple("早饭时间", 6 * 60 + 30, 9 * 60),
        Triple("午饭时间", 11 * 60, 13 * 60 + 30),
        Triple("晚饭时间", 17 * 60, 19 * 60 + 30),
    )

    fun requestUpdate(context: Context) {
        context.sendBroadcast(Intent(context, SmartWidgetProvider::class.java).setAction(ACTION_REFRESH))
    }

    internal fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        com.xjtu.toolbox.account.AccountContext.restoreIfNeeded()
        val now = LocalDateTime.now(CampusZoneId)
        val nowMinute = now.hour * 60 + now.minute
        val today = now.toLocalDate().toString()
        val courses = runCatching { ScheduleWidgetUpdater.todayCourses(context) }.getOrDefault(emptyList())
        // 后台自查问到的那条刷卡 + 上次问的时刻：卡片据此说「已刷卡 · 待结算」「N 分钟前查过」
        val swipe = runCatching { AttendanceWidgetSync.todaySwipe(context, today) }.getOrNull()
        val lastCheck = runCatching { AttendanceWidgetSync.lastCheckAt(context) }.getOrDefault(0L)
        val checkedAgoMin = lastCheck.takeIf { it > 0L }
            ?.let { ((System.currentTimeMillis() - it) / 60_000).toInt() }
        val querying = runCatching { AttendanceWidgetSync.isQuerying(context) }.getOrDefault(false)
        val built = sceneOf(
            courses = courses,
            nowMinute = nowMinute,
            balance = balanceText(context),
            attendance = runCatching { todayAttendance(context) }.getOrNull(),
            date = today,
            swipe = swipe,
            checkedAgoMin = checkedAgoMin,
        )
        // 「正在查询…」只在真正答不上来的那一屏上顶替时效文案：用户点了刷新要能看到反应，
        // 但不能把「已打卡 · 正常」这种实打实的结论也换成在查。
        val scene = if (querying && built.statusIsGuess) built.copy(meta = "正在查询…") else built
        val launch = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, scene.route.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, RequestCodes.SMART_WIDGET, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_smart)
            views.setTextViewText(R.id.widget_smart_label, scene.label)
            views.setTextViewText(R.id.widget_smart_title, scene.title)
            views.setTextViewText(R.id.widget_smart_detail, scene.detail)
            views.setTextViewText(R.id.widget_smart_action, scene.action)
            // 考勤那一行：有状态才显示，顺带换色；没有就整行隐掉，别留一条空行
            views.setTextViewText(R.id.widget_smart_status, scene.status.orEmpty())
            views.setViewVisibility(
                R.id.widget_smart_status,
                if (scene.status.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE,
            )
            scene.statusColor?.let { views.setTextColor(R.id.widget_smart_status, it) }
            // 状态时效：这一行是「上面那句话是什么时候的事实」，没有就整行隐掉
            views.setTextViewText(R.id.widget_smart_meta, scene.meta.orEmpty())
            views.setViewVisibility(
                R.id.widget_smart_meta,
                if (scene.meta.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE,
            )
            views.setOnClickPendingIntent(R.id.widget_smart_root, pending)
            views.setOnClickPendingIntent(R.id.widget_smart_refresh, refreshNowIntent(context))
            manager.updateAppWidget(id, views)
        }
        // 这里**不做任何联网自查**（2026-11-18 定）：学校没有推送，只能靠问，而问就是一次次
        // 登录/请求；一节课几分钟一轮，全天纯属白耗电、白打学校接口。改成用户点卡片上的
        // 「刷新」才查（见 SmartWidgetProvider.onReceive 的 ACTION_REFRESH_NOW）。
        // 卡片照旧只在场景边界重画 —— 那是原本就有的行为，本地渲染，不联网。
        scheduleNext(context, courses, now)
    }

    /**
     * 这一学期落在本机的考勤索引。
     *
     * 读的是考勤流水落在本机的那份（[AttendanceRecordStore]），也就是课表格子角标用的同一份数据。
     * **卡片里绝不登录考勤站点**：小组件会随桌面反复刷新、还会被系统在后台拉起，
     * 为此反复登录既慢又容易被判异常访问。
     *
     * 账号类型必须现问 [AccountStore]：小组件进程是被系统拉起的，[restoreIfNeeded] 只补了
     * `activeAccountId`，`activeAccountType` 还是默认的「本科」——研究生会去读本科那份分片，
     * 永远读不到自己的记录，表现同样是「考勤未记录」。
     *
     * @return 拿不到落盘缓存时返回 null。
     */
    internal fun todayAttendance(context: Context): SlotAttendanceMarks? {
        val termCode = ScheduleWidgetUpdater.currentTermCode(context) ?: return null
        val account = com.xjtu.toolbox.account.AccountContext.activeAccountId
            ?: com.xjtu.toolbox.account.AccountStore().activeAccountId()
        val postgraduate = runCatching {
            com.xjtu.toolbox.account.AccountStore().activeAccount()?.accountType ==
                com.xjtu.toolbox.auth.AccountType.POSTGRADUATE
        }.getOrDefault(
            com.xjtu.toolbox.account.AccountContext.activeAccountType ==
                com.xjtu.toolbox.auth.AccountType.POSTGRADUATE,
        )
        val shard = AttendanceRecordStore.load(postgraduate, termCode, account) ?: return null
        return attendanceMarksOf(shard.records)
    }

    /**
     * @param attendance 这一学期落在本机的考勤索引；null = 本机还没有考勤缓存。
     * @param date 今天的日期（`2026-11-18`），按日期 + 节次找这一格的记录。
     */
    internal fun sceneOf(
        courses: List<WidgetCourse>,
        nowMinute: Int,
        balance: String,
        attendance: SlotAttendanceMarks? = null,
        date: String = "",
        /** 本机记下的今天那条刷卡（后台自查问到的第一手事实）。 */
        swipe: LocalSwipe? = null,
        /** 上次成功问过学校是几分钟前；null = 从没问过。 */
        checkedAgoMin: Int? = null,
    ): SmartWidgetScene {
        val inClass = courses.firstOrNull { nowMinute >= it.startMinute - CLASS_LEAD_MIN && nowMinute < it.endMinute }
        if (inClass != null) {
            val started = nowMinute >= inClass.startMinute
            val label = if (started) "上课中" else "${inClass.startMinute - nowMinute} 分钟后上课"
            val outcome = attendanceOf(attendance, inClass, date, nowMinute, swipe)
            return SmartWidgetScene(
                label = label,
                title = inClass.name,
                detail = timeAndPlace(inClass),
                action = "查看考勤 ›",
                route = AppRoute.Attendance,
                status = outcome.text,
                statusColor = outcome.color,
                // 这一行是推断（不是学校写下的结论）：卡片据此标注时效、并在点刷新时显示「正在查询」
                meta = outcome.meta(checkedAgoMin),
                statusIsGuess = !outcome.recorded,
            )
        }
        MEALS.firstOrNull { (_, start, end) -> nowMinute in start until end }?.let { (label, _, _) ->
            return SmartWidgetScene(label, "付款码", "校园卡余额 $balance", "打开付款码 ›", AppRoute.PaymentCode)
        }
        val next = courses.firstOrNull { it.startMinute > nowMinute }
        if (next != null) {
            return SmartWidgetScene("下一节 ${clock(next.startMinute)}", next.name, timeAndPlace(next), "查看日程 ›", AppRoute.Schedule)
        }
        val label = if (courses.isEmpty()) "今天没课" else "今天的课上完了"
        return SmartWidgetScene(label, "校园卡 $balance", "饭点会自动换成付款码入口", "查看校园卡 ›", AppRoute.CampusCard)
    }

    /**
     * 这节课的考勤：一句话 + 该染的颜色 + 是不是真有记录。
     *
     * [recorded] 为 false 时，这个结论只是按时钟的推断（「待考勤」/「迟到 · 还没刷上」），
     * 学校那边随时可能写出一条记录推翻它 —— 卡片据此决定要不要自己回来看一眼。
     *
     * 判据按用户要求的口径（2026-11-18 反馈）：
     * - 有记录就照实报：正常 / 迟到 / 缺勤 / 请假（请假单独说，不带「打卡」）；
     * - **课前到上课 5 分钟内没记录 ⇒「待考勤」**——这时候学校还没到出结论的点，
     *   说「未记录」像是在报错；
     * - **上课 5 分钟后仍没记录 ⇒「迟到 · 还没刷上」**，这是用户要的即时提醒：
     *   过了这个点还没刷上，基本就要被记迟到了，得赶紧去补。
     *
     * 这一屏只在「课前 [CLASS_LEAD_MIN] 分钟 ～ 下课」之间出现，所以下课之后的「缺勤」
     * 由学校的流水自己给，不能靠这里按时钟猜。
     *
     * 找记录用日期 + 节次 + 课程号/课名（[SlotAttendanceMarks.recordAt]），**不用周次**：
     * 教务和考勤系统算周次的起始日未必同一天，差一天就整体错一周，
     * 于是明明记了考勤却查不到——正是「考勤正常却显示未记录」的成因。
     * 认课优先用课程号：两个系统同一套教务课程号，比课名可靠（见那个方法的注释）。
     */
    private fun attendanceOf(
        attendance: SlotAttendanceMarks?,
        course: WidgetCourse,
        date: String,
        nowMinute: Int,
        swipe: LocalSwipe? = null,
    ): AttendanceOutcome {
        val record = attendance
            ?.takeIf { course.startSection > 0 && date.isNotBlank() }
            ?.recordAt(date, course.startSection, course.endSection, course.name, course.courseCode)
        if (record != null) {
            val (text, color) = when (record.status) {
                WaterType.NORMAL -> "已打卡 · 正常" to COLOR_NORMAL
                WaterType.LATE -> "已打卡 · 迟到" to COLOR_LATE
                WaterType.ABSENCE -> "已打卡 · 缺勤" to COLOR_ABSENT
                WaterType.LEAVE -> "已请假" to COLOR_LEAVE
                // 未识别的状态码不冒充正常，也不吓人，原样说「有记录」
                WaterType.UNKNOWN -> "已打卡 · 状态未知" to COLOR_LEAVE
            }
            return AttendanceOutcome(text, color, recorded = true)
        }

        // 考勤结果还没结算，但**刷卡流水是实时的**：这段时间里刷过卡，就照实说「已刷卡」，
        // 而不是继续喊「还没刷上」让人白跑一趟。学校结算后会被上面那个分支接管。
        val swipeInThisClass = swipe?.takeIf {
            it.minuteOfDay >= course.startMinute - SWIPE_WINDOW_BEFORE_MIN &&
                it.minuteOfDay <= course.endMinute
        }
        if (swipeInThisClass != null) {
            return AttendanceOutcome(
                text = "已刷卡 · 待结算",
                color = COLOR_NORMAL,
                recorded = false,
                pendingMeta = "刷卡 ${swipeInThisClass.clock}",
            )
        }

        // 这一格还没有记录：按此刻距上课多久给一个即时结论
        val lateAt = course.startMinute + LATE_AFTER_MIN
        return if (nowMinute < lateAt) AttendanceOutcome("待考勤", COLOR_PENDING, recorded = false)
        else AttendanceOutcome("迟到 · 还没刷上", COLOR_LATE, recorded = false)
    }

    /**
     * 下一个场景边界：课前提醒起点、上课、**上课后 5 分钟**（「待考勤」→「迟到」那一跳）、
     * 下课、饭点起止；今天没有了就到明天零点。
     *
     * 少了「上课后 5 分钟」这一条，卡片就会一直停在「待考勤」：系统只在边界时刻重画，
     * 而准时上课的人根本收不到那条迟到提醒——偏偏那是这张卡片最有用的信息。
     *
     */
    private fun scheduleNext(
        context: Context,
        courses: List<WidgetCourse>,
        now: LocalDateTime,
    ) {
        val nowMinute = now.hour * 60 + now.minute
        val boundaries = courses.flatMap {
            listOf(it.startMinute - CLASS_LEAD_MIN, it.startMinute, it.startMinute + LATE_AFTER_MIN, it.endMinute)
        } + MEALS.flatMap { listOf(it.second, it.third) }
        val at = boundaries.filter { it > nowMinute }.minOrNull()
            ?.let { now.toLocalDate().atStartOfDay().plusMinutes(it.toLong()) }
            ?: now.toLocalDate().plusDays(1).atStartOfDay()
        val millis = at.atZone(CampusZoneId).toInstant().toEpochMilli()
        // 非精准闹钟就够：场景切换晚一两分钟无妨，不必为此申请精准闹钟权限
        context.getSystemService(AlarmManager::class.java)
            ?.setWindow(AlarmManager.RTC, millis, 2 * 60_000L, refreshIntent(context))
    }
    internal fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(refreshIntent(context))
    }

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RequestCodes.SMART_WIDGET_REFRESH,
        Intent(context, SmartWidgetProvider::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 卡片上那颗「刷新」：发一条广播给小组件自己。
     *
     * **不打开 App**：点一下只做「立刻问一次学校 + 重画卡片」，这是用户想要「马上知道」时
     * 最省事的一条路，也让下面那套自动自查不必为了响应速度一路高频。
     */
    private fun refreshNowIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RequestCodes.SMART_WIDGET_REFRESH_NOW,
        Intent(context, SmartWidgetProvider::class.java).setAction(ACTION_REFRESH_NOW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun balanceText(context: Context): String {
        val balance = com.xjtu.toolbox.card.CampusCardCache.cardPrefs(context).getFloat("card_balance_cache", -1f)
        return if (balance < 0f) "--" else "¥%.2f".format(balance)
    }

    private fun timeAndPlace(c: WidgetCourse): String =
        listOf("${clock(c.startMinute)}–${clock(c.endMinute)}", c.location).filter { it.isNotBlank() }.joinToString(" · ")

    private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}

/** 渲染要读缓存、查 Room，goAsync 后放到 IO 线程做，和课表小组件一样。 */
class SmartWidgetProvider : AppWidgetProvider() {
    private fun updateAsync(context: Context, ids: () -> IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmartWidgetUpdater.update(app, AppWidgetManager.getInstance(app), ids())
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAsync(context) { appWidgetIds }
    }

    override fun onDisabled(context: Context) = SmartWidgetUpdater.cancel(context)

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        when (intent?.action) {
            SmartWidgetUpdater.ACTION_REFRESH -> updateAsync(context) {
                AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, SmartWidgetProvider::class.java))
            }
            // 点了卡片上的「刷新」：先把「正在查询…」挂上让人看见反应，再绕过节流去问一次
            SmartWidgetUpdater.ACTION_REFRESH_NOW -> {
                AttendanceWidgetSync.markQuerying(context)
                AttendanceWidgetSync.requestSync(context)
                updateAsync(context) {
                    AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, SmartWidgetProvider::class.java))
                }
            }
        }
    }
}
