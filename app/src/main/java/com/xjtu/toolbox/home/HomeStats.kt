package com.xjtu.toolbox.home

import android.content.Context
import com.google.gson.Gson
import com.xjtu.toolbox.util.DataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 一条首页状态：[value] 是大字主数据，[detail] 是补充说明。 */
data class HomeStat(val value: String, val detail: String? = null)

/**
 * 首页各功能的「当前状态」采集器。
 *
 * 原则：**只读本地缓存，一个网络请求都不发**。首页是启动第一屏，为了几行状态去打十几个
 * 校园系统既慢又容易把会话打挂。拿不到就返回 null，对应功能退回纯入口，不显示占位符。
 *
 * 数据来源全是各功能页面自己写下的缓存，所以「用过的功能才有状态」——这符合直觉，
 * 也避免了为首页单独维护一套抓取逻辑。
 */
object HomeStats {

    private val gson = Gson()

    /** 各功能页写入的摘要缓存前缀。key 用 Routes 里的路由名，与首页取值一一对应。 */
    private const val PUSHED_PREFIX = "home_stat_"

    /** 摘要保留 7 天。过期即视为"太旧不可信"，宁可不显示也不显示陈旧数据。 */
    private const val PUSHED_TTL_MS = 7L * 24 * 60 * 60 * 1000L

    /**
     * 功能页在**已经拿到数据**的地方顺手调一次，把一句话摘要留给首页。
     *
     * 这是首页状态的主要来源：首页自己一个请求都不发，谁有数据谁负责写。
     * 传 null 表示"这个功能当前没有值得展示的状态"，会清掉旧摘要，
     * 避免首页长期显示一条早已不成立的信息（比如券已经领完了还写着"3 个待领取"）。
     */
    fun push(context: Context, routeKey: String, value: String?, detail: String? = null) {
        runCatching {
            val cache = DataCache(context)
            val k = PUSHED_PREFIX + routeKey
            if (value.isNullOrBlank()) cache.invalidate(k)
            else cache.put(k, gson.toJson(HomeStat(value, detail)))
        }
    }

    /** 上次主动拉取的时刻，供 [HomeStatsRefresher] 判断过期。 */
    // v4：空结果退避由 6 小时收到 1 小时，同时作废旧戳立即重拉一轮。
    // （升版号是让设备上已有的退避戳失效的唯一手段——戳存在 DataCache 里，重装不清。）
    private const val STAMP_PREFIX = "home_stat_at4_"

    fun stamps(context: Context): Map<String, Long> {
        val cache = DataCache(context)
        return PUSHED_KEYS.associateWith { k ->
            cache.get(STAMP_PREFIX + k, Long.MAX_VALUE)?.toLongOrNull() ?: 0L
        }
    }

    /** 失败后的重试间隔。见 [markFailed]。 */
    const val FAILURE_RETRY_MS = 30L * 60 * 1000L

    /** 「拉成功但没数据」的重试间隔。见 [markEmpty]。 */
    const val EMPTY_RETRY_MS = 1L * 60 * 60 * 1000L

    fun markFetched(context: Context, routeKey: String) {
        runCatching { DataCache(context).put(STAMP_PREFIX + routeKey, System.currentTimeMillis().toString()) }
    }

    /**
     * 失败后打一个「[FAILURE_RETRY_MS] 后就到期」的时间戳。
     *
     * 不能直接按成功处理：真机上出现过一次——WebVPN 网关被误判失败连带考勤拉取失败，
     * 结果按 2 天 TTL 打了正常戳，**故障修好后仍要等两天首页才会再试**。
     * 但也不能完全不打戳，否则某个系统长期挂掉时每次进首页都要重试一轮。
     * 折中：失败按半小时重试。
     */
    fun markFailed(context: Context, routeKey: String, ttlMs: Long) {
        runCatching {
            val fakeLast = System.currentTimeMillis() - ttlMs + FAILURE_RETRY_MS
            DataCache(context).put(STAMP_PREFIX + routeKey, fakeLast.toString())
        }
    }

    /**
     * 「请求成功但没拿到内容」按 [EMPTY_RETRY_MS] 重试，**不打满 TTL**。
     *
     * 若空结果也按成功打满 TTL（评教/体测/黄页是 7 天），那么取数逻辑一旦有 bug，
     * 修好后仍要等整个周期才会重试。空结果与软失败在外部无法区分，
     * 几小时后重试的代价远小于一周不显示。
     */
    fun markEmpty(context: Context, routeKey: String, ttlMs: Long) {
        runCatching {
            val fakeLast = System.currentTimeMillis() - ttlMs + EMPTY_RETRY_MS
            DataCache(context).put(STAMP_PREFIX + routeKey, fakeLast.toString())
        }
    }

