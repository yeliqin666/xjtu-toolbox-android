package com.xjtu.toolbox.widget

import com.xjtu.toolbox.util.toJavaTime
import com.xjtu.toolbox.util.toKx

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.xjtu.toolbox.main.BottomTab
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.schedule.colorOf
import com.xjtu.toolbox.schedule.courseColorMap
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.xjtu.toolbox.nav.AppRoute

enum class WidgetSize { SMALL, LARGE }

internal data class WidgetCourse(
    val name: String,
    val location: String,
    /** 真实起止（距 00:00 的分钟），按这一天的作息算，见 [CourseItem.clockMinutes]。 */
    val startMinute: Int,
    val endMinute: Int,
    /** 课程色（ARGB），和课表页、思源学堂同一套，用户改过的也认。 */
    val color: Int,
    /** 已经上完：之前的日子全算，今天看下课时间。 */
    val done: Boolean = false,
)

/**
 * 4x2「今天 / 明天」两栏要的数据。
 *
 * 和 [WidgetScheduleData] 分开：那个带着周次偏移、选中星期这些**浏览状态**，
 * 是 2x2 的翻页交互要用的；4x2 改版后没有翻页，只认真实的今天和明天，
 * 混用会让"明天"跟着用户在 2x2 上翻到的那一天跑。
 */
internal data class WidgetTwoDayData(
    val dayNumber: String,
    val monthText: String,
    val dowText: String,
    val weekText: String,
    val today: List<WidgetCourse>,
    val tomorrow: List<WidgetCourse>,
    val todayEmpty: EmptyState,
    val tomorrowEmpty: EmptyState,
)

internal data class WidgetScheduleData(
    val weekText: String,
    val dayText: String,
    val courses: List<WidgetCourse>,
    val empty: EmptyState,
)

/** 没课（或没缓存）时列表位置显示的：一行醒目的标题，一行有用的提示（下一节课、放假）。 */
internal data class EmptyState(val title: String, val detail: String = "")

private val NO_CACHE = EmptyState("还没有课表", "打开 App 就会同步")

object ScheduleWidgetUpdater {
    const val ACTION_REFRESH = "com.xjtu.toolbox.widget.ACTION_REFRESH_SCHEDULE_WIDGET"
    const val EXTRA_RESET_TO_TODAY = "com.xjtu.toolbox.widget.EXTRA_RESET_TO_TODAY"
    const val ACTION_DAY_PREV = "com.xjtu.toolbox.widget.ACTION_SCHEDULE_WIDGET_DAY_PREV"
    const val ACTION_DAY_NEXT = "com.xjtu.toolbox.widget.ACTION_SCHEDULE_WIDGET_DAY_NEXT"

    /** 适配器要取哪一天：0=今天，1=明天。只有 4x2 用。 */
    const val EXTRA_DAY_OFFSET = "widget_day_offset"

    private const val PREFS_NAME = "schedule_widget_prefs"
    private const val KEY_WEEK_OFFSET = "week_offset"
    private const val KEY_DAY_OF_WEEK = "day_of_week"
    /** 周次偏移、选中星期是哪天定下的；换了一天就作废，回到今天。 */
    private const val KEY_BROWSE_DATE = "browse_date"
    private const val REFRESH_ALARM_CODE = 5000
    private const val MIN_WEEK_OFFSET = -30
    private const val MAX_WEEK_OFFSET = 30

