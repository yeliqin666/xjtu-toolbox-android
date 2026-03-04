package com.xjtu.toolbox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.google.gson.Gson
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

private data class WidgetCourse(
    val name: String,
    val location: String,
    val startSection: Int,
    val endSection: Int
)

private data class WidgetScheduleData(
    val weekText: String,
    val dayText: String,
    val statusText: String,
    val updateText: String,
    val courses: List<WidgetCourse>,
    val hasCache: Boolean
)

object ScheduleWidgetUpdater {
    const val ACTION_REFRESH = "com.xjtu.toolbox.widget.ACTION_REFRESH_SCHEDULE_WIDGET"
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

    fun requestUpdate(context: Context) {
        context.sendBroadcast(
            Intent(context, ScheduleWidget2x2Provider::class.java).apply { action = ACTION_REFRESH }
        )
        context.sendBroadcast(
            Intent(context, ScheduleWidget4x2Provider::class.java).apply { action = ACTION_REFRESH }
        )
    }

    fun handleWeekAction(context: Context, action: String?): Boolean {
        val todayDow = LocalDate.now().dayOfWeek.value
        when (action) {
            ACTION_WEEK_PREV -> {
                adjustWeekOffset(context, -1)
                requestUpdate(context)
                return true
            }

            ACTION_WEEK_NEXT -> {
                adjustWeekOffset(context, 1)
                requestUpdate(context)
                return true
            }

            ACTION_DAY_PREV -> {
                adjustSelectedDayOfWeek(context, -1, todayDow)
                requestUpdate(context)
                return true
            }

            ACTION_DAY_NEXT -> {
                adjustSelectedDayOfWeek(context, 1, todayDow)
                requestUpdate(context)
                return true
            }
        }
        return false
    }

    fun updateSpecific(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        size: WidgetSize
    ) {
        if (appWidgetIds.isEmpty()) return
        val data = loadTodaySchedule(context)
        appWidgetIds.forEach { widgetId ->
            val views = when (size) {
                WidgetSize.SMALL -> buildSmallViews(context, data)
                WidgetSize.LARGE -> buildLargeViews(context, data)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun buildLaunchPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, Routes.SCHEDULE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildWeekActionPendingIntent(
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

    private fun buildSmallViews(context: Context, data: WidgetScheduleData): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_2x2)
        views.setTextViewText(R.id.widget_title, "课表")
        views.setTextViewText(R.id.widget_week, data.weekText)
        views.setTextViewText(R.id.widget_day_text, data.dayText)
        views.setTextViewText(R.id.widget_status, data.statusText)
        views.setTextViewText(R.id.widget_update, data.updateText)
        views.setOnClickPendingIntent(R.id.widget_root, buildLaunchPendingIntent(context, 1001))
        views.setOnClickPendingIntent(
            R.id.widget_week_prev,
            buildWeekActionPendingIntent(context, 1101, ACTION_WEEK_PREV, ScheduleWidget2x2Provider::class.java)
        )
        views.setOnClickPendingIntent(
            R.id.widget_week_next,
            buildWeekActionPendingIntent(context, 1102, ACTION_WEEK_NEXT, ScheduleWidget2x2Provider::class.java)
        )
        views.setOnClickPendingIntent(
            R.id.widget_day_prev,
            buildWeekActionPendingIntent(context, 1103, ACTION_DAY_PREV, ScheduleWidget2x2Provider::class.java)
        )
        views.setOnClickPendingIntent(
            R.id.widget_day_next,
            buildWeekActionPendingIntent(context, 1104, ACTION_DAY_NEXT, ScheduleWidget2x2Provider::class.java)
        )

        if (!data.hasCache) {
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, "暂无课表缓存\n请先打开课表页同步")
            hideSmallRows(views)
            return views
        }

        if (data.courses.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, "所选日期无课程")
            hideSmallRows(views)
            return views
        }

        views.setViewVisibility(R.id.widget_empty, android.view.View.GONE)
        bindSmallRow(views, 0, data.courses.getOrNull(0))
        bindSmallRow(views, 1, data.courses.getOrNull(1))
        val hiddenCount = (data.courses.size - 2).coerceAtLeast(0)
        if (hiddenCount > 0) {
            views.setTextViewText(R.id.widget_status, "今日共${data.courses.size}节，另有${hiddenCount}节")
        }
        return views
    }