    private fun readPushed(cache: DataCache, routeKey: String): HomeStat? =
        runCatching {
            cache.get(PUSHED_PREFIX + routeKey, PUSHED_TTL_MS)
                ?.let { gson.fromJson(it, HomeStat::class.java) }
        }.getOrNull()

    /** 各功能页推送过摘要的路由。加新功能时只要在这里登记，首页即可显示。 */
    private val PUSHED_KEYS = listOf(
        "attendance",       // 本周出勤率
        "iclassface",       // 最新一条刷卡记录
        "lms",              // 最新作业/资料
        "library",          // 常去区域空座
        "judge",            // 待评教门数
        "coupon",           // 待领取 / 待使用
        "fitness",          // 最近学年体测总分
        "yellow_page",      // 教务处 / 保卫处电话
        "jwapp_score",      // 成绩门数 / 新增门数
        "notification",     // 教务处最新通知
    )

    // ── 屁岱主动提醒用的两个游标 ────────────────────────────────────────
    //
    // 与首页摘要分开存：摘要是"现在是什么状态"，这两个是"跟上次比变了没有"。
    // 混在一起会出错——摘要 7 天过期，而基线不能过期，否则过完期就又报一次"新成绩"。

    private const val KEY_SCORE_CURSOR = "proactive_score_total"
    private const val KEY_LATEST_NOTICE = "proactive_latest_notice"

    /**
     * 记录成绩总门数并返回**本次新增了几门**。
     *
     * 首次调用（没有基线）返回 0：不知道之前有多少门，任何数字都可能是全部历史成绩，
     * 报「新增 200 门」只会让人以为坏了。
     *
     * 游标只在这里前移，且只有成功取到数据才会调用——失败时不动基线，
     * 否则一次失败把基线冲成 0，下次就会把全部成绩当成新增。
     */
    fun bumpScoreCursor(context: Context, total: Int): Int = runCatching {
        val prefs = context.getSharedPreferences("home_stats_cursor", Context.MODE_PRIVATE)
        val prev = prefs.getInt(KEY_SCORE_CURSOR, -1)
        prefs.edit().putInt(KEY_SCORE_CURSOR, total).apply()
        if (prev < 0) 0 else (total - prev).coerceAtLeast(0)
    }.getOrDefault(0)

    /** 当前的成绩新增数（供屁岱读取，不改变游标）。 */
    fun pendingNewScores(context: Context): Int = runCatching {
        val prefs = context.getSharedPreferences("home_stats_cursor", Context.MODE_PRIVATE)
        prefs.getInt("proactive_score_new", 0)
    }.getOrDefault(0)

    fun setPendingNewScores(context: Context, n: Int) {
        runCatching {
            context.getSharedPreferences("home_stats_cursor", Context.MODE_PRIVATE)
                .edit().putInt("proactive_score_new", n).apply()
        }
    }

    /**
     * 记下教务处最新一条通知的标题。
     *
     * 返回值表示**是不是没见过的新通知**——屁岱只在标题变化时才提醒，
     * 否则每隔 4 小时抓一次就会把同一条通知反复推给用户。
     */
    fun putLatestNoticeTitle(context: Context, title: String): Boolean = runCatching {
        val prefs = context.getSharedPreferences("home_stats_cursor", Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_LATEST_NOTICE, null)
        if (prev == title) return@runCatching false
        prefs.edit().putString(KEY_LATEST_NOTICE, title)
            .putString("proactive_notice_unseen", title)
            .apply()
        // 首次记录不算"新通知"：这是基线，报出来等于把一条老通知当新的推一次
        prev != null
    }.getOrDefault(false)

    /** 尚未提醒过的新通知标题；没有就返回 null。 */
    fun unseenNoticeTitle(context: Context): String? = runCatching {
        context.getSharedPreferences("home_stats_cursor", Context.MODE_PRIVATE)
            .getString("proactive_notice_unseen", null)
    }.getOrNull()

    /** 提醒已经冒过，清掉待提醒标记，避免重复推同一条。 */
    fun clearUnseenNotice(context: Context) {
        runCatching {
            context.getSharedPreferences("home_stats_cursor", Context.MODE_PRIVATE)
                .edit().remove("proactive_notice_unseen").apply()
        }
    }

