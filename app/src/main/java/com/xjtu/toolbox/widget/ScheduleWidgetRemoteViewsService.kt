package com.xjtu.toolbox.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.xjtu.toolbox.R

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

    private var courses: List<WidgetCourse> = emptyList()

    /** 4x2 左侧色条，按颜色缓存（#448，见 [stripe]）。 */
    private val stripes = HashMap<Int, Bitmap>()

    /**
     * 4x2 的色条直接画成该颜色的小图交给桌面，不再对共用的白色色条图 `setColorFilter`。
     *
     * 同一张 drawable 在桌面进程里各行共用一份状态，HyperOS 桌面上滤镜会串：所有行的色条
     * 都成了同一种颜色，长按部件、从部件进应用重绘一次又换成另一行的颜色（#448）。
     * 每行一张自己的位图就没有可串的状态了。尺寸取色条实际的 3dp 宽、两行字高，
     * `fitXY` 纵向拉伸只影响 2dp 的圆角，看不出来。
     */
    private fun stripe(color: Int): Bitmap = stripes.getOrPut(color) {
        val density = context.resources.displayMetrics.density
        val w = (3 * density).toInt().coerceAtLeast(1)
        val h = (32 * density).toInt().coerceAtLeast(1)
        val r = 2 * density
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRoundRect(
                RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
            )
        }
    }

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        // 一屏放 3 条，多的上下滑；刷新时滚到第一节没上完的课（见 ScheduleWidgetUpdater.scrollToUpcoming）。
        courses = when (widgetSize) {
            // 2x2 跟随翻页状态，显示"用户正在看的那天"。
            WidgetSize.SMALL -> ScheduleWidgetUpdater.loadScheduleData(context).courses
            // 4x2 是今天 / 明天两栏。
            WidgetSize.LARGE -> ScheduleWidgetUpdater.loadTwoDayData(context)
                .let { if (dayOffset == 0) it.today else it.tomorrow }
        }
    }

    override fun onDestroy() {
        courses = emptyList()
        stripes.clear()
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
        val timeRange = "%02d:%02d-%02d:%02d".format(
            course.startMinute / 60, course.startMinute % 60, course.endMinute / 60, course.endMinute % 60,
        )
        val place = course.location.ifBlank { "地点待定" }
        // 2x2 着整块浅底，4x2 着左侧色条
        val colorTarget = when (widgetSize) {
            WidgetSize.SMALL -> R.id.widget_course_tone
            WidgetSize.LARGE -> R.id.widget_course_accent
        }
        // 上完的课：灰色、课名删除线。条目视图会被复用，没上完的也要把颜色设回去。
        fun title(text: String): CharSequence =
            if (!course.done) text
            else SpannableString(text).apply { setSpan(StrikethroughSpan(), 0, length, 0) }
        when (widgetSize) {
            WidgetSize.SMALL -> if (course.done) {
                views.setColor(colorTarget, "setColorFilter", R.color.widget_course_done)
            } else {
                views.setInt(colorTarget, "setColorFilter", course.color)
            }
            WidgetSize.LARGE -> views.setImageViewBitmap(
                colorTarget,
                stripe(if (course.done) context.getColor(R.color.widget_course_done) else course.color),
            )
        }
        views.setColorStateList(
            R.id.widget_course_desc, "setTextColor",
            if (course.done) R.color.widget_on_surface_secondary else R.color.widget_on_surface,
        )
        when (widgetSize) {
            // 2x2：一行放得下「课名 · 地点」，时间单独一行。
            WidgetSize.SMALL -> {
                views.setTextViewText(R.id.widget_course_desc, title("${course.name} · $place"))
                views.setTextViewText(R.id.widget_course_time, timeRange)
            }
            // 4x2：每栏只有半个部件宽，课名独占一行，时间和地点合并到第二行。
            WidgetSize.LARGE -> {
                views.setTextViewText(R.id.widget_course_desc, title(course.name))
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