    private val holidayFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 小组件用的节假日表：**只读本地缓存**。
     *
     * 这里的调用链跑在 AppWidgetProvider.onUpdate/onReceive 里，也就是主线程。以前直接
     * runBlocking 调 [com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates]，缓存为空时会在
     * 主线程同步请求两个外部接口（各 10s 超时），而换包会清空 DataCache，于是每次升级后
     * 第一次刷新小组件都有 ANR 风险。缓存为空就先按"无节假日"渲染，后台拉到后再刷一次。
     */
    private fun widgetHolidays(context: Context): Map<LocalDate, String> {
        val cached = com.xjtu.toolbox.schedule.HolidayApi.peekCached(context)
        if (cached.isNotEmpty()) return cached
        if (holidayFetchInFlight.compareAndSet(false, true)) {
            val app = context.applicationContext
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val fetched = runCatching {
                        com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates(app)
                    }.getOrDefault(emptyMap())
                    // 拉不到就别刷新，否则每次刷新又触发一次拉取，形成循环
                    if (fetched.isNotEmpty()) requestUpdate(app, resetToToday = false)
                } finally {
                    holidayFetchInFlight.set(false)
                }
            }
        }
        return emptyMap()
    }

    fun requestUpdate(context: Context, resetToToday: Boolean = true) {
        context.sendBroadcast(
            Intent(context, ScheduleWidget2x2Provider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(EXTRA_RESET_TO_TODAY, resetToToday)
            }
        )
        context.sendBroadcast(
            Intent(context, ScheduleWidget4x2Provider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(EXTRA_RESET_TO_TODAY, resetToToday)
            }
        )
    }

    fun handleAction(context: Context, action: String?): Boolean {
        when (action) {
            ACTION_DAY_PREV -> {
                adjustSelectedDayOfWeek(context, -1)
                requestUpdate(context, resetToToday = false)
                return true
            }

            ACTION_DAY_NEXT -> {
                adjustSelectedDayOfWeek(context, 1)
                requestUpdate(context, resetToToday = false)
                return true
            }
        }
        return false
    }

    internal fun resetBrowseSelectionToToday(context: Context) {
        val today = LocalDate.now()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_WEEK_OFFSET, 0)
            .putInt(KEY_DAY_OF_WEEK, today.dayOfWeek.value)
            .putLong(KEY_BROWSE_DATE, today.toEpochDay())
            .apply()
    }

    /** 翻页状态是前些天留下的就回到今天，否则过了零点还停在昨天那个星期几。 */
    private fun dropStaleBrowseState(context: Context) {
        val browseDate = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_BROWSE_DATE, -1L)
        if (browseDate != LocalDate.now().toEpochDay()) resetBrowseSelectionToToday(context)
    }

    fun updateSpecific(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        size: WidgetSize
    ) {
        if (appWidgetIds.isEmpty()) return
        // 只有 2x2 用得上它，而它要读缓存、查 Room、拉节假日；4x2 走
        // loadTwoDayData 自己那套，别为它白跑一遍。
        val data = if (size == WidgetSize.SMALL) loadScheduleData(context) else null
        appWidgetIds.forEach { widgetId ->
            val views = when (size) {
                WidgetSize.SMALL -> buildSmallViews(context, data!!, widgetId)
                WidgetSize.LARGE -> buildLargeViews(context, widgetId)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_course_list)
        // 4x2 有第二个集合视图（明天那栏）。漏了它，明天的课会一直停在上次的数据上。
        if (size == WidgetSize.LARGE) {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_course_list_next)
        }
        scheduleNextRefresh(context, size)
    }

    /** 课多时把列表滚到第一节还没上完的课。 */
    private fun scrollToUpcoming(views: RemoteViews, listId: Int, courses: List<WidgetCourse>) {
        val index = courses.indexOfFirst { !it.done }
        if (index > 0) views.setScrollPosition(listId, index)
    }

    private fun buildLaunchPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, AppRoute.Main.id)
            putExtra(MainActivity.EXTRA_LAUNCH_TAB, BottomTab.COURSES.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildActionPendingIntent(
        context: Context,
        requestCode: Int,
        action: String,
        providerClass: Class<out AppWidgetProvider>
    ): PendingIntent {
        val intent = Intent(context, providerClass).apply { this.action = action }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * @param dayOffset 0=今天，1=明天。会进 Intent 的 data（[Intent.toUri] 把 extra 也编了进去），
     *                  两栏因此拿到两个不同的 Intent —— 系统按 filterEquals 复用
     *                  RemoteViewsFactory，data 一样的话两栏会共用同一个工厂、显示同一天。
     */
    private fun buildCourseListAdapterIntent(
        context: Context,
        appWidgetId: Int,
        size: WidgetSize,
        dayOffset: Int = 0,
    ): Intent {
        return Intent(context, ScheduleWidgetRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("widget_size", size.name)
            putExtra(EXTRA_DAY_OFFSET, dayOffset)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
    }

    /** 2x2：第一行「周几 第几周 ‹ ›」，其余全给课表。左右按钮逐天翻，跨周自动滚。 */
    private fun buildSmallViews(context: Context, data: WidgetScheduleData, appWidgetId: Int): RemoteViews {
        val provider = ScheduleWidget2x2Provider::class.java
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_2x2)
        views.setTextViewText(R.id.widget_day_text, data.dayText)
        views.setTextViewText(R.id.widget_week, data.weekText)
        views.setOnClickPendingIntent(R.id.widget_root, buildLaunchPendingIntent(context, 1001))
        views.setOnClickPendingIntent(R.id.widget_day_prev, buildActionPendingIntent(context, 1201, ACTION_DAY_PREV, provider))
        views.setOnClickPendingIntent(R.id.widget_day_next, buildActionPendingIntent(context, 1202, ACTION_DAY_NEXT, provider))
        views.setRemoteAdapter(R.id.widget_course_list, buildCourseListAdapterIntent(context, appWidgetId, WidgetSize.SMALL))
        views.setEmptyView(R.id.widget_course_list, R.id.widget_empty)
        views.setPendingIntentTemplate(R.id.widget_course_list, buildLaunchPendingIntent(context, 3000 + appWidgetId))
        bindEmpty(views, R.id.widget_empty, R.id.widget_empty_title, R.id.widget_empty_detail, data.empty, data.courses.isEmpty())
        scrollToUpcoming(views, R.id.widget_course_list, data.courses)
        return views
    }

    private fun bindEmpty(views: RemoteViews, containerId: Int, titleId: Int, detailId: Int, state: EmptyState, show: Boolean) {
        views.setViewVisibility(containerId, if (show) View.VISIBLE else View.GONE)
        views.setTextViewText(titleId, state.title)
        views.setTextViewText(detailId, state.detail)
        views.setViewVisibility(detailId, if (state.detail.isEmpty()) View.GONE else View.VISIBLE)
    }

    /** 4x2：今天 | 明天 两栏，没有任何翻页控件，不受 2x2 浏览状态影响。 */
    private fun buildLargeViews(context: Context, appWidgetId: Int): RemoteViews {
        val data = loadTwoDayData(context)
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_4x2)
        views.setTextViewText(R.id.widget_date_day, data.dayNumber)
        views.setTextViewText(R.id.widget_date_month, data.monthText)
        views.setTextViewText(R.id.widget_date_dow, data.dowText)
        views.setTextViewText(R.id.widget_week, data.weekText)
        views.setOnClickPendingIntent(R.id.widget_root, buildLaunchPendingIntent(context, 1002))

        listOf(
            Triple(R.id.widget_course_list, R.id.widget_empty, 0),
            Triple(R.id.widget_course_list_next, R.id.widget_empty_next, 1),
        ).forEach { (listId, emptyId, dayOffset) ->
            views.setRemoteAdapter(
                listId,
                buildCourseListAdapterIntent(context, appWidgetId, WidgetSize.LARGE, dayOffset)
            )
            views.setEmptyView(listId, emptyId)
            views.setPendingIntentTemplate(
                listId,
                buildLaunchPendingIntent(context, 4000 + dayOffset * 1000 + appWidgetId)
            )
        }

        bindEmpty(views, R.id.widget_empty, R.id.widget_empty_title, R.id.widget_empty_detail, data.todayEmpty, data.today.isEmpty())
        bindEmpty(
            views, R.id.widget_empty_next, R.id.widget_empty_next_title, R.id.widget_empty_next_detail,
            data.tomorrowEmpty, data.tomorrow.isEmpty(),
        )
        scrollToUpcoming(views, R.id.widget_course_list, data.today)
        return views
    }

    /**
     * 今天和明天的课，外加左上角那个日期块。
     *
     * 明确**不读** [PREFS_NAME] 里的周次/星期偏移：那是 2x2 翻页留下的浏览状态，
     * 4x2 要的是"现在"。
     */
    internal fun loadTwoDayData(context: Context): WidgetTwoDayData {
        ensureAccountContext(context)
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val header = { d: LocalDate ->
            Triple(
                d.dayOfMonth.toString(),
                "${d.monthValue}月",
                "周${weekdayLabel(d.dayOfWeek.value)}",
            )
        }
        val (dayNumber, monthText, dowText) = header(today)

        val term = loadTerm(context)
            ?: return WidgetTwoDayData(
                dayNumber, monthText, dowText, "",
                today = emptyList(), tomorrow = emptyList(),
                todayEmpty = NO_CACHE, tomorrowEmpty = EmptyState(""),
            )

        val weekText = term.startDate
            ?.let { com.xjtu.toolbox.schedule.TermWeeks.weekOf(it.toKx(), today.toKx()) }
            ?.takeIf { it >= 1 }
            ?.let { "第${it}周" }
            .orEmpty()
        val todayCourses = term.coursesOn(today)
        val tomorrowCourses = term.coursesOn(tomorrow)
        // 今天没课：明天有课就报明天几节、几点开始，否则往后找最近的一节
        val todayEmpty = EmptyState(
            title = term.holidays[today] ?: "今天没课",
            detail = tomorrowCourses.firstOrNull()
                ?.let { "明天 ${tomorrowCourses.size} 节，${formatClock(it.startMinute)} 开始" }
                ?: term.nextClassText(from = today.plusDays(2), today),
        )
        // 明天没课：今天那栏已经报过下一节就不重复
        val tomorrowEmpty = EmptyState(
            title = term.holidays[tomorrow] ?: "明天没课",
            detail = when {
                todayCourses.isEmpty() -> if (tomorrow in term.holidays) "放假" else ""
                else -> term.nextClassText(from = tomorrow.plusDays(1), today)
            },
        )

        return WidgetTwoDayData(
            dayNumber = dayNumber,
            monthText = monthText,
            dowText = dowText,
            weekText = weekText,
            today = todayCourses,
            tomorrow = tomorrowCourses,
            todayEmpty = todayEmpty,
            tomorrowEmpty = tomorrowEmpty,
        )
    }

    /** 往后最多找几天的下一节课。 */
    private const val LOOKAHEAD_DAYS = 14L

    /**
     * 一次读好的本学期课表（教务课 + 自建日程）、开学日期和节假日，按日期取课。
     * 以前每取一天都要查一次 Room，往后找下一节课时就是十几次。
     */
    private class TermSchedule(
        val all: List<CourseItem>,
        val startDate: LocalDate?,
        val holidays: Map<LocalDate, String>,
    ) {
        // 按整学期的课分配颜色，和课表页一样，避开撞色的顺延结果才对得上
        private val colors = courseColorMap(all.map { it.courseName })

        /** 某一天的课，按开始时间排。节假日只滤掉教务的课，自建日程照常显示。 */
        fun coursesOn(date: LocalDate, now: LocalDateTime = LocalDateTime.now()): List<WidgetCourse> {
            val isHoliday = date in holidays
            // 没有开学日期就算不出周次；这时按星期给出全部同星期的课，总好过一片空白
            val week = startDate?.let { com.xjtu.toolbox.schedule.TermWeeks.weekOf(it.toKx(), date.toKx()) }
            return all
                .filter { it.dayOfWeek == date.dayOfWeek.value }
                .filter { !isHoliday || it.isUserCreated }
                .filter { week == null || it.isInWeek(week) }
                .toWidgetCourses(date, colors, now)
        }

        /** 从 [from]（含）起两周内最近一节还没上完的课，写成「下一节 10/8 周四 08:00 高等数学」。 */
        fun nextClassText(from: LocalDate, today: LocalDate): String {
            val (date, course) = (0 until LOOKAHEAD_DAYS)
                .map { from.plusDays(it) }
                .firstNotNullOfOrNull { d -> coursesOn(d).firstOrNull { !it.done }?.let { d to it } }
                ?: return "近两周都没有课"
            val day = when (date) {
                today -> "今天"
                today.plusDays(1) -> "明天"
                else -> "${date.monthValue}/${date.dayOfMonth} 周${weekdayLabel(date.dayOfWeek.value)}"
            }
            return "下一节 $day ${formatClock(course.startMinute)} ${course.name}"
        }
    }

    private fun loadTerm(context: Context): TermSchedule? {
        val cache = DataCache(context)
        val termCode = resolveTermCode(context, cache) ?: return null
        return TermSchedule(allCoursesOf(context, cache, termCode), readStartDate(cache, termCode), widgetHolidays(context))
    }

    private fun formatClock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /**
     * [date] 那天的条目转成卡片条目：按那天的作息算真实起止（自建日程用它自己的钟点），按开始时间排。
     * 之前的日子全算上完，今天看下课时刻。
     */
    private fun List<CourseItem>.toWidgetCourses(
        date: LocalDate,
        colors: Map<String, androidx.compose.ui.graphics.Color>,
        now: LocalDateTime,
    ): List<WidgetCourse> {
        val summer = XjtuTime.isSummerTime(date.monthValue)
        val today = now.toLocalDate()
        val nowMinute = now.hour * 60 + now.minute
        return map { c ->
            val (start, end) = c.clockMinutes(summer)
            WidgetCourse(
                name = c.courseName,
                location = c.location,
                startMinute = start,
                endMinute = end,
                color = colors.colorOf(c.courseName).toArgb(),
                done = date < today || (date == today && end <= nowMinute),
            )
        }.sortedWith(compareBy({ it.startMinute }, { it.endMinute }))
    }

    private fun todayCourses(context: Context): List<WidgetCourse> =
        loadTerm(context)?.coursesOn(LocalDate.now()).orEmpty()

    /**
     * 约下一次刷新：今天下一个上课或下课时刻，今天没有了就约到明天零点。系统自带的定时刷新半小时起步、
     * 省电时还会推迟，全靠它的话下课标记、「正在进行」、跨天都会滞后。闹钟能拉起已经退出的进程；
     * 不用精确闹钟（要用户单独授权），系统允许最多晚 10 分钟。
     */
    private fun scheduleNextRefresh(context: Context, size: WidgetSize) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val nowMinute = now.hour * 60 + now.minute
        val at = todayCourses(context)
            .flatMap { listOf(it.startMinute, it.endMinute) }
            .filter { it > nowMinute }
            .minOrNull()
            // 结束在 24:00 的条目落到明天零点，和跨天刷新是同一次
            ?.let { today.atStartOfDay().plusMinutes(it.toLong()) }
            ?: today.plusDays(1).atStartOfDay()
        val millis = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java)
            ?.setWindow(AlarmManager.RTC, millis, 10 * 60_000L, refreshAlarmIntent(context, size))
    }

    internal fun cancelScheduledRefresh(context: Context, size: WidgetSize) {
        context.getSystemService(AlarmManager::class.java)?.cancel(refreshAlarmIntent(context, size))
    }

    private fun refreshAlarmIntent(context: Context, size: WidgetSize): PendingIntent {
        val provider = when (size) {
            WidgetSize.SMALL -> ScheduleWidget2x2Provider::class.java
            WidgetSize.LARGE -> ScheduleWidget4x2Provider::class.java
        }
        return buildActionPendingIntent(context, REFRESH_ALARM_CODE + size.ordinal, ACTION_REFRESH, provider)
    }

    private fun allCoursesOf(context: Context, cache: DataCache, termCode: String): List<CourseItem> {
        val apiCourses = ScheduleCache.readCourses(cache, termCode).orEmpty()
        val customCourses = runCatching {
            runBlocking {
                AppDatabase.getInstance(context)
                    .customCourseDao()
                    .getByTerm(com.xjtu.toolbox.account.AccountContext.activeAccountId ?: "", termCode)
                    .map { it.toCourseItem() }
            }
        }.getOrDefault(emptyList())
        return apiCourses + customCourses
    }

    private fun readStartDate(cache: DataCache, termCode: String): LocalDate? = ScheduleCache.readStartDate(cache, termCode)

    /**
     * Widget 进程入口可能未经 MainActivity，AccountContext 未初始化；
     * 从 AccountStore 恢复激活账号，保证 DataCache/Room 命名空间正确。
     */
    private fun ensureAccountContext(context: Context) {
        if (com.xjtu.toolbox.account.AccountContext.activeAccountId == null) {
            runCatching {
                com.xjtu.toolbox.account.AccountStore(context).activeAccountId()?.let {
                    com.xjtu.toolbox.account.AccountContext.activeAccountId = it
                }
            }
        }
    }

    internal fun loadScheduleData(context: Context): WidgetScheduleData {
        ensureAccountContext(context)
        dropStaleBrowseState(context)
        val nowAt = LocalDateTime.now()
        val nowDate = nowAt.toLocalDate()
        val todayDow = nowDate.dayOfWeek.value

        val cache = DataCache(context)
        val termCode = resolveTermCode(context, cache)
            ?: return WidgetScheduleData(
                weekText = "",
                dayText = "周${weekdayLabel(todayDow)}",
                courses = emptyList(),
                empty = NO_CACHE,
            )

        val allCourses = allCoursesOf(context, cache, termCode)
        // 按整学期的课分配颜色，和课表页一样，避开撞色的顺延结果才对得上
        val colors = courseColorMap(allCourses.map { it.courseName })

        val startDate = ScheduleCache.readStartDate(cache, termCode)

        val baseWeek = startDate?.let {
            com.xjtu.toolbox.schedule.TermWeeks.weekOf(it.toKx(), nowDate.toKx())
        }

        val maxWeek = ScheduleCache.totalWeeks(cache, termCode, allCourses)
            .takeIf { it > 0 } ?: com.xjtu.toolbox.schedule.TermWeeks.DEFAULT_TOTAL_WEEKS
        val firstTeachWeek = allCourses
            .asSequence()
            .flatMap { course ->
                course.weekBits.asSequence().mapIndexedNotNull { index, bit ->
                    if (bit == '1') index + 1 else null
                }
            }
            .minOrNull()
        val displayBaseWeek = when {
            baseWeek == null -> null
            baseWeek <= 0 -> 1
            firstTeachWeek != null && baseWeek in 1..maxWeek && baseWeek < firstTeachWeek -> firstTeachWeek
            else -> baseWeek.coerceIn(1, maxWeek)
        }
        val weekOffset = getWeekOffset(context)
        val selectedDayOfWeek = getSelectedDayOfWeek(context, todayDow)
        val effectiveWeek = when {
            displayBaseWeek != null -> (displayBaseWeek + weekOffset).coerceIn(1, maxWeek)
            else -> (1 + weekOffset).coerceIn(1, maxWeek)
        }
        val shouldFilterByWeek = baseWeek != null || weekOffset != 0

        val selectedDate = if (startDate != null) {
            com.xjtu.toolbox.schedule.TermWeeks.dateOf(startDate.toKx(), effectiveWeek, selectedDayOfWeek).toJavaTime()
        } else {
            val relativeWeekDelta = effectiveWeek - (displayBaseWeek ?: 1)
            nowDate.plusDays(relativeWeekDelta * 7L + (selectedDayOfWeek - todayDow).toLong())
        }
        val dayText = "周${weekdayLabel(selectedDayOfWeek)}"

        val holidayDates = widgetHolidays(context)
        val isHoliday = holidayDates.containsKey(selectedDate)

        val todayCourses = allCourses
            .filter { !isHoliday || it.isUserCreated }
            .filter { it.dayOfWeek == selectedDayOfWeek }
            .filter { if (shouldFilterByWeek) it.isInWeek(effectiveWeek) else true }
            .toWidgetCourses(selectedDate, colors, nowAt)

        val weekText = if (baseWeek != null) {
            if (baseWeek <= 0) "未开学" else "第${effectiveWeek}周"
        } else {
            if (weekOffset == 0) "周次未同步" else "第${effectiveWeek}周"
        }

        // 没课：放假就写节日名；下一节从所看那天的次日找，看的是过去的日子就从今天找
        val term = TermSchedule(allCourses, startDate, holidayDates)
        return WidgetScheduleData(
            weekText = weekText,
            dayText = dayText,
            courses = todayCourses,
            empty = EmptyState(
                title = holidayDates[selectedDate] ?: "${dayText}没课",
                detail = term.nextClassText(from = maxOf(selectedDate.plusDays(1), nowDate), nowDate),
            ),
        )
    }

    private fun getWeekOffset(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_WEEK_OFFSET, 0)
            .coerceIn(MIN_WEEK_OFFSET, MAX_WEEK_OFFSET)
    }

    private fun getSelectedDayOfWeek(context: Context, todayDow: Int): Int {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DAY_OF_WEEK, todayDow)
        return saved.coerceIn(1, 7)
    }

    internal fun adjustSelectedDayOfWeek(context: Context, delta: Int) {
        dropStaleBrowseState(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val todayDow = LocalDate.now().dayOfWeek.value
        val currentDay = prefs.getInt(KEY_DAY_OF_WEEK, todayDow).coerceIn(1, 7)
        val currentOffset = prefs.getInt(KEY_WEEK_OFFSET, 0).coerceIn(MIN_WEEK_OFFSET, MAX_WEEK_OFFSET)

        var nextDay = currentDay + delta
        var nextOffset = currentOffset

        while (nextDay < 1) {
            nextDay += 7
            nextOffset = (nextOffset - 1).coerceIn(MIN_WEEK_OFFSET, MAX_WEEK_OFFSET)
        }
        while (nextDay > 7) {
            nextDay -= 7
            nextOffset = (nextOffset + 1).coerceIn(MIN_WEEK_OFFSET, MAX_WEEK_OFFSET)
        }

        prefs.edit()
            .putInt(KEY_DAY_OF_WEEK, nextDay)
            .putInt(KEY_WEEK_OFFSET, nextOffset)
            .apply()
    }

    private fun weekdayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "一"
        2 -> "二"
        3 -> "三"
        4 -> "四"
        5 -> "五"
        6 -> "六"
        7 -> "日"
        else -> "?"
    }

    private fun resolveTermCode(context: Context, cache: DataCache): String? {
        // 桌面上永远显示本学期：不能读 schedule_last_term（用户上一次翻到的学期），
        // 翻一眼去年的课表，桌面小组件就变成去年的了。见 ScheduleCache.readCurrentTerm。
        val termFromLast = ScheduleCache.readCurrentTerm(cache)
        if (!termFromLast.isNullOrBlank() && hasScheduleCache(cache, termFromLast)) {
            return termFromLast
        }

        val termFromList = ScheduleCache.readTermList(cache).firstOrNull { hasScheduleCache(cache, it) }
        if (!termFromList.isNullOrBlank()) return termFromList

        if (!termFromLast.isNullOrBlank()) return termFromLast

        // Fallback：扫描当前账号命名空间下的缓存目录（迁移后旧 data_cache/ 已被 rename 走）
        val cacheDir = File(context.cacheDir, "data_cache${com.xjtu.toolbox.account.AccountContext.safeSuffix()}")
        val scheduleFiles = cacheDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith("schedule_") &&
                    !it.name.startsWith("schedule_optimized_") &&
                    it.name.endsWith(".json") &&
                    it.name != "schedule_term_list.json"
            }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        for (file in scheduleFiles) {
            val termPart = file.name.removePrefix("schedule_").removeSuffix(".json")
            if (termPart.isBlank()) continue
            val hyphenTerm = termPart.replace('_', '-')
            val candidates = listOf(hyphenTerm, termPart).distinct()
            val matched = candidates.firstOrNull { candidate ->
                val scheduleJson = cache.get("schedule_$candidate", Long.MAX_VALUE)
                !scheduleJson.isNullOrBlank()
            }
            if (!matched.isNullOrBlank()) return matched
        }
        return null
    }

    private fun hasScheduleCache(cache: DataCache, termCode: String): Boolean =
        termCode.isNotBlank() && !ScheduleCache.readCourses(cache, termCode).isNullOrEmpty()
}

