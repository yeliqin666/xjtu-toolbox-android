package com.xjtu.toolbox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.google.gson.Gson
import com.xjtu.toolbox.BottomTab
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.AppDatabase
import com.xjtu.toolbox.util.DataCache
import com.xjtu.toolbox.util.XjtuTime
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking

enum class WidgetSize { SMALL, LARGE }

internal data class WidgetCourse(
    val name: String,
    val location: String,
    val startSection: Int,
    val endSection: Int
)

internal data class WidgetScheduleData(
    val weekText: String,
    val dayText: String,
    val statusText: String,
    val updateText: String,
    val courses: List<WidgetCourse>,
    val hasCache: Boolean
)

object ScheduleWidgetUpdater {
    const val ACTION_REFRESH = "com.xjtu.toolbox.widget.ACTION_REFRESH_SCHEDULE_WIDGET"
    const val EXTRA_RESET_TO_TODAY = "com.xjtu.toolbox.widget.EXTRA_RESET_TO_TODAY"
    const val ACTION_WEEK_PREV = "com.xjtu.toolbox.widget.ACTION_SCHEDULE_WIDGET_WEEK_PREV"
    const val ACTION_WEEK_NEXT = "com.xjtu.toolbox.widget.ACTION_SCHEDULE_WIDGET_WEEK_NEXT"
    const val ACTION_DAY_PREV = "com.xjtu.toolbox.widget.ACTION_SCHEDULE_WIDGET_DAY_PREV"
    const val ACTION_DAY_NEXT = "com.xjtu.toolbox.widget.ACTION_SCHEDULE_WIDGET_DAY_NEXT"

    private const val PREFS_NAME = "schedule_widget_prefs"
    private const val KEY_WEEK_OFFSET = "week_offset"
    private const val KEY_DAY_OF_WEEK = "day_of_week"
    private const val MIN_WEEK_OFFSET = -30
    private const val MAX_WEEK_OFFSET = 30

    private val gson = Gson()

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
            ACTION_WEEK_PREV -> {
                adjustWeekOffset(context, -1)
                requestUpdate(context, resetToToday = false)
                return true
            }

