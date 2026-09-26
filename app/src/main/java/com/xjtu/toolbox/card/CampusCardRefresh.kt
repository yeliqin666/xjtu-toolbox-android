package com.xjtu.toolbox.card

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.widget.CampusCardWidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 抓一次校园卡余额与今日流水写进缓存，并刷新桌面小组件。
 *
 * 调用方：首页数据刷新（[com.xjtu.toolbox.home.HomeStatsRefresher]，和别的首页数据源共用
 * 同一套 TTL / 退避 / 串行节奏），以及回到前台时的一分钟节流刷新（AppRoot）。
 */
suspend fun refreshCampusCardCache(
    context: Context,
    site: SiteSession,
): Boolean = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    // 请求发出前定下账号：结果回来时可能已切到别的账号
    val accountId = AccountContext.activeAccountId
    val api = CampusCardApi(site)
    val info = api.getCardInfo()
    val (_, recentTx) = api.getTransactions(page = 1, pageSize = 50)

    CampusCardCache.cardPrefs(appContext, accountId).edit()
        .putFloat("card_balance_cache", info.balance.toFloat())
        .putString("card_name_cache", info.name)
        .putLong("card_cache_time", System.currentTimeMillis())
        .putTodaySummary(todaySummaryOf(recentTx))
        .putDailyRate(dailySpendRate(recentTx))
        .apply()
    CampusCardWidgetUpdater.requestUpdate(appContext)
    true
}