/**
 * 两个尺寸的小组件除了尺寸和 provider 类，逻辑完全一样。
 * 渲染要读缓存目录、查 Room，不能占着接收广播的主线程：goAsync 之后放到 IO 线程做。
 */
abstract class ScheduleWidgetProviderBase(
    private val size: WidgetSize,
    private val self: Class<out AppWidgetProvider>,
) : AppWidgetProvider() {

    private fun updateAsync(context: Context, ids: () -> IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                ScheduleWidgetUpdater.updateSpecific(app, AppWidgetManager.getInstance(app), ids(), size)
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAsync(context) { appWidgetIds }
    }

    override fun onDisabled(context: Context) {
        ScheduleWidgetUpdater.cancelScheduledRefresh(context, size)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        if (ScheduleWidgetUpdater.handleAction(context, intent?.action)) return
        if (intent?.action == ScheduleWidgetUpdater.ACTION_REFRESH) {
            if (intent.getBooleanExtra(ScheduleWidgetUpdater.EXTRA_RESET_TO_TODAY, false)) {
                ScheduleWidgetUpdater.resetBrowseSelectionToToday(context)
            }
            updateAsync(context) {
                AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, self))
            }
        }
    }
}

class ScheduleWidget2x2Provider : ScheduleWidgetProviderBase(WidgetSize.SMALL, ScheduleWidget2x2Provider::class.java)

class ScheduleWidget4x2Provider : ScheduleWidgetProviderBase(WidgetSize.LARGE, ScheduleWidget4x2Provider::class.java)