            ACTION_WEEK_NEXT -> {
                adjustWeekOffset(context, 1)
                requestUpdate(context, resetToToday = false)
                return true
            }

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
        val todayDow = LocalDate.now().dayOfWeek.value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_WEEK_OFFSET, 0)
            .putInt(KEY_DAY_OF_WEEK, todayDow)
            .apply()
    }

    fun updateSpecific(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        size: WidgetSize
    ) {
        if (appWidgetIds.isEmpty()) return
        val data = loadScheduleData(context)
        appWidgetIds.forEach { widgetId ->
            val views = when (size) {
                WidgetSize.SMALL -> buildSmallViews(context, data, widgetId)
                WidgetSize.LARGE -> buildLargeViews(context, data, widgetId)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_course_list)
    }

    private fun buildLaunchPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, Routes.MAIN)
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

    private fun buildCourseListAdapterIntent(context: Context, appWidgetId: Int, size: WidgetSize): Intent {
        return Intent(context, ScheduleWidgetRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("widget_size", size.name)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
    }

    private fun bindCommonViews(
        context: Context,
        data: WidgetScheduleData,
        appWidgetId: Int,
        size: WidgetSize,
        providerClass: Class<out AppWidgetProvider>,
        rootRequestCode: Int,
        weekRequestOffset: Int,
        dayRequestOffset: Int,
        listTemplateBase: Int,
        layoutRes: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutRes)
        views.setTextViewText(R.id.widget_title, "日程")
        views.setTextViewText(R.id.widget_week, data.weekText)
        views.setTextViewText(R.id.widget_day_text, data.dayText)
        views.setTextViewText(R.id.widget_status, data.statusText)
        views.setTextViewText(R.id.widget_update, data.updateText)
        views.setOnClickPendingIntent(R.id.widget_root, buildLaunchPendingIntent(context, rootRequestCode))
        views.setOnClickPendingIntent(
            R.id.widget_week_prev,
            buildActionPendingIntent(context, weekRequestOffset + 1, ACTION_WEEK_PREV, providerClass)
        )
        views.setOnClickPendingIntent(
            R.id.widget_week_next,
            buildActionPendingIntent(context, weekRequestOffset + 2, ACTION_WEEK_NEXT, providerClass)
        )
        views.setOnClickPendingIntent(
            R.id.widget_day_prev,
            buildActionPendingIntent(context, dayRequestOffset + 1, ACTION_DAY_PREV, providerClass)
        )
        views.setOnClickPendingIntent(
            R.id.widget_day_next,
            buildActionPendingIntent(context, dayRequestOffset + 2, ACTION_DAY_NEXT, providerClass)
        )
        views.setRemoteAdapter(
            R.id.widget_course_list,
            buildCourseListAdapterIntent(context, appWidgetId, size)
        )
        views.setEmptyView(R.id.widget_course_list, R.id.widget_empty)
        views.setPendingIntentTemplate(
            R.id.widget_course_list,
            buildLaunchPendingIntent(context, listTemplateBase + appWidgetId)
        )

        if (!data.hasCache) {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, "暂无日程缓存\n请先打开日程页同步")
            return views
        }

        if (data.courses.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, "所选日期无日程")
            return views
        }

        views.setViewVisibility(R.id.widget_empty, View.GONE)
        return views
    }

    private fun buildSmallViews(context: Context, data: WidgetScheduleData, appWidgetId: Int): RemoteViews {
        return bindCommonViews(
            context = context,
            data = data,
            appWidgetId = appWidgetId,
            size = WidgetSize.SMALL,
            providerClass = ScheduleWidget2x2Provider::class.java,
            rootRequestCode = 1001,
            weekRequestOffset = 1100,
            dayRequestOffset = 1200,
            listTemplateBase = 3000,
            layoutRes = R.layout.widget_schedule_2x2
        )
    }

    private fun buildLargeViews(context: Context, data: WidgetScheduleData, appWidgetId: Int): RemoteViews {
        return bindCommonViews(
            context = context,
            data = data,
            appWidgetId = appWidgetId,
            size = WidgetSize.LARGE,
            providerClass = ScheduleWidget4x2Provider::class.java,
            rootRequestCode = 1002,
            weekRequestOffset = 2100,
            dayRequestOffset = 2200,
            listTemplateBase = 4000,
            layoutRes = R.layout.widget_schedule_4x2
        )
    }

    internal fun loadScheduleData(context: Context): WidgetScheduleData {
        val now = LocalTime.now()
        val nowDate = LocalDate.now()
        val todayDow = nowDate.dayOfWeek.value
        val updateText = "更新 ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}"

        val cache = DataCache(context)
        val termCode = resolveTermCode(context, cache)
            ?: return WidgetScheduleData(
                weekText = "未同步",
                dayText = "日期未同步",
                statusText = "请先进入日程页",
                updateText = updateText,
                courses = emptyList(),
                hasCache = false
            )

        val scheduleJson = cache.get("schedule_$termCode", Long.MAX_VALUE)
        val apiCourses = if (scheduleJson != null) {
            runCatching { gson.fromJson(scheduleJson, Array<CourseItem>::class.java)?.toList().orEmpty() }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val customCourses = runCatching {
            runBlocking {
                AppDatabase.getInstance(context)
                    .customCourseDao()
                    .getByTerm(termCode)
                    .map { it.toCourseItem() }
            }
        }.getOrDefault(emptyList())

        val allCourses = apiCourses + customCourses

        val startDateRaw = cache.get("start_date_$termCode", Long.MAX_VALUE)
        val startDate = if (!startDateRaw.isNullOrBlank()) {
            runCatching {
                val dateStr = gson.fromJson(startDateRaw, String::class.java)
                LocalDate.parse(dateStr)
            }.getOrNull()
        } else {
            null
        }

        val baseWeek = startDate?.let {
            val days = java.time.temporal.ChronoUnit.DAYS.between(it, nowDate)
            ((days / 7) + 1).toInt()
        }

        val maxWeek = allCourses.maxOfOrNull { it.weekBits.length }?.coerceAtLeast(1) ?: 20
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
        val weekAdjusted = displayBaseWeek != null && effectiveWeek != displayBaseWeek
        val notStartedYet = baseWeek != null &&
            firstTeachWeek != null &&
            baseWeek in 1..maxWeek &&
            baseWeek < firstTeachWeek &&
            weekOffset == 0

        val shouldFilterByWeek = baseWeek != null || weekOffset != 0

        val selectedDate = if (startDate != null) {
            startDate.plusDays(((effectiveWeek - 1) * 7L) + (selectedDayOfWeek - 1L))
        } else {
            val relativeWeekDelta = effectiveWeek - (displayBaseWeek ?: 1)
            nowDate.plusDays(relativeWeekDelta * 7L + (selectedDayOfWeek - todayDow).toLong())
        }
        val dayText = "${selectedDate.format(DateTimeFormatter.ofPattern("MM-dd"))} 周${weekdayLabel(selectedDayOfWeek)}"

        val todayCourses = allCourses
            .asSequence()
            .filter { it.dayOfWeek == selectedDayOfWeek }
            .filter { if (shouldFilterByWeek) it.isInWeek(effectiveWeek) else true }
            .sortedBy { it.startSection }
            .map {
                WidgetCourse(
                    name = it.courseName,
                    location = it.location,
                    startSection = it.startSection,
                    endSection = it.endSection
                )
            }
            .toList()

        val isSelectedToday = selectedDate == nowDate

        val currentCourse = if (!weekAdjusted && isSelectedToday) {
            todayCourses.firstOrNull { course ->
                val start = XjtuTime.getClassTime(course.startSection)?.start
                val end = XjtuTime.getClassTime(course.endSection)?.end
                start != null && end != null && now >= start && now <= end
            }
        } else {
            null
        }
        val nextCourse = if (!weekAdjusted && isSelectedToday) {
            todayCourses.firstOrNull { course ->
                val start = XjtuTime.getClassTime(course.startSection)?.start
                start != null && now < start
            }
        } else {
            null
        }

        val status = when {
            todayCourses.isEmpty() -> "周${weekdayLabel(selectedDayOfWeek)}没有日程"
            notStartedYet -> "尚未开课，已显示第${effectiveWeek}周"
            !isSelectedToday -> "所选日共 ${todayCourses.size} 项安排"
            weekAdjusted -> "本周今日共 ${todayCourses.size} 项安排"
            currentCourse != null -> "正在进行：${currentCourse.name}"
            nextCourse != null -> "下一项：${XjtuTime.getClassStartStr(nextCourse.startSection)} ${nextCourse.name}"
            else -> "今日日程已结束"
        }

        val weekText = if (baseWeek != null) {
            if (baseWeek <= 0) "未开学" else "第${effectiveWeek}周"
        } else {
            if (weekOffset == 0) "周次未同步" else "第${effectiveWeek}周"
        }

        return WidgetScheduleData(
            weekText = weekText,
            dayText = dayText,
            statusText = status,
            updateText = updateText,
            courses = todayCourses,
            hasCache = true
        )
    }

    private fun getWeekOffset(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_WEEK_OFFSET, 0)
            .coerceIn(MIN_WEEK_OFFSET, MAX_WEEK_OFFSET)
    }

    internal fun adjustWeekOffset(context: Context, delta: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_WEEK_OFFSET, 0)
        val target = (current + delta).coerceIn(MIN_WEEK_OFFSET, MAX_WEEK_OFFSET)
        prefs.edit().putInt(KEY_WEEK_OFFSET, target).apply()
    }

    private fun getSelectedDayOfWeek(context: Context, todayDow: Int): Int {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DAY_OF_WEEK, todayDow)
        return saved.coerceIn(1, 7)
    }

    internal fun adjustSelectedDayOfWeek(context: Context, delta: Int) {
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
        val lastTermJson = cache.get("schedule_last_term", Long.MAX_VALUE)
        val termFromLast = if (!lastTermJson.isNullOrBlank()) {
            runCatching { gson.fromJson(lastTermJson, String::class.java) }.getOrNull()
        } else {
            null
        }
        if (!termFromLast.isNullOrBlank() && hasScheduleCache(cache, termFromLast)) {
            return termFromLast
        }

        val termListJson = cache.get("schedule_term_list", Long.MAX_VALUE)
        val termFromList = if (termListJson != null) {
            runCatching { gson.fromJson(termListJson, Array<String>::class.java)?.toList().orEmpty() }
                .getOrDefault(emptyList())
                .firstOrNull { hasScheduleCache(cache, it) }
        } else {
            null
        }
        if (!termFromList.isNullOrBlank()) return termFromList

        if (!termFromLast.isNullOrBlank()) return termFromLast

        val cacheDir = File(context.cacheDir, "data_cache")
        val scheduleFiles = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("schedule_") && it.name.endsWith(".json") && it.name != "schedule_term_list.json" }
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

    private fun hasScheduleCache(cache: DataCache, termCode: String): Boolean {
        if (termCode.isBlank()) return false
        val scheduleJson = cache.get("schedule_$termCode", Long.MAX_VALUE)
        if (scheduleJson.isNullOrBlank()) return false
        return runCatching {
            gson.fromJson(scheduleJson, Array<CourseItem>::class.java)?.isNotEmpty() == true
        }.getOrDefault(false)
    }
}

