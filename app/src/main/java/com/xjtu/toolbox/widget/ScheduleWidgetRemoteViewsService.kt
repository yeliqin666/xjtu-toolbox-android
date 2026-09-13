package com.xjtu.toolbox.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.xjtu.toolbox.R
import com.xjtu.toolbox.util.XjtuTime
import kotlin.math.abs

class ScheduleWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val size = intent.getStringExtra("widget_size")
            ?.let { runCatching { WidgetSize.valueOf(it) }.getOrNull() }
            ?: WidgetSize.SMALL
        // 4x2 有今天、明天两栏，各挂一个适配器，靠这个 extra 区分。
        val dayOffset = intent.getIntExtra(ScheduleWidgetUpdater.EXTRA_DAY_OFFSET, 0)
        return ScheduleWidgetCourseFactory(applicationContext, size, dayOffset)
    }
}

private class ScheduleWidgetCourseFactory(
    private val context: Context,
    private val widgetSize: WidgetSize,
    /** 0=今天，1=明天。仅 [WidgetSize.LARGE] 使用；2x2 的"哪一天"由翻页状态决定。 */
    private val dayOffset: Int = 0,
) : RemoteViewsService.RemoteViewsFactory {

    /** 2x2 的整块浅色底。 */
    private val toneBackgrounds = intArrayOf(
        R.drawable.bg_schedule_course_item,
        R.drawable.bg_schedule_course_item_2,
        R.drawable.bg_schedule_course_item_3,
        R.drawable.bg_schedule_course_item_4,
        R.drawable.bg_schedule_course_item_5,
        R.drawable.bg_schedule_course_item_6
    )

    /** 4x2 的左侧色条。和上面一一对应，同一门课两种尺寸下颜色一致。 */
    private val accentBars = intArrayOf(
        R.drawable.bg_widget_accent_1,
        R.drawable.bg_widget_accent_2,
        R.drawable.bg_widget_accent_3,
        R.drawable.bg_widget_accent_4,
        R.drawable.bg_widget_accent_5,
        R.drawable.bg_widget_accent_6
    )

    private var courses: List<WidgetCourse> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        courses = when (widgetSize) {
            // 2x2 跟随翻页状态，显示"用户正在看的那天"。
            WidgetSize.SMALL -> ScheduleWidgetUpdater.loadScheduleData(context).courses.take(3)
            // 4x2 是今天 / 明天两栏，每栏 3 条正好填满，与 issue #41 参考图一致。
            WidgetSize.LARGE -> ScheduleWidgetUpdater.loadTwoDayData(context)
                .let { if (dayOffset == 0) it.today else it.tomorrow }
                .take(3)
        }
    }

    override fun onDestroy() {
        courses = emptyList()
    }

    override fun getCount(): Int = courses.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in courses.indices) return null
        val course = courses[position]
        val itemLayout = when (widgetSize) {
            WidgetSize.SMALL -> R.layout.widget_schedule_course_item
            WidgetSize.LARGE -> R.layout.widget_schedule_course_accent_item
        }
        val views = RemoteViews(context.packageName, itemLayout)
        val toneIndex = ((course.name + course.location + course.startSection + course.endSection)
            .hashCode()
            .let { if (it == Int.MIN_VALUE) 0 else abs(it) }) % toneBackgrounds.size
        val timeRange =
            "${XjtuTime.getClassStartStr(course.startSection)}-${XjtuTime.getClassEndStr(course.endSection)}"
        val place = course.location.ifBlank { "地点待定" }
        when (widgetSize) {
            // 2x2：整块浅色底，一行放得下「课名 · 地点」，时间单独一行。
            WidgetSize.SMALL -> {
                views.setInt(
                    R.id.widget_course_item_root,
                    "setBackgroundResource",
                    toneBackgrounds[toneIndex]
                )
                views.setTextViewText(R.id.widget_course_desc, "${course.name} · $place")
                views.setTextViewText(R.id.widget_course_time, timeRange)
            }
            // 4x2：每栏只有半个部件宽，色块底会把字挤没，改成左侧色条；
            // 课名独占一行，时间和地点合并到第二行。
            WidgetSize.LARGE -> {
                views.setInt(
                    R.id.widget_course_accent,
                    "setBackgroundResource",
                    accentBars[toneIndex]
                )
                views.setTextViewText(R.id.widget_course_desc, course.name)
                views.setTextViewText(R.id.widget_course_time, "$timeRange $place")
            }
        }
        views.setOnClickFillInIntent(R.id.widget_course_item_root, Intent())
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