    suspend fun collect(context: Context, termCode: String?): Map<String, HomeStat> =
        withContext(Dispatchers.IO) {
            val cache = DataCache(context)
            val out = LinkedHashMap<String, HomeStat>()

            // 各功能页推送的摘要（主要来源）
            PUSHED_KEYS.forEach { k -> readPushed(cache, k)?.let { out[k] = it } }

            // ── 校园卡：余额 + 最近一笔消费 ──
            runCatching {
                com.xjtu.toolbox.card.CampusCardCache.load(context)
            }.getOrNull()?.let { snap ->
                val balance = snap.cardInfo.balance
                val last = snap.transactions.firstOrNull()
                out["campus_card"] = HomeStat(
                    "¥${"%.2f".format(balance)}",
                    when {
                        balance < 20.0 -> "余额偏低，建议充值"
                        last != null -> "最近 ${last.merchant.take(8)} ${"%+.2f".format(last.amount)}"
                        else -> "校园卡余额"
                    }
                )
            }

            if (termCode.isNullOrBlank()) return@withContext out

            // ── 校历 / 教学周：由学期开始日期本地推算，不需要网络 ──
            runCatching {
                cache.get("start_date_$termCode", Long.MAX_VALUE)?.let { raw ->
                    val start = LocalDate.parse(raw.trim().trim('"'))
                    val today = LocalDate.now()
                    val week = ((today.toEpochDay() - start.toEpochDay()) / 7 + 1).toInt()
                    if (week in 1..30) {
                        out["school_calendar"] = HomeStat("第 $week 周", "$termCode 学期")
                    }
                }
            }

            // ── 考试：最近一场 + 倒计时 ──
            runCatching {
                cache.get("exams_$termCode", Long.MAX_VALUE)?.let { json ->
                    // 考试记录结构随教务返回变化，这里按「一组键值对」宽松读取，
                    // 字段名多给几个候选，避免为它单独引一份实体类。
                    val arr: List<Map<String, Any?>> = gson.fromJson(
                        json,
                        object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
                    ) ?: return@let
                    val today = LocalDate.now()
                    val upcoming = arr.mapNotNull { m ->
                        val d = (m["date"] ?: m["examDate"] ?: m["ksrq"])?.toString()?.take(10) ?: return@mapNotNull null
                        val day = runCatching { LocalDate.parse(d) }.getOrNull() ?: return@mapNotNull null
                        if (day < today) return@mapNotNull null
                        Triple(
                            (m["name"] ?: m["courseName"] ?: m["kcmc"])?.toString().orEmpty(),
                            day,
                            (m["location"] ?: m["place"] ?: m["jsmc"])?.toString().orEmpty()
                        )
                    }.sortedBy { it.second }
                    upcoming.firstOrNull()?.let { e ->
                        val days = e.second.toEpochDay() - today.toEpochDay()
                        // 挂在「日程」下：没有下节课时它就是这一类最该被看到的信息
                        out["schedule"] = HomeStat(
                            e.first.ifBlank { "考试" },
                            buildString {
                                append(if (days == 0L) "就在今天" else "还有 $days 天")
                                if (e.third.isNotBlank()) append(" · ${e.third}")
                            }
                        )
                    }
                }
            }

            // ── 日程兜底：既没有下节课（Hero 算的）也没有考试时，至少告诉用户本学期有多少门课。
            // 否则「日程」这一格在假期/短学期里永远是空的，看着像功能坏了。
            if (!out.containsKey("schedule")) {
                runCatching {
                    val courses = com.xjtu.toolbox.schedule.ScheduleCache
                        .readOptimizedCourses(cache, gson, termCode)
                        ?: com.xjtu.toolbox.schedule.ScheduleCache
                            .readRawCourses(cache, gson, termCode)
                    if (!courses.isNullOrEmpty()) {
                        out["schedule"] = HomeStat("${courses.size} 门课", "$termCode 学期")
                    }
                }
            }

            // ── 教材：本学期册数 ──
            runCatching {
                cache.get(com.xjtu.toolbox.schedule.ScheduleCache.textbookKey(termCode), Long.MAX_VALUE)
                    ?.let { json ->
                        val n = gson.fromJson(json, Array<Any>::class.java)?.size ?: 0
                        if (n > 0) out["jiaocai"] = HomeStat("$n 本", "本学期教材")
                    }
            }

            out
        }
}