class ScheduleWidget2x2Provider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ScheduleWidgetUpdater.updateSpecific(context, appWidgetManager, appWidgetIds, WidgetSize.SMALL)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        if (ScheduleWidgetUpdater.handleAction(context, intent?.action)) return
        if (intent?.action == ScheduleWidgetUpdater.ACTION_REFRESH) {
            if (intent.getBooleanExtra(ScheduleWidgetUpdater.EXTRA_RESET_TO_TODAY, false)) {
                ScheduleWidgetUpdater.resetBrowseSelectionToToday(context)
            }
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidget2x2Provider::class.java))
            ScheduleWidgetUpdater.updateSpecific(context, manager, ids, WidgetSize.SMALL)
        }
    }
}

class ScheduleWidget4x2Provider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ScheduleWidgetUpdater.updateSpecific(context, appWidgetManager, appWidgetIds, WidgetSize.LARGE)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        if (ScheduleWidgetUpdater.handleAction(context, intent?.action)) return
        if (intent?.action == ScheduleWidgetUpdater.ACTION_REFRESH) {
            if (intent.getBooleanExtra(ScheduleWidgetUpdater.EXTRA_RESET_TO_TODAY, false)) {
                ScheduleWidgetUpdater.resetBrowseSelectionToToday(context)
            }
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidget4x2Provider::class.java))
            ScheduleWidgetUpdater.updateSpecific(context, manager, ids, WidgetSize.LARGE)
        }
    }
}