    private fun buildLargeViews(context: Context, data: WidgetScheduleData): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_4x2)
        views.setTextViewText(R.id.widget_title, "课表")
        views.setTextViewText(R.id.widget_week, data.weekText)
        views.setTextViewText(R.id.widget_day_text, data.dayText)
        views.setTextViewText(R.id.widget_status, data.statusText)
        views.setTextViewText(R.id.widget_update, data.updateText)
        views.setOnClickPendingIntent(R.id.widget_root, buildLaunchPendingIntent(context, 1002))
        views.setOnClickPendingIntent(
            R.id.widget_week_prev,
            buildWeekActionPendingIntent(context, 1201, ACTION_WEEK_PREV, ScheduleWidget4x2Provider::class.java)
        )
        views.setOnClickPendingIntent(
            R.id.widget_week_next,
            buildWeekActionPendingIntent(context, 1202, ACTION_WEEK_NEXT, ScheduleWidget4x2Provider::class.java)
        )
        views.setOnClickPendingIntent(
            R.id.widget_day_prev,
            buildWeekActionPendingIntent(context, 1203, ACTION_DAY_PREV, ScheduleWidget4x2Provider::class.java)
        )
        views.setOnClickPendingIntent(
            R.id.widget_day_next,
            buildWeekActionPendingIntent(context, 1204, ACTION_DAY_NEXT, ScheduleWidget4x2Provider::class.java)
        )

        if (!data.hasCache) {
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, "暂无课表缓存，请先打开课表页同步")
            hideLargeRows(views)
            return views
        }

        if (data.courses.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, "所选日期无课程")
            hideLargeRows(views)
            return views
        }

        views.setViewVisibility(R.id.widget_empty, android.view.View.GONE)
        bindLargeRow(views, 0, data.courses.getOrNull(0))
        bindLargeRow(views, 1, data.courses.getOrNull(1))
        bindLargeRow(views, 2, data.courses.getOrNull(2))
        bindLargeRow(views, 3, data.courses.getOrNull(3))
        bindLargeRow(views, 4, data.courses.getOrNull(4))
        val hiddenCount = (data.courses.size - 5).coerceAtLeast(0)
        if (hiddenCount > 0) {
            views.setTextViewText(R.id.widget_status, "今日共${data.courses.size}节，另有${hiddenCount}节")
        }
        return views
    }

    private fun hideSmallRows(views: RemoteViews) {
        views.setViewVisibility(R.id.row_course_1, android.view.View.GONE)
        views.setViewVisibility(R.id.row_course_2, android.view.View.GONE)
    }

    private fun bindSmallRow(views: RemoteViews, index: Int, course: WidgetCourse?) {
        val rowId = when (index) {
            0 -> R.id.row_course_1
            else -> R.id.row_course_2
        }
        val timeId = when (index) {
            0 -> R.id.widget_time_1
            else -> R.id.widget_time_2
        }
        val nameId = when (index) {
            0 -> R.id.widget_name_1
            else -> R.id.widget_name_2
        }
        val locId = when (index) {
            0 -> R.id.widget_location_1
            else -> R.id.widget_location_2
        }

        if (course == null) {
            views.setViewVisibility(rowId, android.view.View.GONE)
            return
        }

        views.setViewVisibility(rowId, android.view.View.VISIBLE)
        views.setTextViewText(
            timeId,
            "${XjtuTime.getClassStartStr(course.startSection)}-${XjtuTime.getClassEndStr(course.endSection)}"
        )
        views.setTextViewText(nameId, course.name)
        views.setTextViewText(locId, course.location.ifBlank { "地点待定" })
    }

    private fun hideLargeRows(views: RemoteViews) {
        views.setViewVisibility(R.id.row_course_1, android.view.View.GONE)
        views.setViewVisibility(R.id.row_course_2, android.view.View.GONE)
        views.setViewVisibility(R.id.row_course_3, android.view.View.GONE)
        views.setViewVisibility(R.id.row_course_4, android.view.View.GONE)
        views.setViewVisibility(R.id.row_course_5, android.view.View.GONE)
    }

    private fun bindLargeRow(views: RemoteViews, index: Int, course: WidgetCourse?) {
        val rowId = when (index) {
            0 -> R.id.row_course_1
            1 -> R.id.row_course_2
            2 -> R.id.row_course_3
            3 -> R.id.row_course_4
            else -> R.id.row_course_5
        }
        val timeId = when (index) {
            0 -> R.id.widget_time_1
            1 -> R.id.widget_time_2
            2 -> R.id.widget_time_3
            3 -> R.id.widget_time_4
            else -> R.id.widget_time_5
        }
        val nameId = when (index) {
            0 -> R.id.widget_name_1
            1 -> R.id.widget_name_2
            2 -> R.id.widget_name_3
            3 -> R.id.widget_name_4
            else -> R.id.widget_name_5
        }
        val locId = when (index) {
            0 -> R.id.widget_location_1
            1 -> R.id.widget_location_2
            2 -> R.id.widget_location_3
            3 -> R.id.widget_location_4
            else -> R.id.widget_location_5
        }

        if (course == null) {
            views.setViewVisibility(rowId, android.view.View.GONE)
            return
        }

        views.setViewVisibility(rowId, android.view.View.VISIBLE)
        views.setTextViewText(
            timeId,
            "${XjtuTime.getClassStartStr(course.startSection)}-${XjtuTime.getClassEndStr(course.endSection)}"
        )
        views.setTextViewText(nameId, course.name)
        views.setTextViewText(locId, course.location.ifBlank { "地点待定" })
    }

    private fun loadTodaySchedule(context: Context): WidgetScheduleData {
        val now = LocalTime.now()
        val nowDate = LocalDate.now()
        val todayDow = nowDate.dayOfWeek.value
        val updateText = "更新 ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}"

        val cache = DataCache(context)
        val termCode = resolveTermCode(context, cache)
            ?: return WidgetScheduleData(
                weekText = "未同步",
                dayText = "日期未同步",
                statusText = "请先进入课表页",
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
        } else null
        val nextCourse = if (!weekAdjusted && isSelectedToday) {
            todayCourses.firstOrNull { course ->
                val start = XjtuTime.getClassTime(course.startSection)?.start
                start != null && now < start
            }
        } else null

        val status = when {
            todayCourses.isEmpty() -> "周${weekdayLabel(selectedDayOfWeek)}没有课程"
            notStartedYet -> "尚未开课，已显示第${effectiveWeek}周"
            !isSelectedToday -> "所选日共 ${todayCourses.size} 节课"
            weekAdjusted -> "本周今日共 ${todayCourses.size} 节课"
            currentCourse != null -> "正在上课：${currentCourse.name}"
            nextCourse != null -> "下一节：${XjtuTime.getClassStartStr(nextCourse.startSection)} ${nextCourse.name}"
            else -> "今日课程已结束"
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

    private fun adjustWeekOffset(context: Context, delta: Int) {
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

    private fun adjustSelectedDayOfWeek(context: Context, delta: Int, todayDow: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_DAY_OF_WEEK, todayDow).coerceIn(1, 7)
        val next = (((current - 1 + delta) % 7 + 7) % 7) + 1
        prefs.edit().putInt(KEY_DAY_OF_WEEK, next).apply()
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
        if (ScheduleWidgetUpdater.handleWeekAction(context, intent?.action)) return
        if (intent?.action == ScheduleWidgetUpdater.ACTION_REFRESH) {
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
        if (ScheduleWidgetUpdater.handleWeekAction(context, intent?.action)) return
        if (intent?.action == ScheduleWidgetUpdater.ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidget4x2Provider::class.java))
            ScheduleWidgetUpdater.updateSpecific(context, manager, ids, WidgetSize.LARGE)
        }
    }
}
