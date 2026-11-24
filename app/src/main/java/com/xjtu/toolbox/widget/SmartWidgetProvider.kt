package com.xjtu.toolbox.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.attendance.AttendanceRecordStore
import com.xjtu.toolbox.card.CampusCardCache
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.inbox.InboxRules
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.library.LibrarySeatStore
import com.xjtu.toolbox.notification.LibraryActionReceiver
import com.xjtu.toolbox.notification.LibraryReminderWorker
import com.xjtu.toolbox.schedule.ExamCountdown
import com.xjtu.toolbox.schedule.HolidayApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleDiff
import com.xjtu.toolbox.schedule.attendanceMarksOf
import com.xjtu.toolbox.schedule.scheduleDiffStore
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.util.CampusZoneId
import com.xjtu.toolbox.util.RequestCodes
import com.xjtu.toolbox.util.todayOnCampus
import com.xjtu.toolbox.widget.smart.NavKey
import com.xjtu.toolbox.widget.smart.SeatKey
import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartCard
import com.xjtu.toolbox.widget.smart.SmartFacts
import com.xjtu.toolbox.widget.smart.SmartKey
import com.xjtu.toolbox.widget.smart.SmartKeyKind
import com.xjtu.toolbox.widget.smart.SmartLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * 智能卡片（#408）：**只读本机缓存**，按此刻挑一屏，其余一概不联网。
 *
 * 三件事分在三个地方，各有各的判据，谁都不许越界：
 * - **读什么** —— [factsOf]：把本机已有的缓存全读成一份 [SmartFacts]（这里不判语义）；
 * - **画什么** —— [SmartArbiter]：按 `docs/smart-widget.md` §3.1 的仲裁表判出一屏（纯函数）；
 * - **怎么排** —— [SmartLayout]：选形态、算高度预算、不够就按块降级（纯函数）。
 *
 * 这一层只做两件 Android 专属的事：把 RemoteViews 填好，和定下一个闹钟。
 *
 * **绝不在这里登录任何站点。** 小组件会被系统随桌面反复拉起，为此反复登录既慢又容易被判
 * 异常访问。联网一律走 `WorkManager`（见 [AttendanceWidgetSync]），完成后发广播重画。
 * 唯一的联网例外是「按课在两个离散时刻问一次考勤」——那是用户明确要求的，且一节课只有两次、
 * 无课日一次都没有（§6.3）。
 */
object SmartWidgetUpdater {
    const val ACTION_REFRESH = "com.xjtu.toolbox.widget.ACTION_REFRESH_SMART_WIDGET"

    /** 用户点了卡片上的「刷新」：立刻去问一次学校，不等该问的那个时刻。 */
    const val ACTION_REFRESH_NOW = "com.xjtu.toolbox.widget.ACTION_REFRESH_NOW_SMART_WIDGET"

    private const val CLASS_LEAD_MIN = SmartArbiter.CLASS_LEAD_MIN

    /** 早、午、晚饭的窗口（距 00:00 的分钟），只用来排闹钟；判语义在 [SmartArbiter]。 */
    private val MEALS = listOf(
        6 * 60 + 30 to 9 * 60,
        11 * 60 to 13 * 60 + 30,
        17 * 60 to 19 * 60 + 30,
    )

    fun requestUpdate(context: Context) {
        context.sendBroadcast(Intent(context, SmartWidgetProvider::class.java).setAction(ACTION_REFRESH))
    }

