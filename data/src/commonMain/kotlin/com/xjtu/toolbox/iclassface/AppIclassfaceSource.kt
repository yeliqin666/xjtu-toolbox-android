package com.xjtu.toolbox.iclassface

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * `:core` 的 [IclassfaceSource] 在 `:data` 里的实现（Android 与桌面侧共用）—— 包住原来的
 * [IclassfaceApi]（okhttp 抓 `iclassface.xjtu.edu.cn` 的服务端渲染表格）。
 *
 * 它从 `:app` 搬进 `:data`（桌面端第 20 条真数据路由：桌面要自己登 `iclassface`、自己查记录）。
 * 取数的类名与包路径一字未改，只有一件事跟着搬：**调度器** —— 搬迁前是屏里
 * `withContext(Dispatchers.IO) { api.fetchRecords(date) }`，现在屏不再替实现挑调度器
 *（见 [IclassfaceSource] 的 KDoc），由本类自己包 —— **同一层、同一个调度器，行为不变**。
 *
 * 会话仍然是 `:data` 的 [SiteSession]（`iclassface`，见 [com.xjtu.toolbox.auth.IclassfaceSession]
 * 与 [IclassfaceLogin]）：`PHPSESSID` cookie 由会话内核自动带上，与搬迁前逐字相同。
 */
class AppIclassfaceSource(private val site: SiteSession) : IclassfaceSource {

    private val api = IclassfaceApi(site)

    override suspend fun fetchRecords(date: LocalDate): List<CheckinRecord> =
        withContext(Dispatchers.IO) { api.fetchRecords(date) }
}
