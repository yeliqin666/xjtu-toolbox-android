package com.xjtu.toolbox.card

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.AppJson
import kotlinx.datetime.LocalDate

// ==================== 校园卡在 Android 上的落盘 ====================
//
// 这个文件原来还带着两个模型（`TodaySpendSummary` / `CampusCardSnapshot`）与三处纯计算
//（`todaySummaryOf` / `dailySpendRate` / `runwayDays`+`runwayText`）。屏与 ViewModel 搬进 :core 后
// 那些跟着走了（模型在 `CampusCardModels.kt`、计算在 `CampusCardAnalysis.kt`），这里**只剩落盘**：
// 两份 SharedPreferences（`campus_card_data_cache*` 存快照、`campus_card*` 存首页/小组件读的那组 key），
// 文件与键名**逐字未动**（首页卡片、桌面小组件、AgentTool、DiningHabit 读的还是同一份）。

/** 写进 [CampusCardCache.cardPrefs]，首页读；算不出来时保留上一次的值。 */
fun android.content.SharedPreferences.Editor.putDailyRate(rate: Double?): android.content.SharedPreferences.Editor =
    if (rate == null) this else putFloat("card_daily_rate_cache", rate.toFloat())

/** 写入 [CampusCardCache.cardPrefs] 里首页卡片与校园卡小组件读取的那组 key。 */
fun android.content.SharedPreferences.Editor.putTodaySummary(s: TodaySpendSummary): android.content.SharedPreferences.Editor =
    putFloat("card_today_spend_cache", s.total.toFloat())
        .putFloat("card_today_breakfast_cache", s.breakfast.toFloat())
        .putFloat("card_today_lunch_cache", s.lunch.toFloat())
        .putFloat("card_today_dinner_cache", s.dinner.toFloat())
        // 以前还写一份最近 5 条流水的 JSON，全仓没有任何地方读，顺手清掉旧值
        .remove("card_recent_tx_cache")


object CampusCardCache {
    private const val PREFS_PREFIX = "campus_card_data_cache"
    private const val KEY = "snapshot"

    private fun prefsName(accountId: String? = AccountContext.activeAccountId): String =
        PREFS_PREFIX + AccountContext.suffixFor(accountId)

    fun load(context: Context, accountId: String? = AccountContext.activeAccountId): CampusCardSnapshot? {
        val raw = context.getSharedPreferences(prefsName(accountId), Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return runCatching { AppJson.decodeFromString<CampusCardSnapshot>(raw) }.getOrNull()
    }

    fun save(
        context: Context,
        cardInfo: CardInfo,
        transactions: List<Transaction>,
        // kotlinx-datetime 的日期（端口与屏都用它）；这里只 `toString()` 存成 `yyyy-MM-dd`，
        // 与原来 java.time 的 `LocalDate.toString()` 逐字相同 ⇒ 落盘格式不变。
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        accountId: String? = AccountContext.activeAccountId,
    ) {
        val snapshot = CampusCardSnapshot(
            cardInfo = cardInfo,
            transactions = transactions,
            rangeStart = rangeStart.toString(),
            rangeEnd = rangeEnd.toString(),
            savedAt = System.currentTimeMillis()
        )
        context.getSharedPreferences(prefsName(accountId), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, AppJson.encodeToString(snapshot))
            .apply()
    }

    /** 删除指定账号（默认当前账号）的校园卡缓存。删除账号时传被删的那个。 */
    fun clear(context: Context, accountId: String? = AccountContext.activeAccountId) {
        context.getSharedPreferences(prefsName(accountId), Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * 指定账号（默认当前账号）命名空间下的校园卡余额/流水缓存 SharedPreferences。
     * 联网刷新要在发起请求时定下账号传进来，理由见 [AccountContext.suffixFor]。
     */
    fun cardPrefs(
        context: Context,
        accountId: String? = AccountContext.activeAccountId,
    ): android.content.SharedPreferences =
        context.getSharedPreferences("campus_card" + AccountContext.suffixFor(accountId), Context.MODE_PRIVATE)
}