    internal fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        com.xjtu.toolbox.account.AccountContext.restoreIfNeeded()
        val now = LocalDateTime.now(CampusZoneId)
        val facts = factsOf(context, now)
        val card = SmartArbiter.card(facts)
        for (id in ids) render(context, manager, id, card)
        scheduleNext(context, facts, now)
        // 这一屏还欠不欠一次查询（Q1/Q2 没问 / 结论已经放太久）：欠就后台补，但**不打断这一屏**
        if (SmartArbiter.checkNeeded(facts) != SmartArbiter.CheckKind.NONE) {
            AttendanceWidgetSync.requestAutoSync(context)
        }
    }

    // ══════ 读缓存 ══════

    /**
     * 把本机已有的事实读齐。
     *
     * 每一条都 [runCatching] 兜住：小组件进程是被系统拉起的，存储可能正在迁移、Room 可能还没
     * 打开。任何一项读失败只让那一档退让，绝不能整张卡片空白。
     */
    internal fun factsOf(context: Context, now: LocalDateTime): SmartFacts {
        val nowMs = System.currentTimeMillis()
        val nowMinute = now.hour * 60 + now.minute
        val today = now.toLocalDate().toString()
        val courses = runCatching { ScheduleWidgetUpdater.todayCourses(context) }.getOrDefault(emptyList())
        // 后台自查问到的那条刷卡 + 上次问的时刻：卡片据此说「已刷卡 · 待结算」「08:05 查过」
        val swipe = runCatching { AttendanceWidgetSync.todaySwipe(context, today) }.getOrNull()
        val lastCheck = runCatching { AttendanceWidgetSync.lastCheckAt(context) }.getOrDefault(0L)
        val checked = lastCheck.takeIf { it > 0L }

        val cardPrefs = runCatching { CampusCardCache.cardPrefs(context) }.getOrNull()
        val balanceRaw = cardPrefs?.getFloat("card_balance_cache", -1f) ?: -1f
        val balanceAt = cardPrefs?.getLong("card_cache_time", 0L) ?: 0L
        val spend = cardPrefs?.getFloat("card_today_spend_cache", -1f) ?: -1f

        val cache = runCatching { DataCache(context) }.getOrNull()
        val term = cache?.let { runCatching { ScheduleCache.readCurrentTerm(it) }.getOrNull() }
        val exams = term?.let { runCatching { ScheduleCache.readExams(cache, it) }.getOrNull() }
        val holiday = cache?.let {
            runCatching { HolidayApi.peekCached(it)[todayOnCampus()] }.getOrNull()
        }

        return SmartFacts(
            nowMinute = nowMinute,
            nowMs = nowMs,
            zoneOffsetMinutes = now.atZone(CampusZoneId).offset.totalSeconds / 60,
            courses = courses,
            attendance = runCatching { todayAttendance(context) }.getOrNull(),
            date = today,
            swipe = swipe,
            checkedAgoMin = checked?.let { ((nowMs - it) / 60_000L).toInt() },
            lastCheckAtMs = checked,
            checkedAtClock = checked?.let { clockOf(it) },
            balance = if (balanceRaw < 0f) null else "¥%.2f".format(balanceRaw),
            balanceClock = balanceAt.takeIf { it > 0L }?.let { clockOf(it) },
            todaySpend = if (spend <= 0f) null else "¥%.2f".format(spend),
            todos = runCatching { InboxRules.todos(InboxStore.load(), nowMs) }.getOrDefault(emptyList()),
            library = runCatching { LibrarySeatStore.load(context)?.takeIf { it.active } }.getOrNull(),
            exam = exams?.let { runCatching { ExamCountdown.next(it) }.getOrNull() },
            holiday = holiday,
            scheduleDiff = runCatching { ScheduleDiff.pending(scheduleDiffStore()) }.getOrNull(),
            notice = runCatching {
                // 通知没标题可说，只能当钩子（§4 的 M12）：有新的就写一句，没有就不写
                val entries = NoticeWidgetStore.read(context).entries
                if (entries.isEmpty()) null else "教务 · 新通知"
            }.getOrNull(),
        )
    }

    /**
     * 这一学期落在本机的考勤索引。
     *
     * 读的是考勤流水落在本机的那份（[AttendanceRecordStore]），也就是课表格子角标用的同一份数据。
     * **卡片里绝不登录考勤站点**：小组件会随桌面反复刷新、还会被系统在后台拉起，
     * 为此反复登录既慢又容易被判异常访问。
     *
     * 账号类型必须现问 [com.xjtu.toolbox.account.AccountStore]：小组件进程是被系统拉起的，
     * [restoreIfNeeded] 只补了 `activeAccountId`，`activeAccountType` 还是默认的「本科」——
     * 研究生会去读本科那份分片，永远读不到自己的记录，表现同样是「考勤未记录」。
     *
     * @return 拿不到落盘缓存时返回 null。
     */
    internal fun todayAttendance(context: Context): com.xjtu.toolbox.schedule.SlotAttendanceMarks? {
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

    // ══════ 填 RemoteViews ══════

    /**
     * 按实例的实际尺寸排版。桌面在竖屏、横屏下给的尺寸不同（`OPTION_APPWIDGET_SIZES`），
     * 每个尺寸各排一份，交给 `RemoteViews(Map<SizeF, RemoteViews>)` 由桌面按当下尺寸挑；
     * 拿不到尺寸列表时退回最小宽高排一份。
     */
    private fun render(context: Context, manager: AppWidgetManager, id: Int, card: SmartCard) {
        val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val sizes = options
            ?.let { BundleCompat.getParcelableArrayList(it, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java) }
            .orEmpty().distinct().take(MAX_SIZES)
        val views = if (sizes.isEmpty()) {
            build(
                context, id, card,
                widthDp = optionDp(options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0),
                heightDp = optionDp(options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0),
            )
        } else {
            RemoteViews(sizes.associateWith { build(context, id, card, it.width.toInt(), it.height.toInt()) })
        }
        manager.updateAppWidget(id, views)
    }

    /** `RemoteViews(Map)` 最多收 16 个尺寸。 */
    private const val MAX_SIZES = 16

    /**
     * 排一个尺寸：形态跟宽度走，块数、主数字、细节行数由 [SmartLayout.plan] 按高度定。
     * 两套布局的控件 id 完全相同，差异只在排版上。
     */
    private fun build(context: Context, id: Int, card: SmartCard, widthDp: Int, heightDp: Int): RemoteViews {
        val form = SmartLayout.formOf(widthDp)
        val plan = SmartLayout.plan(card, form, heightDp, widthDp)
        val layout = when (form) {
            SmartLayout.Form.WIDE -> R.layout.widget_smart_wide
            SmartLayout.Form.COMPACT -> R.layout.widget_smart_compact
        }
        val views = RemoteViews(context.packageName, layout)

        // 一个块 / 两个块：每块整块可点，块内的图标各有各的 id，互不覆盖
        bindBlock(context, views, id, SLOT_BLOCK1, plan.blocks.getOrNull(0), ROW1, plan.twoBlocks, plan.hero)
        bindBlock(context, views, id, SLOT_BLOCK2, plan.blocks.getOrNull(1), ROW2, plan.twoBlocks, hero = false)

        // 底部那条：次要提示 + 刷新。提示只读、不做点击目标 —— 点空白不该乱跳。
        views.putText(R.id.widget_smart_hint, plan.hint)
        views.setViewVisibility(R.id.widget_smart_refresh, View.VISIBLE)
        views.setImageViewResource(R.id.widget_smart_refresh, R.drawable.ic_widget_refresh)
        views.setInt(
            R.id.widget_smart_refresh,
            "setColorFilter",
            context.getColor(R.color.widget_on_surface_secondary),
        )
        views.setContentDescription(R.id.widget_smart_refresh, "刷新")
        views.setOnClickPendingIntent(R.id.widget_smart_refresh, refreshNowIntent(context))
        return views
    }

    /** 一个块的八个 id：容器（整块可点）、场景行、标题、状态、两行细节、两个键。 */
    private class BlockIds(
        val container: Int,
        val label: Int,
        val title: Int,
        val status: Int,
        val detail: Int,
        val detail2: Int,
        val keyA: Int,
        val keyB: Int,
    )

    private fun bindBlock(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        slot: Int,
        rendered: SmartLayout.RenderedBlock?,
        ids: BlockIds,
        tiled: Boolean,
        hero: Boolean,
    ) {
        if (rendered == null) {
            views.setViewVisibility(ids.container, View.GONE)
            return
        }
        val block = rendered.block
        views.setViewVisibility(ids.container, View.VISIBLE)

        // 两件事并列时各垫一层深一档的底板；只有一件事时直接铺在卡上
        views.setInt(ids.container, "setBackgroundResource", if (tiled) R.drawable.bg_widget_tile else 0)
        val density = context.resources.displayMetrics.density
        val padH = if (tiled) (SmartLayout.TILE_PAD_H_DP * density).roundToInt() else 0
        val padV = if (tiled) (SmartLayout.TILE_PAD_V_DP * density).roundToInt() else 0
        views.setViewPadding(ids.container, padH, padV, padH, padV)

        val titleSp = if (hero) SmartLayout.SIZE_HERO else SmartLayout.SIZE_TITLE
        val titleDp = if (hero) SmartLayout.BLOCK_HERO_DP else SmartLayout.BLOCK_TITLE_DP
        views.setTextViewTextSize(ids.title, TypedValue.COMPLEX_UNIT_SP, titleSp.toFloat())
        views.setViewLayoutHeight(ids.title, titleDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)

        views.putText(ids.label, block.label)
        views.putText(ids.title, block.title)
        views.putText(ids.status, block.statusLine)
        views.putText(ids.detail, block.detail)
        views.putText(ids.detail2, block.detail2)
        block.statusColor?.let { views.setTextColor(ids.status, it) }

        // **块整体是一个点击目标**：点场景/标题/状态/细节都进这件事对应的页面。
        // 请求码与下面两个图标必须分开 —— 共用时 PendingIntent 会互相覆盖，
        // 表现出来就是「点块跳到了图标的地方」。
        val open = contentIntent(context, widgetId, block.route, slot)
        views.setOnClickPendingIntent(ids.container, open)

        bindKey(context, views, widgetId, slot + 1, ids.keyA, block.keys.getOrNull(0))
        bindKey(context, views, widgetId, slot + 2, ids.keyB, block.keys.getOrNull(1))
    }

    /**
     * 把 `getAppWidgetOptions` 里的尺寸换成 dp。
     *
     * 坑在这儿：不同版本、不同桌面塞进 Bundle 的单位并不一致 —— 同一个 110dp 的 2×2，
     * 有的给 `110`，有的给 `28160`（帧内部按 1/256 dp 存）。不归一的话，一个 2×2 会被
     * 判成「宽 110 个 1/256 dp = 28160dp」，直接进宽卡形态。
     * 判据：真机上的小组件宽度不可能超过几千 dp，超过就按 1/256 dp 换算。
     */
    internal fun optionDp(raw: Int): Int = when {
        raw <= 0 -> 0
        raw > 4000 -> raw / 256
        else -> raw
    }

    private fun bindKey(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        slot: Int,
        keyId: Int,
        key: SmartKey?,
    ) {
        if (key == null) {
            views.setViewVisibility(keyId, View.GONE)
            return
        }
        views.setViewVisibility(keyId, View.VISIBLE)
        views.setImageViewResource(keyId, iconOf(key.kind))
        // 图标是单色矢量，颜色由这里给：android:tint 在 RemoteViews 里不可靠，用标准方法着色
        views.setInt(keyId, "setColorFilter", context.getColor(R.color.widget_on_key))
        // 无障碍朗读要读出动作名，而不是「图片」
        views.setContentDescription(keyId, key.label)
        views.setOnClickPendingIntent(keyId, keyIntent(context, widgetId, slot, key))
    }

    /**
     * 键盘上的一个图标。
     *
     * 一种键一个图标，一一对应 —— 没有多余的备用图标，也没有一个图标干两件事的歧义。
     * 全部是 24dp 单色矢量，实际颜色由 `setColorFilter` 在运行时给。
     */
    private fun iconOf(kind: SmartKeyKind): Int = when (kind) {
        SmartKeyKind.ATTENDANCE -> R.drawable.ic_widget_attendance
        SmartKeyKind.QR -> R.drawable.ic_widget_qr
        SmartKeyKind.PAYMENT -> R.drawable.ic_widget_payment
        SmartKeyKind.CALENDAR -> R.drawable.ic_widget_calendar
        SmartKeyKind.CHECKIN -> R.drawable.ic_widget_checkin
        SmartKeyKind.INBOX -> R.drawable.ic_widget_inbox
        SmartKeyKind.LEAVE -> R.drawable.ic_widget_leave
        SmartKeyKind.RETURN -> R.drawable.ic_widget_return
        SmartKeyKind.CANCEL -> R.drawable.ic_widget_cancel
    }

    /**
     * 块里的一个键按下去做什么。
     *
     * 两种：**动作键**发广播给现成的后台 Worker（不打开 App）、**导航键**带路由启动 App。
     * 请求码按「实例 + 位置」分开 —— `PendingIntent` 判等不看 extras，共用请求码会让后画的
     * 把先画的意图覆盖掉。
     */
    private fun keyIntent(context: Context, widgetId: Int, slot: Int, key: SmartKey): PendingIntent = when (key) {
        is SeatKey -> PendingIntent.getBroadcast(
            context, requestCode(widgetId, slot),
            Intent(context, LibraryActionReceiver::class.java)
                .setAction(LibraryActionReceiver.INTENT_ACTION)
                .putExtra(LibraryReminderWorker.KEY_ACTION, key.action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        is NavKey -> contentIntent(context, widgetId, key.route, slot)
    }

    private fun contentIntent(context: Context, widgetId: Int, route: AppRoute, slot: Int = 0): PendingIntent {
        val launch = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, route.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode(widgetId, slot), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 请求码 = 基址 + 实例号 × 10 + 位置。
     *
     * **块与它内部的图标必须占不同位置**：`PendingIntent` 判等不看 extras，共用请求码时
     * `FLAG_UPDATE_CURRENT` 会让后设置的那个把先设置的覆盖掉 —— 表现出来就是
     * 「点块跳到了图标的页面」。下面六个 SLOT 常量为此分开（单测断言互不相同）。
     */
    private fun requestCode(widgetId: Int, slot: Int): Int =
        RequestCodes.SMART_WIDGET + widgetId.coerceAtLeast(0) * 10 + slot

    /** 块 1 本体 / 它的两个键；块 2 本体 / 它的两个键。互不相同。 */
    internal const val SLOT_BLOCK1 = 0
    internal const val SLOT_KEY1A = 1
    internal const val SLOT_KEY1B = 2
    internal const val SLOT_BLOCK2 = 3
    internal const val SLOT_KEY2A = 4
    internal const val SLOT_KEY2B = 5

    /** 一个实例上会用到的全部请求码（单测断言它们互不相同）。 */
    internal fun requestCodesOf(widgetId: Int): List<Int> =
        listOf(SLOT_BLOCK1, SLOT_KEY1A, SLOT_KEY1B, SLOT_BLOCK2, SLOT_KEY2A, SLOT_KEY2B)
            .map { requestCode(widgetId, it) }

    private fun RemoteViews.putText(id: Int, value: String?) {
        setTextViewText(id, value.orEmpty())
        setViewVisibility(id, if (value.isNullOrBlank()) View.GONE else View.VISIBLE)
    }

    /** 块 1 / 块 2 的 id 组。 */
    private val ROW1 = BlockIds(
        container = R.id.widget_smart_block1,
        label = R.id.widget_smart_label1,
        title = R.id.widget_smart_title1,
        status = R.id.widget_smart_status1,
        detail = R.id.widget_smart_detail1,
        detail2 = R.id.widget_smart_detail1b,
        keyA = R.id.widget_smart_key1a,
        keyB = R.id.widget_smart_key1b,
    )

    private val ROW2 = BlockIds(
        container = R.id.widget_smart_block2,
        label = R.id.widget_smart_label2,
        title = R.id.widget_smart_title2,
        status = R.id.widget_smart_status2,
        detail = R.id.widget_smart_detail2,
        detail2 = R.id.widget_smart_detail2b,
        keyA = R.id.widget_smart_key2a,
        keyB = R.id.widget_smart_key2b,
    )

    // ══════ 闹钟 ══════

    /**
     * 下一个场景边界（§6.2）：课前提醒起点、上课、**打卡窗口关**、下课、饭点起止、待办截止、
     * 跨天零点。只本地重画，零联网。
     *
     * 「打卡窗口关」（[SmartArbiter.attendanceEndMinute]）这条不能少：系统只在边界时刻重画，
     * 而准时上课的人根本收不到那条迟到提醒——偏偏那是这张卡片最有用的信息。
     */
    private fun scheduleNext(context: Context, facts: SmartFacts, now: LocalDateTime) {
        val nowMinute = facts.nowMinute
        val boundaries = buildList {
            facts.courses.forEach {
                add(it.startMinute - CLASS_LEAD_MIN)
                add(it.startMinute)
                add(SmartArbiter.attendanceEndMinute(it))
                add(it.endMinute)
            }
            MEALS.forEach { (start, end) -> add(start); add(end) }
            // 待办进「两小时内」那一档的时刻，以及截止那一刻
            facts.todos.forEach { todo ->
                if (todo.expiresAt > 0L) {
                    val minute = facts.minuteOf(todo.expiresAt)
                    add(minute)
                    add(minute - SmartArbiter.TODO_P0_MIN)
                }
            }
        }
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
     * 最省事的一条路，也让自动自查不必为了响应速度一路高频。
     */
    private fun refreshNowIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RequestCodes.SMART_WIDGET_REFRESH_NOW,
        Intent(context, SmartWidgetProvider::class.java).setAction(ACTION_REFRESH_NOW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** epoch millis → `08:05`（北京时间）。 */
    private fun clockOf(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(CampusZoneId)
        return "%02d:%02d".format(t.hour, t.minute)
    }
}

/**
 * 小组件入口。渲染要读缓存、查 Room，所以 `goAsync` 后放到 IO 线程做，和课表小组件一样。
 */
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

    private fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, SmartWidgetProvider::class.java))

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAsync(context) { appWidgetIds }
    }

    /** 拖成 4×2 / 2×4：换了形态就重画一张（§11 第 6 条）。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        updateAsync(context) { intArrayOf(appWidgetId) }
    }

    override fun onDisabled(context: Context) = SmartWidgetUpdater.cancel(context)

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        when (intent?.action) {
            SmartWidgetUpdater.ACTION_REFRESH -> updateAsync(context) { ids(context) }
            // 点了卡片上的「刷新」：先把「正在查询…」挂上让人看见反应，再绕过节流去问一次
            SmartWidgetUpdater.ACTION_REFRESH_NOW -> {
                AttendanceWidgetSync.markQuerying(context)
                AttendanceWidgetSync.requestSync(context)
                updateAsync(context) { ids(context) }
            }
        }
    }
}
